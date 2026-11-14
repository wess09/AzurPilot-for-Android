package com.azurpilot.ghio.remoteaccess

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.Timer
import java.util.TimerTask

/**
 * 把本机 HTTP/WS 请求映射到 P2P 帧；只监听 127.0.0.1 随机端口。
 *
 * Maps local HTTP/WS requests to P2P frames; listens only on a random 127.0.0.1 port.
 */
class LocalTunnelServer(
    private val connection: Peer2PeerConnection,
    private val pingIntervalMs: Long = 15_000,
) : NanoWSD("127.0.0.1", 0) {
    private val nextStreamId = AtomicInteger(1)
    private val httpStreams = ConcurrentHashMap<Int, HttpExchange>()
    private val webSockets = ConcurrentHashMap<Int, TunnelWebSocket>()
    private val wsMessages = ConcurrentHashMap<Int, WsMessage>()
    private var keepAlive: Timer? = null

    /** 本地网关入口；始终是 http://127.0.0.1:<随机端口>/。 / Local gateway entry with a random port. */
    val url: String get() = "http://127.0.0.1:$listeningPort/"

    /** 启动本地服务并安装远端帧回调。 / Starts the local service and installs the remote-frame callback. */
    fun startTunnel() {
        require(pingIntervalMs > 0)
        connection.setFrameListener(::handleFrame)
        connection.setCloseListener { stop() }
        keepAlive = Timer("azurpilot-p2p-ping", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val sent = runCatching {
                        connection.send(TunnelFrame(TunnelFrameType.PING, 0, ByteArray(0)))
                    }.getOrDefault(false)
                    if (!sent) stop()
                }
            }, pingIntervalMs, pingIntervalMs)
        }
        try {
            start(30_000, false)
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    override fun stop() {
        keepAlive?.cancel()
        keepAlive = null
        super.stop()
    }

    override fun openWebSocket(session: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        val streamId = allocateStreamId()
        val socket = TunnelWebSocket(session, streamId)
        webSockets[streamId] = socket
        val headers = JSONObject()
        session.headers.forEach { (key, value) -> headers.put(key, value) }
        sendOrClose(streamId, TunnelFrame(TunnelFrameType.WS_OPEN, streamId,
            JSONObject().put("p", requestPath(session)).put("h", headers).toString()
                .toByteArray(StandardCharsets.UTF_8)))
        return socket
    }

    override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val streamId = allocateStreamId()
        val result = CompletableDeferred<RemoteHttp>()
        val exchange = HttpExchange(result)
        httpStreams[streamId] = exchange
        val headers = JSONObject()
        session.headers.forEach { (key, value) -> headers.put(key, value) }
        val body = readBody(session)
        val open = JSONObject().put("m", session.method.name).put("p", requestPath(session))
            .put("h", headers).toString().toByteArray(StandardCharsets.UTF_8)
        if (!sendOrClose(streamId, TunnelFrame(TunnelFrameType.HTTP_OPEN, streamId, open))) return unavailable()
        tunnelDataFrames(streamId, body).forEach {
            if (!sendOrClose(streamId, it)) return unavailable()
        }
        if (!sendOrClose(streamId, TunnelFrame(TunnelFrameType.DONE, streamId, ByteArray(0)))) return unavailable()
        return try {
            val remote = kotlinx.coroutines.runBlocking { result.await() }
            responseFrom(remote)
        } catch (_: Exception) {
            unavailable()
        }
    }

    private fun handleFrame(frame: TunnelFrame) {
        when (frame.type) {
            TunnelFrameType.HTTP_HEAD -> handleHttpHead(frame)
            TunnelFrameType.DATA -> handleData(frame)
            TunnelFrameType.DONE -> handleDone(frame)
            TunnelFrameType.WS_TEXT, TunnelFrameType.WS_BIN -> {
                val message = WsMessage(frame.type).apply { body.write(frame.payload) }
                if (wsMessages.putIfAbsent(frame.streamId, message) != null) closeStream(frame.streamId)
            }
            TunnelFrameType.WS_END -> handleWsEnd(frame)
            TunnelFrameType.WS_CLOSE -> closeStream(frame.streamId)
            TunnelFrameType.PING -> sendOrClose(frame.streamId,
                TunnelFrame(TunnelFrameType.PONG, frame.streamId, ByteArray(0)))
            TunnelFrameType.PONG, TunnelFrameType.HTTP_OPEN, TunnelFrameType.WS_OPEN -> Unit
            else -> closeStream(frame.streamId)
        }
    }

    private fun handleHttpHead(frame: TunnelFrame) {
        val exchange = httpStreams[frame.streamId] ?: return closeStream(frame.streamId)
        val json = runCatching { JSONObject(frame.payload.toString(StandardCharsets.UTF_8)) }.getOrNull()
            ?: return closeStream(frame.streamId)
        val headers = LinkedHashMap<String, String>()
        json.optJSONObject("h")?.keys()?.forEach { key ->
            val value = json.optJSONObject("h")?.optString(key).orEmpty()
            if (!key.equals("connection", true) && !key.equals("transfer-encoding", true)) headers[key] = value
        }
        exchange.head = RemoteHead(json.optInt("s", 502), headers)
    }

    private fun handleData(frame: TunnelFrame) {
        val http = httpStreams[frame.streamId]
        if (http != null) {
            if (http.head == null) return closeStream(frame.streamId)
            http.body.write(frame.payload)
            return
        }
        val message = wsMessages[frame.streamId] ?: return closeStream(frame.streamId)
        message.body.write(frame.payload)
    }

    private fun handleDone(frame: TunnelFrame) {
        val exchange = httpStreams.remove(frame.streamId) ?: return closeStream(frame.streamId)
        val head = exchange.head
        if (head == null) {
            exchange.result.completeExceptionally(IOException("HTTP_HEAD missing"))
            return
        }
        exchange.result.complete(RemoteHttp(head.status, head.headers, exchange.body.toByteArray()))
    }

    private fun handleWsEnd(frame: TunnelFrame) {
        val message = wsMessages.remove(frame.streamId) ?: return closeStream(frame.streamId)
        val socket = webSockets[frame.streamId] ?: return closeStream(frame.streamId)
        runCatching {
            if (message.type == TunnelFrameType.WS_TEXT) socket.send(message.body.toByteArray().toString(StandardCharsets.UTF_8))
            else socket.send(message.body.toByteArray())
        }.onFailure { closeStream(frame.streamId) }
    }

    private fun closeStream(streamId: Int) {
        httpStreams.remove(streamId)?.result?.completeExceptionally(IOException("invalid tunnel stream"))
        wsMessages.remove(streamId)
        webSockets.remove(streamId)?.closeQuietly(false)
    }

    private fun sendOrClose(streamId: Int, frame: TunnelFrame): Boolean {
        val sent = runCatching { connection.send(frame) }.getOrDefault(false)
        if (!sent) closeStream(streamId)
        return sent
    }

    private fun allocateStreamId(): Int {
        while (true) {
            val value = nextStreamId.getAndIncrement() and 0xffff
            if (value != 0) return value
        }
    }

    private fun requestPath(session: NanoHTTPD.IHTTPSession): String {
        val query = session.queryParameterString
        return session.uri + if (query.isNullOrEmpty()) "" else "?$query"
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): ByteArray {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (length <= 0L) return ByteArray(0)
        val body = ByteArrayOutputStream(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val input = session.inputStream
        val buffer = ByteArray(8_192)
        var remaining = length
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("request body truncated")
            body.write(buffer, 0, read)
            remaining -= read
        }
        return body.toByteArray()
    }

    private fun responseFrom(remote: RemoteHttp): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(
            TunnelStatus(remote.status),
            remote.headers["content-type"] ?: NanoHTTPD.MIME_PLAINTEXT,
            remote.body.inputStream(),
            remote.body.size.toLong(),
        )
        remote.headers.forEach { (key, value) ->
            if (!key.equals("content-type", true) && !key.equals("content-length", true)) response.addHeader(key, value)
        }
        return response
    }

    private fun unavailable(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(TunnelStatus(502), NanoHTTPD.MIME_PLAINTEXT, "")

    private data class RemoteHead(val status: Int, val headers: Map<String, String>)
    private data class RemoteHttp(val status: Int, val headers: Map<String, String>, val body: ByteArray)
    private class HttpExchange(val result: CompletableDeferred<RemoteHttp>) {
        var head: RemoteHead? = null
        val body = ByteArrayOutputStream()
    }
    private class WsMessage(val type: Int) {
        val body = ByteArrayOutputStream()
    }

    private inner class TunnelWebSocket(session: NanoHTTPD.IHTTPSession, private val streamId: Int) :
        NanoWSD.WebSocket(session) {
        override fun onOpen() = Unit

        override fun onMessage(frame: NanoWSD.WebSocketFrame) {
            when (frame.opCode) {
                NanoWSD.WebSocketFrame.OpCode.Text -> sendWs(TunnelFrameType.WS_TEXT,
                    frame.textPayload.toByteArray(StandardCharsets.UTF_8))
                NanoWSD.WebSocketFrame.OpCode.Binary -> sendWs(TunnelFrameType.WS_BIN, frame.binaryPayload)
                NanoWSD.WebSocketFrame.OpCode.Close -> closeQuietly(true)
                NanoWSD.WebSocketFrame.OpCode.Ping -> runCatching {
                    sendFrame(NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Pong, true, frame.binaryPayload))
                }
                NanoWSD.WebSocketFrame.OpCode.Pong, NanoWSD.WebSocketFrame.OpCode.Continuation -> Unit
            }
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            webSockets.remove(streamId)
            wsMessages.remove(streamId)
            if (initiatedByRemote) sendOrClose(streamId, TunnelFrame(TunnelFrameType.WS_CLOSE, streamId, ByteArray(0)))
        }

        override fun onPong(frame: NanoWSD.WebSocketFrame) = Unit
        override fun onException(exception: IOException) = closeQuietly(false)

        private fun sendWs(type: Int, payload: ByteArray) {
            tunnelWsMessageFrames(type, streamId, payload).forEach {
                if (!sendOrClose(streamId, it)) closeQuietly(false)
            }
        }
    }

    private fun NanoWSD.WebSocket.closeQuietly(initiatedByRemote: Boolean) {
        runCatching { close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "", initiatedByRemote) }
    }

    private class TunnelStatus(private val code: Int) : NanoHTTPD.Response.IStatus {
        override fun getDescription(): String = "$code " + when (code) {
            200 -> "OK"
            201 -> "Created"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "Status"
        }
        override fun getRequestStatus(): Int = code
    }
}
