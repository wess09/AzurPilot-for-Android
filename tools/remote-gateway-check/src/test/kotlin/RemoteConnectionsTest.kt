import com.azurpilot.ghio.remoteaccess.RemoteConnections
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 验证生产选路模块的公网限制、设备身份检查与 Cloudflare 回退。
 *
 * Verifies public address filtering, device checks, and fallback in the production resolver.
 */
class RemoteConnectionsTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val entry get() = "https://registry.example/d/$id/"

    private fun client(handler: (okhttp3.Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            assertEquals(null, chain.request().header("Authorization"))
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun rejectsPrivateAddressesAndCredentialURLs() {
        val resolver = RemoteConnections(OkHttpClient())
        for (value in listOf("http://127.0.0.1:25549", "http://192.168.1.1:25549",
            "http://100.64.1.1:25549", "http://[fc00::1]:25549", "http://[::1]:25549",
            "http://example.com:25549", "http://user@1.1.1.1:25549", "http://1.1.1.1:25549/path"))
            assertFalse(resolver.isDirect(value), value)
        assertTrue(resolver.isDirect("http://1.1.1.1:25549"))
        assertTrue(resolver.isDirect("http://[2606:4700:4700::1111]:25549"))
    }

    @Test fun verifiesDeviceAndExcludesFailedRoutes() = runBlocking {
        var healthCalls = 0
        val resolver = RemoteConnections(client { request ->
            if (request.url.encodedPath == "/healthz") {
                healthCalls++
                200 to JSONObject().put("status", "ok").put("protocolVersion", 1).put("device_id", id).toString()
            } else 200 to JSONObject().put("device_id", id).put("endpoints",
                JSONArray().put(JSONObject().put("url", "http://1.1.1.1:25549")))
                .put("tunnel_url", "https://fallback.trycloudflare.com").toString()
        })
        assertEquals("http://1.1.1.1:25549/", resolver.resolve(entry, id))
        resolver.connectionFailed("http://1.1.1.1:25549/")
        assertEquals("https://fallback.trycloudflare.com/", resolver.resolve(entry, id))
        assertEquals(1, healthCalls)
    }

    @Test fun wrongDeviceFallsBackAndRequestsTunnelOnDemand() = runBlocking {
        var requested = false
        val resolver = RemoteConnections(client { request ->
            when {
                request.url.encodedPath.endsWith("/fallback") -> {
                    assertEquals("POST", request.method)
                    requested = true
                    200 to "{}"
                }
                request.url.encodedPath == "/healthz" -> 200 to
                    """{"status":"ok","protocolVersion":1,"device_id":"wrong-device"}"""
                else -> 200 to JSONObject().put("device_id", id).put("endpoints",
                    JSONArray().put(JSONObject().put("url", "http://1.1.1.1:25549")))
                    .put("tunnel_url", if (requested) "https://fallback.trycloudflare.com" else "").toString()
            }
        })
        assertEquals("https://fallback.trycloudflare.com/", resolver.resolve(entry, id))
        assertTrue(requested)
    }

    @Test fun usesNewDirectRouteWhileCloudflareIsStillStarting() = runBlocking {
        var requested = false
        val resolver = RemoteConnections(client { request ->
            when {
                request.url.encodedPath.endsWith("/fallback") -> {
                    requested = true
                    200 to "{}"
                }
                request.url.encodedPath == "/healthz" -> 200 to
                    JSONObject().put("status", "ok").put("protocolVersion", 1).put("device_id", id).toString()
                else -> 200 to JSONObject().put("device_id", id).put("tunnel_url", "")
                    .put("endpoints", JSONArray().apply {
                        if (requested) put(JSONObject().put("url", "http://1.1.1.1:25549"))
                    }).toString()
            }
        })
        assertEquals("http://1.1.1.1:25549/", resolver.resolve(entry, id))
        assertTrue(requested)
    }
}
