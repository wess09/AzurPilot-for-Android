package com.azurpilot.ghio.remoteaccess

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * 注册服务上的 SDP 信令客户端；不携带任何设备凭据。
 *
 * SDP signaling client on the registry; it carries no device credentials.
 */
class Peer2PeerSignaling(private val client: OkHttpClient) {

    /** 发布 offer 并返回信令 ID。 / Publishes an offer and returns the signal id. */
    suspend fun offer(entry: String, deviceId: String, sdp: String): String {
        val api = signalUrl(entry, deviceId)
        val body = JSONObject().put("offer", sdp).toString()
            .toRequestBody("application/json".toMediaType())
        return client.newCall(Request.Builder().url("$api/signal").post(body).build()).execute().use {
            if (!it.isSuccessful) throw IOException("signal offer HTTP ${it.code}")
            val id = JSONObject(it.body.string()).optString("id")
            require(id.isNotEmpty()) { "signal offer missing id" }
            id
        }
    }

    /** 每 1.5 秒轮询 answer，25 秒未应答则失败。 / Polls every 1.5 s and gives up after 25 s. */
    suspend fun answer(entry: String, deviceId: String, signalId: String): String = withTimeout(25_000) {
        val api = signalUrl(entry, deviceId)
        while (true) {
            val answer = client.newCall(Request.Builder().url("$api/signal/$signalId").build()).execute().use {
                when {
                    it.code == 404 -> null
                    !it.isSuccessful -> throw IOException("signal answer HTTP ${it.code}")
                    else -> JSONObject(it.body.string()).optString("answer").takeIf { value -> value.isNotEmpty() }
                }
            }
            if (answer != null) return@withTimeout answer
            delay(1_500)
        }
        throw IOException("signal answer unavailable")
    }

    private fun signalUrl(entry: String, deviceId: String): String {
        val fixed = requireNotNull(entry.toHttpUrlOrNull())
        return fixed.newBuilder().encodedPath("/api/v1/devices/$deviceId").build().toString()
    }
}
