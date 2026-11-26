package com.azurpilot.ghio.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.aliothmoon.preferences.PrefKey
import com.aliothmoon.preferences.PrefSchema
import com.azurpilot.download.DownloadCancelledException
import com.azurpilot.download.ParallelDownloader
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.provision.RuntimeArch
import com.azurpilot.ghio.update.MirrorChyanPreferencesSchema.flow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 官方分发资源与平台。通用 APK 不传 arch，Runtime 不传 os。
 *
 * Official distribution resource and platform. Universal APKs omit arch; Runtime omits os.
 */
enum class MirrorChyanResource(internal val rid: String, internal val os: String?, internal val arch: String?) {
    FULL_ARM64("AzurPilotAndroid", "android", "arm64"),
    FULL_X86_64("AzurPilotAndroid", "android", "x86_64"),
    UPDATE_ARM64("AzurPilotAndroidUpdate", "android", "arm64"),
    UPDATE_X86_64("AzurPilotAndroidUpdate", "android", "x86_64"),
    UPDATE_UNIVERSAL("AzurPilotAndroidUpdate", "android", null),
    RUNTIME_ARM64("AzurPilotAndroidRootfs", null, "arm64"),
    RUNTIME_X86_64("AzurPilotAndroidRootfs", null, "x86_64");

    internal companion object {
        fun updateApk(abi: String?) = when (abi) {
            RuntimeArch.ARM64 -> UPDATE_ARM64
            RuntimeArch.X86_64 -> UPDATE_X86_64
            else -> UPDATE_UNIVERSAL
        }

        fun runtime(abi: String) = if (abi == RuntimeArch.X86_64) RUNTIME_X86_64 else RUNTIME_ARM64
    }
}

/**
 * 面向界面的下载结果，不携带 CDK、签名链接或服务端错误文本。
 *
 * User-facing download outcome with no CDK, signed URL, or server error text.
 */
enum class MirrorChyanNotice {
    USED, NOT_SYNCED, UNAVAILABLE, FAILED, EXPIRED, INVALID, QUOTA, WRONG_RESOURCE, BLOCKED, TEST_KEY;

    internal companion object {
        fun fromCode(code: Int) = when (code) {
            7001 -> EXPIRED
            7002 -> INVALID
            7003 -> QUOTA
            7004 -> WRONG_RESOURCE
            7005 -> BLOCKED
            8001 -> UNAVAILABLE
            8009 -> TEST_KEY
            else -> FAILED
        }
    }
}

/** 设置页需要的两项状态。 / Settings state exposed to the UI. */
data class MirrorChyanSettings(val enabled: Boolean = false, val hasCdk: Boolean = false)

/** 仅用于免备份存储的偏好。 / Preferences for the no-backup store. */
@PrefSchema
class MirrorChyanPreferences(
    @PrefKey(default = "false") val enabled: Boolean = false,
    @PrefKey(default = "") val cdk: String = "",
)

/**
 * 用 Mirror 酱加速清单指定的完整包；回退和安装由调用方负责。
 *
 * Accelerates whole packages selected by the release manifest. Callers own fallback and install.
 */
