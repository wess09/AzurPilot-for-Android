package com.azurpilot.ghio.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.azurpilot.download.ParallelDownloader
import com.azurpilot.ghio.provision.RuntimeArch
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 覆盖 Mirror 酱路由、免 CDK 查询、授权下载与回退条件。
 *
 * Covers MirrorChyan routing, public lookup, authorized downloads, and fallback conditions.
 */
class MirrorChyanTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun lookupUsesOfficialPackageRoutesWithoutAnImplicitKey() {
        val routes = listOf(
            Triple("AzurPilotAndroid", "android", "arm64"),
            Triple("AzurPilotAndroid", "android", "x86_64"),
            Triple("AzurPilotAndroidUpdate", "android", "arm64"),
            Triple("AzurPilotAndroidUpdate", "android", "x86_64"),
            Triple("AzurPilotAndroidUpdate", "android", null),
            Triple("AzurPilotAndroidRootfs", null, "arm64"),
            Triple("AzurPilotAndroidRootfs", null, "x86_64"),
        )
        for ((resource, route) in MirrorChyanResource.entries.zip(routes)) {
            val (rid, os, arch) = route
            val url = mirrorChyanLookupUrl(resource)
            assertEquals("/api/resources/$rid/latest", url.encodedPath)
            assertEquals(os, url.queryParameter("os"))
            assertEquals(arch, url.queryParameter("arch"))
            assertEquals("", url.queryParameter("current_version"))
            assertEquals("stable", url.queryParameter("channel"))
            assertEquals("AzurPilotAndroid", url.queryParameter("user_agent"))
            assertNull(url.queryParameter("cdk"))
        }
        assertEquals("key+/&=", mirrorChyanLookupUrl(resource, "key+/&=").queryParameter("cdk"))
    }

    @Test
    fun apkRouteTracksTheSelectedManifestAsset() {
        fun apk(name: String) = JSONObject()
            .put("url", ReleaseUrls.BASE + name)
            .put("sha256", checksum)
            .put("size", 3)

        val manifest = JSONObject()
            .put("versionCode", 238)
            .put("versionName", "1.3.238")
            .put("apkUrl", ReleaseUrls.BASE + "AzurPilot-Android-1.3.238-update.apk")
            .put("apkSha256", checksum)
            .put("apkSize", 3)
            .put("fullApks", JSONObject().put(
                RuntimeArch.X86_64, apk("AzurPilot-Android-1.3.238-x86_64-full.apk"),
            ))
        assertEquals(
            MirrorChyanResource.FULL_X86_64,
            parseAppUpdateInfo(manifest, RuntimeArch.X86_64)?.mirrorResource,
        )
        assertEquals(
            MirrorChyanResource.UPDATE_UNIVERSAL,
            parseAppUpdateInfo(manifest, RuntimeArch.ARM64)?.mirrorResource,
        )

        val slimName = "AzurPilot-Android-1.3.238-x86_64-update-${checksum.take(16)}.apk"
        manifest.put("updateApks", JSONObject().put(RuntimeArch.X86_64, apk(slimName)))
        val update = parseAppUpdateInfo(manifest, RuntimeArch.X86_64)
        assertEquals(MirrorChyanResource.UPDATE_X86_64, update?.mirrorResource)
        assertEquals(ReleaseUrls.BASE + slimName, update?.apkUrl)
    }

    @Test
    fun enabledWithoutCdkChecksVersionAndSkipsSignedDownload() {
        val queried = CountDownLatch(1)
        withMirror(
            onLookup = {
                assertNull(it.url.queryParameter("cdk"))
                queried.countDown()
                it.reply(200, publicVersion)
            },
        ) { mirror ->
            mirror.setEnabled(true)
            mirror.check(resource)
            assertTrue(queried.await(5, TimeUnit.SECONDS))
            assertFalse(download(mirror))
        }
    }

    @Test
    fun stalePublicVersionDoesNotRequestSignedUrl() = withMirror(
        onLookup = {
            assertNull(it.url.queryParameter("cdk"))
            it.reply(200, """{"code":0,"data":{"version_name":"1.3.200"}}""")
        },
    ) { mirror ->
        mirror.saveCdk("synthetic-key")
        assertFalse(download(mirror))
        assertEquals(MirrorChyanNotice.NOT_SYNCED, mirror.notice.value)
    }

    @Test
    fun validMetadataAndBytesCompleteDownload() = withMirror(
        onLookup = { request ->
            val cdk = request.url.queryParameter("cdk")
            if (cdk != null) assertEquals("synthetic-key", cdk)
            request.reply(200, if (cdk == null) publicVersion else signedPayload())
        },
        onDownload = {
            assertEquals("https://cdn.example.com/file?signature=synthetic", it.url.toString())
            it.reply(200, body)
        },
    ) { mirror ->
        mirror.saveCdk("synthetic-key")
        assertTrue(download(mirror))
        assertEquals(body, target.readText())
        assertEquals(MirrorChyanNotice.USED, mirror.notice.value)
    }

    @Test
    fun sameSizeCorruptBytesAreDiscardedBeforeFallback() = withMirror(
        onLookup = { it.reply(200, if (it.url.queryParameter("cdk") == null) publicVersion else signedPayload()) },
        onDownload = { it.reply(200, "bad") },
    ) { mirror ->
        mirror.saveCdk("synthetic-key")
        assertFalse(download(mirror))
        assertFalse(target.exists())
        assertEquals(MirrorChyanNotice.FAILED, mirror.notice.value)
    }

    @Test
    fun missingPlatformTimeoutAndInvalidKeyReturnFallback() {
        for ((code, notice) in listOf(
            8001 to MirrorChyanNotice.UNAVAILABLE,
            -1 to MirrorChyanNotice.FAILED,
            7002 to MirrorChyanNotice.INVALID,
        )) {
            withMirror(onLookup = {
                when {
                    code == -1 -> throw SocketTimeoutException("synthetic network timeout")
                    code == 8001 -> it.reply(404, """{"code":8001}""")
                    it.url.queryParameter("cdk") == null -> it.reply(200, publicVersion)
                    else -> it.reply(403, """{"code":7002,"msg":"untrusted server detail"}""")
                }
            }) { mirror ->
                mirror.saveCdk("synthetic-key")
                assertFalse(download(mirror))
                assertEquals(notice, mirror.notice.value)
            }
        }
    }

    @Test
    fun rejectsNonmatchingOrNonHttpsDownloadMetadata() {
        fun url(
            version: String = "v1.3.238",
            size: Long = 3,
            sha256: String = checksum,
            type: String = "full",
            address: String = "https://cdn.example.com/file",
        ): HttpUrl? = MirrorChyanPayload(version, address, sha256, size, type)
            .matchingUrl(resource, "1.3.238", 3, checksum)

        assertNotNull(url())
        assertNull(url(version = "1.3.237"))
        assertNull(url(size = 4))
        assertNull(url(sha256 = "a".repeat(64)))
        assertNull(url(type = "incremental"))
        assertNull(url(address = "http://cdn.example.com/file"))
    }

    @Test
    fun sourceSwitchAbortsBeforeSignedDownload() {
        val switched = AtomicBoolean(false)
        withMirror(onLookup = {
            switched.set(true)
            it.reply(200, publicVersion)
        }) { mirror ->
            mirror.saveCdk("synthetic-key")
            val failure = runCatching {
                mirror.download(resource, "1.3.238", 3, checksum, target, shouldAbort = switched::get) { _, _ -> }
            }.exceptionOrNull()
            assertTrue(failure is DownloadAborted)
            assertFalse(target.exists())
        }
    }

    private val resource = MirrorChyanResource.UPDATE_ARM64
    private val target: File get() = File(folder.root, "update.part")
    private val body = "apk"
    private val checksum = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04"
    private val publicVersion = """{"code":0,"data":{"version_name":"1.3.238","release_note":"release"}}"""

    private fun signedPayload() = """{"code":0,"data":{"version_name":"1.3.238","update_type":"full",
        "url":"https://cdn.example.com/file?signature=synthetic","filesize":3,"sha256":"$checksum"}}"""

    private suspend fun download(mirror: MirrorChyan) =
        mirror.download(resource, "1.3.238", 3, checksum, target) { _, _ -> }

    private fun withMirror(
        onLookup: (Request) -> Response,
        onDownload: (Request) -> Response = { error("Unexpected download") },
        block: suspend (MirrorChyan) -> Unit,
    ) {
        runBlocking {
            val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            val store = object : DataStore<Preferences> {
                override val data = MutableStateFlow(emptyPreferences())
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val updated = transform(data.value)
                    return data.updateAndGet { updated }
                }
            }
            val apiClient = OkHttpClient.Builder().addInterceptor { onLookup(it.request()) }.build()
            val downloadClient = OkHttpClient.Builder().addInterceptor { onDownload(it.request()) }.build()
            val mirror = MirrorChyan(store, scope, apiClient, ParallelDownloader(downloadClient))
            try {
                block(mirror)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun Request.reply(code: Int, content: String): Response =
        Response.Builder().request(this).protocol(Protocol.HTTP_1_1).code(code)
            .message("Fixture").body(content.toResponseBody()).build()
}
