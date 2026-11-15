package com.azurpilot.ghio.remoteaccess

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 在真实 Android 设备验证公开入口和原生打洞后的 HTTP、鉴权及 WebSocket 心跳。
 *
 * Verifies public entry resolution and native P2P HTTP, authentication, and WebSocket heartbeats.
 */
@RunWith(AndroidJUnit4::class)
class LiveP2pInstrumentedTest {
    @Test fun registeredEntryWebSocketTimings() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceId = requireNotNull(InstrumentationRegistry.getArguments().getString("deviceId"))
        val store = RemotePairings(context)
        val pairing = store.list().first { it.deviceId == deviceId }
        val results = File(context.filesDir, "live-entry-ws.txt")
        results.writeText("started\n")
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
        try {
            val started = System.nanoTime()
            val base = store.resolve(pairing)
            results.appendText("resolve_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}\n")
            verifyGateway(client, base, pairing, results)
            results.appendText("authenticated_heartbeats_ok\n")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun easyTierWebSocketTimings() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val deviceId = requireNotNull(arguments.getString("deviceId"))
        val meshUrl = requireNotNull(arguments.getString("meshUrl"))
        val socksPort = arguments.getString("socksPort")?.toInt() ?: 25888
        val pairing = RemotePairings(context).list().first { it.deviceId == deviceId }
        val results = File(context.filesDir, "live-easytier-ws.txt")
        results.writeText("started\n")
        val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS,
            InetSocketAddress("127.0.0.1", socksPort))).connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
        try {
            verifyGateway(client, meshUrl, pairing, results)
            results.appendText("authenticated_heartbeats_ok\n")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun realDeviceConnectsToRegisteredAp() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val deviceId = requireNotNull(arguments.getString("deviceId"))
        val store = RemotePairings(context)
        val code = arguments.getString("pairingCode").orEmpty()
        val pairing = if (code.isNotEmpty()) store.pair(code)
            else store.list().first { it.deviceId == deviceId }
        assertEquals(deviceId, pairing.deviceId)
        val results = File(context.filesDir, "live-p2p-result.txt")
        results.writeText("started\n")
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
        val factory = WebRtcPeer2PeerConnectionFactory(context, Peer2PeerSignaling(client))
        if (arguments.getString("p2pOnly") != "true") {
            val resolved = RemoteConnections(client, factory).resolve(pairing.url, deviceId)
            verifyGateway(client, resolved, pairing)
            results.appendText("normal_entry_http_ws_authenticated_ok\n")
        }
        val api = pairing.url.substringBefore("/d/") + "/api/v1/devices/$deviceId/resolve"
        val configuration = client.newCall(Request.Builder().url(api).build()).execute().use {
            assertTrue(it.isSuccessful)
            JSONObject(it.body.string())
        }
        val started = System.nanoTime()
        val connection = withTimeout(75_000) {
            factory.connect(pairing.url, deviceId, configuration.optString("stun"))
        }
        try {
            results.appendText("native_p2p_open_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}\n")
            verifyGateway(client, connection.localUrl, pairing)
            results.appendText("p2p_http_ws_authenticated_ok\n")
            val field = connection.javaClass.getDeclaredField("peer").apply { isAccessible = true }
            val peer = field.get(connection) as PeerConnection
            val collected = CompletableDeferred<String>()
            peer.getStats { report ->
                val pair = report.statsMap.values.firstOrNull {
                    it.type == "candidate-pair" && it.members["state"] == "succeeded" &&
                        it.members["nominated"] == true
                }
                val local = report.statsMap[pair?.members?.get("localCandidateId")]
                val remote = report.statsMap[pair?.members?.get("remoteCandidateId")]
                collected.complete("local=${local?.members?.get("candidateType")},remote=${remote?.members?.get("candidateType")},protocol=${local?.members?.get("protocol")},bytesSent=${pair?.members?.get("bytesSent")},bytesReceived=${pair?.members?.get("bytesReceived")}")
            }
            results.appendText(withTimeout(5_000) { collected.await() } + "\n")
        } finally {
            connection.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun verifyGateway(client: OkHttpClient, base: String, pairing: RemotePairing,
                                      timings: File? = null) {
        val healthStarted = System.nanoTime()
        client.newCall(Request.Builder().url(base.trimEnd('/') + "/healthz").build()).execute().use {
            assertTrue(it.isSuccessful)
            assertEquals(pairing.deviceId, JSONObject(it.body.string()).optString("device_id"))
        }
        timings?.appendText("health_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - healthStarted)}\n")
        val messages = Channel<JSONObject>(Channel.UNLIMITED)
        val url = base.trimEnd('/').replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + "/api/v1/ws"
        val socketStarted = System.nanoTime()
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.trySend(JSONObject(text))
            }

            override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
                messages.close(failure)
            }
        })
        try {
            withTimeout(15_000) {
                while (messages.receive().optString("topic") != "session") Unit
            }
            timings?.appendText("ws_session_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - socketStarted)}\n")
            val login = JSONObject().put("password", pairing.accessToken)
            val loginStarted = System.nanoTime()
            rpc(socket, messages, "auth.login", login, "login")
            timings?.appendText("login_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loginStarted)}\n")
            repeat(4) { index ->
                val pingStarted = System.nanoTime()
                val reply = rpc(socket, messages, "system.ping", JSONObject(), "ping-$index")
                assertTrue(reply.getJSONObject("result").getBoolean("pong"))
                timings?.appendText("ping_${index}_ms=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pingStarted)}\n")
                if (index < 3) delay(6_000)
            }
        } finally {
            socket.close(1000, "test complete")
            messages.close()
        }
    }

    private suspend fun rpc(socket: WebSocket, messages: Channel<JSONObject>, method: String,
                            params: JSONObject, id: String): JSONObject = withTimeout(15_000) {
        assertTrue(socket.send(JSONObject().put("v", 1).put("type", "request")
            .put("id", id).put("method", method).put("params", params).toString()))
        var reply = messages.receive()
        while (reply.optString("id") != id) reply = messages.receive()
        assertTrue(reply.toString(), reply.getBoolean("ok"))
        reply
    }
}
