import com.azurpilot.ghio.remoteaccess.LocalTunnelServer
import com.azurpilot.ghio.remoteaccess.Peer2PeerConnection
import com.azurpilot.ghio.remoteaccess.TunnelFrame
import com.azurpilot.ghio.remoteaccess.TunnelFrameType
import com.azurpilot.ghio.remoteaccess.tunnelDataFrames
import com.azurpilot.ghio.remoteaccess.tunnelWsMessageFrames
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用内存 fake DataChannel 验证本地 HTTP/WS 透传与 WS 大消息重组。 / Verifies local HTTP/WS forwarding and large WS reassembly via a fake DataChannel. */
class LocalTunnelServerTest {
    @Test fun sendsProtocolPingKeepalive() {
        val ping = CountDownLatch(1)
        val connection = LoopbackConnection(onPing = { ping.countDown() })
        val server = LocalTunnelServer(connection, pingIntervalMs = 10).also { it.startTunnel() }
        try {
            assertTrue(ping.await(2, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }

    @Test fun forwardsHttpAndFiltersTransportHeaders() {
        val connection = LoopbackConnection()
        val server = LocalTunnelServer(connection).also { it.startTunnel() }
        try {
            val response = OkHttpClient().newCall(
                Request.Builder().url(server.url + "echo").post("hello".toRequestBody()).build(),
            ).execute()
            response.use {
                assertEquals(201, it.code)
                assertEquals("hello", it.body.string())
                assertEquals("ok", it.header("x-remote"))
                assertNull(it.header("transfer-encoding"))
            }
        } finally {
            server.stop()
        }
    }

    @Test fun forwardsWebSocketTextAndLargeBinaryMessages() {
        val connection = LoopbackConnection()
        val server = LocalTunnelServer(connection).also { it.startTunnel() }
        val largeMessage = ByteArray(60_000) { 'x'.code.toByte() }
        val textReceived = AtomicReference<String>()
        val binaryReceived = AtomicReference<ByteArray>()
        val binaryLatch = CountDownLatch(1)
        val socket = OkHttpClient().newWebSocket(
            Request.Builder().url(server.url.replaceFirst("http://", "ws://") + "api/v1/ws").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("hello")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    textReceived.set(text)
                    webSocket.send(largeMessage.toByteString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    binaryReceived.set(bytes.toByteArray())
                    binaryLatch.countDown()
                }
            },
        )
        try {
            assertTrue(binaryLatch.await(10, TimeUnit.SECONDS))
            assertEquals("hello", textReceived.get())
            assertTrue(largeMessage.contentEquals(binaryReceived.get()))
            assertEquals(listOf(5, largeMessage.size), connection.messageLengths)
        } finally {
            socket.close(1000, null)
            server.stop()
        }
    }

    private class LoopbackConnection(private val onPing: (() -> Unit)? = null) : Peer2PeerConnection {
        override val localUrl: String = "memory://loopback"
        private val remote = RemoteHandler()
        private var listener: ((TunnelFrame) -> Unit)? = null
        val messageLengths = mutableListOf<Int>()

        override fun send(frame: TunnelFrame): Boolean {
            if (frame.type == TunnelFrameType.PING) onPing?.invoke()
            remote.handle(frame)
            return true
        }

        override fun close() = Unit
        override fun setFrameListener(listener: ((TunnelFrame) -> Unit)?) {
            this.listener = listener
        }

        override fun setCloseListener(listener: (() -> Unit)?) = Unit

        private fun deliver(frame: TunnelFrame) {
            listener?.invoke(frame)
        }

        private inner class RemoteHandler {
            private val httpBodies = HashMap<Int, ByteArrayOutputStream>()
            private val wsMessages = HashMap<Int, Pair<Int, ByteArrayOutputStream>>()

            fun handle(frame: TunnelFrame) {
                when (frame.type) {
                    TunnelFrameType.HTTP_OPEN -> httpBodies[frame.streamId] = ByteArrayOutputStream()
                    TunnelFrameType.DONE -> {
                        val body = httpBodies.remove(frame.streamId)?.toByteArray() ?: ByteArray(0)
                        deliver(TunnelFrame(
                            TunnelFrameType.HTTP_HEAD,
                            frame.streamId,
                            JSONObject().put("s", 201).put("h", JSONObject()
                                .put("x-remote", "ok").put("connection", "keep-alive")
                                .put("transfer-encoding", "chunked")).toString().toByteArray(),
                        ))
                        tunnelDataFrames(frame.streamId, body).forEach { deliver(it) }
                        deliver(TunnelFrame(TunnelFrameType.DONE, frame.streamId, ByteArray(0)))
                    }
                    TunnelFrameType.WS_TEXT, TunnelFrameType.WS_BIN ->
                        wsMessages[frame.streamId] = frame.type to
                            ByteArrayOutputStream().apply { write(frame.payload) }
                    TunnelFrameType.DATA -> {
                        val httpBody = httpBodies[frame.streamId]
                        if (httpBody != null) httpBody.write(frame.payload)
                        else wsMessages[frame.streamId]?.second?.write(frame.payload)
                    }
                    TunnelFrameType.WS_END -> {
                        val (type, body) = wsMessages.remove(frame.streamId) ?: return
                        val payload = body.toByteArray()
                        messageLengths += payload.size
                        tunnelWsMessageFrames(type, frame.streamId, payload).forEach { deliver(it) }
                    }
                    TunnelFrameType.WS_CLOSE ->
                        deliver(TunnelFrame(TunnelFrameType.WS_CLOSE, frame.streamId, ByteArray(0)))
                    else -> Unit
                }
            }
        }
    }
}
