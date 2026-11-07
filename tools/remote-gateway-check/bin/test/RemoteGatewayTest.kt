import com.azurpilot.ghio.proot.AzurPilotGateway
import com.azurpilot.ghio.proot.RuntimeConnection
import com.azurpilot.ghio.remoteaccess.RemotePairing
import com.azurpilot.ghio.remoteaccess.RemotePairings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 验证生产原生网关的配对鉴权、来源切换及固定入口重新解析。
 *
 * Verifies pairing authentication, source switching, and stable entry resolution in the production gateway.
 */
class RemoteGatewayTest {
    private class Peer(val requireAuth: Boolean, val authGate: CountDownLatch = CountDownLatch(0)) : WebSocketListener() {
        val messages = CopyOnWriteArrayList<JSONObject>()
        @Volatile var ws: WebSocket? = null
        override fun onOpen(webSocket: WebSocket, response: Response) {
            ws = webSocket
            webSocket.send(JSONObject().put("v",1).put("type","event").put("topic","session")
                .put("data",JSONObject().put("authRequired",requireAuth)).toString())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val request = JSONObject(text); messages.add(request)
            val result = JSONObject().put("pong",true)
            if (request.getString("method") == "auth.login") {
                assertEquals("synthetic-pair-token",request.getJSONObject("params").getString("password"))
                check(authGate.await(5,TimeUnit.SECONDS))
                result.put("authenticated",true)
            }
            webSocket.send(JSONObject().put("v",1).put("type","response").put("id",request.getString("id"))
                .put("ok",true).put("result",result).toString())
        }
    }

    @Test fun explicitLocalLoginAlsoPublishesReadiness() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(Peer(true)).build())
        server.start()
        RuntimeConnection.endpoint = RuntimeConnection.Endpoint(server.url("/").toString())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = AzurPilotGateway(scope)
        try {
            gateway.start()
            withTimeout(5000) { gateway.authRequired.first { it } }
            assertFalse(gateway.connected.value)
            assertIs<AzurPilotGateway.Reply.Ok>(gateway.login("synthetic-pair-token"))
            assertTrue(gateway.connected.value)
            assertFalse(gateway.authRequired.value)
        } finally {
            gateway.stop(); scope.cancel(); server.close()
        }
    }

    @Test fun nativeConnectionAuthenticatesBeforeReadyAndResolvesAgain() = runBlocking {
        val local = MockWebServer(); val first = MockWebServer(); val second = MockWebServer()
        val localPeer = Peer(false); val remotePeer = Peer(true, CountDownLatch(1)); val replacementPeer = Peer(true)
        local.enqueue(MockResponse.Builder().webSocketUpgrade(localPeer).build())
        local.enqueue(MockResponse.Builder().webSocketUpgrade(Peer(false)).build())
        first.enqueue(MockResponse.Builder().webSocketUpgrade(remotePeer).build())
        second.enqueue(MockResponse.Builder().webSocketUpgrade(replacementPeer).build())
        local.start(); first.start(); second.start()
        val localEndpoint = RuntimeConnection.Endpoint(local.url("/").toString())
        RuntimeConnection.endpoint = localEndpoint
        val resolved = AtomicInteger(0)
        val store = RemotePairings { if (resolved.incrementAndGet() == 1) first.url("/").toString() else second.url("/").toString() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gateway = AzurPilotGateway(scope,store)
        try {
            gateway.start()
            withTimeout(5000) { gateway.connected.first { it } }
            gateway.selectTarget(RemotePairing("device","Remote AP","https://fixed.example/","synthetic-pair-token"))
            withTimeout(5000) { while (remotePeer.messages.isEmpty()) delay(10) }
            assertFalse(gateway.connected.value)
            remotePeer.authGate.countDown()
            withTimeout(5000) { gateway.connected.first { it } }
            assertEquals("auth.login",remotePeer.messages.first().getString("method"))
            assertIs<AzurPilotGateway.Reply.Ok>(gateway.call("system.ping"))
            assertEquals(localEndpoint,RuntimeConnection.endpoint)
            assertTrue(localPeer.messages.isEmpty())
            remotePeer.ws!!.close(1000,null)
            withTimeout(8000) { while (replacementPeer.messages.none { it.getString("method")=="auth.login" }) delay(20) }
            withTimeout(5000) { gateway.connected.first { it } }
            assertTrue(resolved.get() >= 2)
            assertIs<AzurPilotGateway.Reply.Ok>(gateway.call("system.ping"))
            gateway.selectTarget(null)
            withTimeout(5000) { gateway.connected.first { it } }
            assertFalse(gateway.isRemote)
            assertEquals("local",gateway.sourceId.value)
        } finally {
            gateway.stop(); scope.cancel(); local.close(); first.close(); second.close()
        }
    }
}