class MirrorChyan internal constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val downloader: ParallelDownloader = ParallelDownloader(
        OkHttpClient.Builder()
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build(),
    ),
) {
    /** 创建独立的免备份设置。 / Creates a separate settings store excluded from backup. */
    constructor(context: Context, scope: CoroutineScope) : this(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { File(context.noBackupFilesDir, "mirrorchyan.preferences_pb") },
        scope,
    )

    val settings = store.flow.map { MirrorChyanSettings(it.enabled, it.cdk.isNotEmpty()) }
        .stateIn(scope, SharingStarted.Eagerly, MirrorChyanSettings())
    private val _notice = MutableStateFlow<MirrorChyanNotice?>(null)
    val notice = _notice.asStateFlow()

    // 检查版本只缓存公开元数据；CDK 请求和签名链接仅在下载时使用。
    private val publicVersions = ConcurrentHashMap<MirrorChyanResource, PublicVersion>()

    /** 独立启停加速。 / Toggles acceleration independently of the fallback source. */
    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[MirrorChyanPreferencesSchema.enabled] = enabled }
        _notice.value = null
    }

    /** 保存新密钥并启用加速；空值删除密钥。 / Saves a key and enables acceleration, or removes it. */
    suspend fun saveCdk(cdk: String) {
        store.edit {
            if (cdk.isBlank()) {
                it.remove(MirrorChyanPreferencesSchema.cdk)
            } else {
                it[MirrorChyanPreferencesSchema.cdk] = cdk.trim()
                it[MirrorChyanPreferencesSchema.enabled] = true
            }
        }
        _notice.value = null
    }

    /** 异步检查官方资源的公开版本。 / Checks the public version of the official resource asynchronously. */
    internal fun check(resource: MirrorChyanResource) {
        scope.launch(AppDispatchers.IO) {
            if (store.flow.first().enabled) publicVersion(resource)
        }
    }

    /**
     * 获取临时链接，确认元数据并验证落盘文件。返回 false 时可从现有源重新下载。
     *
     * Obtains a short-lived URL, checks metadata, and verifies the downloaded file. False allows
     * the caller to retry from its existing source.
     */
    internal suspend fun download(
        resource: MirrorChyanResource,
        version: String,
        size: Long,
        sha256: String,
        target: File,
        shouldAbort: () -> Boolean = { false },
        onVerify: () -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val config = store.flow.first()
        if (!config.enabled || config.cdk.isEmpty()) return false
        val jobContext = currentCoroutineContext()
        fun checkAbort() {
            jobContext.ensureActive()
            if (shouldAbort()) throw DownloadAborted()
        }
        checkAbort()

        val latest = publicVersion(resource)
        checkAbort()
        if (latest.code != 0) return failed(MirrorChyanNotice.fromCode(latest.code))
        if (!resource.matchesVersion(latest.version, version)) return failed(MirrorChyanNotice.NOT_SYNCED)

        val result = request(resource, config.cdk)
        checkAbort()
        if (result.code != 0) return failed(MirrorChyanNotice.fromCode(result.code))
        val url = result.data?.matchingUrl(resource, version, size, sha256)
            ?: return failed(MirrorChyanNotice.NOT_SYNCED)

        try {
            downloader.download(
                url.toString(), target, size,
                shouldAbort = { shouldAbort() || !jobContext.isActive },
                onProgress = onProgress,
            )
            onVerify()
            val matches = target.length() == size && sha256Hex(target).equals(sha256, ignoreCase = true)
            checkAbort()
            if (matches) {
                _notice.value = MirrorChyanNotice.USED
                return true
            }
        } catch (_: DownloadCancelledException) {
            jobContext.ensureActive()
            throw DownloadAborted()
        } catch (_: IOException) {
            checkAbort()
        }
        target.delete()
        return failed(MirrorChyanNotice.FAILED)
    }

    private fun failed(notice: MirrorChyanNotice): Boolean {
        _notice.value = notice
        return false
    }

    private fun publicVersion(resource: MirrorChyanResource): PublicVersion {
        val now = System.nanoTime()
        publicVersions[resource]?.takeIf { now - it.fetchedAt < PUBLIC_VERSION_TTL_NS }?.let { return it }
        val result = request(resource)
        return PublicVersion(result.code, result.data?.version, now).also {
            if (it.code == 0) publicVersions[resource] = it
        }
    }

    private fun request(resource: MirrorChyanResource, cdk: String? = null): MirrorChyanResponse {
        // 异常与响应可能带有密钥或签名链接，仅把结构化状态传给界面。
        return try {
            val request = Request.Builder().url(mirrorChyanLookupUrl(resource, cdk)).build()
            apiClient.newCall(request).execute().use { response ->
                val result = json.decodeFromString<MirrorChyanResponse>(response.peekBody(256 * 1024L).string())
                if (response.isSuccessful || result.code != 0) result else MirrorChyanResponse(-1)
            }
        } catch (_: IOException) {
            MirrorChyanResponse(-1)
        } catch (_: SerializationException) {
            MirrorChyanResponse(-1)
        }
    }

    private data class PublicVersion(val code: Int, val version: String?, val fetchedAt: Long)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val PUBLIC_VERSION_TTL_NS = TimeUnit.MINUTES.toNanos(1)
    }
}

/** 构造官方查询，完整包使用空版本号。 / Builds the official lookup with an empty version for whole packages. */
internal fun mirrorChyanLookupUrl(resource: MirrorChyanResource, cdk: String? = null): HttpUrl =
    "https://mirrorchyan.com".toHttpUrl().newBuilder().apply {
        addPathSegments("api/resources/${resource.rid}/latest")
        addQueryParameter("current_version", "")
        addQueryParameter("channel", "stable")
        addQueryParameter("user_agent", "AzurPilotAndroid")
        resource.os?.let { addQueryParameter("os", it) }
        resource.arch?.let { addQueryParameter("arch", it) }
        cdk?.let { addQueryParameter("cdk", it) }
    }.build()

/** 镜像版本仅用于匹配清单。 / A mirror version is used only to match the manifest. */
private fun MirrorChyanResource.matchesVersion(candidate: String?, expected: String): Boolean =
    if (os == "android") candidate?.removePrefix("v") == expected.removePrefix("v") else candidate == expected

/** 只解码下载需要的字段。 / Decodes only fields needed for downloading. */
@Serializable
internal class MirrorChyanResponse(val code: Int, val data: MirrorChyanPayload? = null)

/** 镜像声明的完整包元数据。 / Whole-package metadata reported by the mirror. */
@Serializable
internal class MirrorChyanPayload(
    @SerialName("version_name") val version: String? = null,
    private val url: String? = null,
    private val sha256: String? = null,
    private val filesize: Long? = null,
    @SerialName("update_type") private val updateType: String? = null,
) {
    /** 只接受和可信清单完全对应的 HTTPS 完整包。 / Accepts only the manifest's exact complete HTTPS package. */
    fun matchingUrl(resource: MirrorChyanResource, version: String, size: Long, checksum: String): HttpUrl? =
        url?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
                updateType == "full" && resource.matchesVersion(this.version, version) &&
                filesize == size && sha256.equals(checksum, ignoreCase = true)
        }
}
