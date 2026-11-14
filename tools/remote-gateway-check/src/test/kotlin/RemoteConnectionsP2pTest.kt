import com.azurpilot.ghio.remoteaccess.Peer2PeerConnection
import com.azurpilot.ghio.remoteaccess.Peer2PeerConnectionFactory
import com.azurpilot.ghio.remoteaccess.RemoteConnections
import com.azurpilot.ghio.remoteaccess.TunnelFrame
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验证直连全失败后的 P2P 接管、失败回退和 30 秒入口排除。 / Verifies P2P takeover, fallback, and 30-second exclusion. */
class RemoteConnectionsP2pTest {
    private val id = "0123456789abcdef0123456789abcdef"
    private val entry get() = "https://registry.example/d/$id/"

    private class FakeConnection(override val localUrl: String) : Peer2PeerConnection {
        override fun send(frame: TunnelFrame): Boolean = true
        override fun close() = Unit
        override fun setFrameListener(listener: ((TunnelFrame) -> Unit)?) = Unit
        override fun setCloseListener(listener: (() -> Unit)?) = Unit
    }

    private class FakeFactory(
        private val result: Result<Peer2PeerConnection>,
    ) : Peer2PeerConnectionFactory {
        var calls = 0
        override suspend fun connect(entry: String, deviceId: String, stunHost: String): Peer2PeerConnection {
            calls++
            return result.getOrThrow()
        }
    }

    private fun client(
        p2p: Boolean,
        tunnel: String,
        directEndpoint: String = "http://1.1.1.1:25549",
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = when {
            request.url.encodedPath.endsWith("/resolve") -> JSONObject()
                .put("device_id", id).put("p2p", p2p).put("stun", "remote.example")
                .put("endpoints", JSONArray().put(JSONObject().put("url", directEndpoint)))
                .put("tunnel_url", tunnel).toString()
            request.url.encodedPath.endsWith("/fallback") -> "{}"
            request.url.encodedPath.endsWith("/healthz") -> JSONObject()
                .put("status", "ok").put("protocolVersion", 1).put("device_id", "wrong-device").toString()
            else -> "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
            .message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun p2pOpensLocalUrlWhenAllDirectEndpointsFail() = runBlocking {
        val factory = FakeFactory(Result.success(FakeConnection("http://127.0.0.1:32123")))
        val resolver = RemoteConnections(client(p2p = true, tunnel = ""), factory)
        assertEquals("http://127.0.0.1:32123/", resolver.resolve(entry, id))
        assertEquals(1, factory.calls)
    }

    @Test fun p2pFailureUsesCloudflareTunnel() = runBlocking {
        val factory = FakeFactory(Result.failure(IOException("ice failed")))
        val resolver = RemoteConnections(client(p2p = true, tunnel = "https://fallback.trycloudflare.com"), factory)
        assertEquals("https://fallback.trycloudflare.com/", resolver.resolve(entry, id))
        assertEquals(1, factory.calls)
    }

    @Test fun disabledP2pUsesCloudflareWithoutFactoryCall() = runBlocking {
        val factory = FakeFactory(Result.failure(AssertionError("must not be called")))
        val resolver = RemoteConnections(client(p2p = false, tunnel = "https://fallback.trycloudflare.com"), factory)
        assertEquals("https://fallback.trycloudflare.com/", resolver.resolve(entry, id))
        assertEquals(0, factory.calls)
    }

    @Test fun failedP2pEntryIsExcludedForThirtySeconds() = runBlocking {
        val factory = FakeFactory(Result.success(FakeConnection("http://127.0.0.1:32123")))
        val resolver = RemoteConnections(client(p2p = true, tunnel = "https://fallback.trycloudflare.com"), factory)
        assertEquals("http://127.0.0.1:32123/", resolver.resolve(entry, id))
        resolver.connectionFailed("http://127.0.0.1:32123/")
        assertEquals("https://fallback.trycloudflare.com/", resolver.resolve(entry, id))
        assertEquals(1, factory.calls)
    }
}
