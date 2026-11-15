package com.azurpilot.ghio.remoteaccess

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 在 IO 调度器解析固定入口并验证客户端可达的 HTTP 地址，直连失败后静默尝试 P2P，再请求 Cloudflare。
 *
 * Resolves registry entries and checks HTTP reachability on the IO dispatcher, silently trying P2P
 * before requesting Cloudflare after direct candidates fail.
 */
class RemoteConnections(
    private val client: OkHttpClient,
    private val peerConnectionFactory: Peer2PeerConnectionFactory = UnavailablePeer2PeerConnectionFactory,
) {
    private val failed = ConcurrentHashMap<String, Long>()
    private val p2pUrls = ConcurrentHashMap<String, String>()
    private val activeP2p = ConcurrentHashMap<String, Peer2PeerConnection>()
    private val lastGood = ConcurrentHashMap<String, String>()
    private val probeClient = client.newBuilder().connectTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).build()

    /**
     * 暂时排除握手或会话失败的入口，使下一次重连可以选择备用路径。
     *
     * Temporarily excludes a failed handshake or session so reconnect can select a fallback.
     */
    fun connectionFailed(url: String) {
        val key = url.trimEnd('/')
        val stable = p2pUrls.remove(key) ?: key
        failed[stable] = System.nanoTime()
        activeP2p.remove(stable)?.close()
        lastGood.values.removeAll(setOf(stable))
    }

    /**
     * 仅接受公网 IP 根路径；禁止 DNS、私网地址和凭据出现在直连候选中。
     *
     * Accepts public literal IP roots only, rejecting DNS, private addresses, and credentials.
     */
    fun isDirect(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        if (url.scheme != "http" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null) return false
        val host = url.host
        if (!host.matches(Regex("[0-9.]+")) && !host.matches(Regex("[0-9a-fA-F:]+"))) return false
        val ip = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress ||
            ip.isSiteLocalAddress || ip.isMulticastAddress) return false
        if (host.contains(':')) return !host.startsWith("fc", true) && !host.startsWith("fd", true) &&
            !host.startsWith("2001:db8", true) && !host.startsWith("2002:", true)
        val parts = host.split('.').map { it.toIntOrNull() ?: return false }
        return parts.size == 4 && parts.all { it in 0..255 } && parts[0] in 1..223 &&
            !(parts[0] == 100 && parts[1] in 64..127) &&
            !(parts[0] == 192 && parts[1] == 0) &&
            !(parts[0] == 198 && parts[1] in 18..19) &&
            !(parts[0] == 198 && parts[1] == 51 && parts[2] == 100) &&
            !(parts[0] == 203 && parts[1] == 0 && parts[2] == 113)
    }

    private fun isTunnel(value: String): Boolean =
        value.matches(Regex("https://[a-z0-9-]+\\.trycloudflare\\.com/?"))

    private fun excluded(url: String): Boolean {
        val key = url.trimEnd('/')
        return excludedKey(key)
    }

    private fun excludedKey(key: String): Boolean {
        val whenFailed = failed[key] ?: return false
        if (System.nanoTime() - whenFailed < TimeUnit.SECONDS.toNanos(30)) return true
        failed.remove(key, whenFailed)
        return false
    }

    private fun p2pKey(deviceId: String) = "p2p://$deviceId"

    private suspend fun reachable(url: String, deviceId: String): Boolean = suspendCancellableCoroutine { pending ->
        val call = probeClient.newCall(Request.Builder().url(url.trimEnd('/') + "/healthz").build())
        pending.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                pending.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val valid = runCatching {
                    response.use {
                        if (!it.isSuccessful) return@use false
                        val data = JSONObject(it.body.string())
                        data.optString("status") == "ok" && data.optInt("protocolVersion") == 1 &&
                            data.optString("device_id") == deviceId
                    }
                }.getOrDefault(false)
                pending.resume(valid)
            }
        })
    }

    /**
     * 并行探测全部直连候选，先通者胜：串行等超时会把建链拖到数倍 RTT。
     *
     * Probes all direct candidates concurrently and takes the first one that answers;
     * sequential probing would stretch establishment to several times the round trip.
     */
    private suspend fun direct(data: JSONObject, deviceId: String): String? = coroutineScope {
        val endpoints = data.optJSONArray("endpoints") ?: return@coroutineScope null
        val candidates = (0 until endpoints.length()).mapNotNull { index ->
            endpoints.optJSONObject(index)?.optString("url").orEmpty().takeIf { value ->
                isDirect(value) && !excluded(value)
            }
        }
        val probes: List<Deferred<String?>> = candidates.map { candidate ->
            async(Dispatchers.IO) { if (reachable(candidate, deviceId)) candidate else null }
        }
        val pending = probes.toMutableList()
        while (pending.isNotEmpty()) {
            val (probe, winner) = select<Pair<Deferred<String?>, String?>> {
                pending.forEach { candidate -> candidate.onAwait { candidate to it } }
            }
            if (winner != null) {
                probes.forEach { it.cancel() }
                lastGood[deviceId] = winner.trimEnd('/')
                return@coroutineScope winner.trimEnd('/') + "/"
            }
            pending.remove(probe)
        }
        null
    }

    private suspend fun peerToPeer(
        entry: String,
        deviceId: String,
        data: JSONObject,
    ): String? {
        if (!data.optBoolean("p2p")) return null
        val key = p2pKey(deviceId)
        if (excludedKey(key)) return null
        var connection: Peer2PeerConnection? = null
        return try {
            activeP2p.remove(key)?.close()
            connection = peerConnectionFactory.connect(entry, deviceId, data.optString("stun"))
            val opened = requireNotNull(connection)
            val localUrl = opened.localUrl.trimEnd('/') + "/"
            p2pUrls[localUrl.trimEnd('/')] = key
            activeP2p[key] = opened
            localUrl
        } catch (cancellation: CancellationException) {
            connection?.close()
            throw cancellation
        } catch (failure: Throwable) {
            connection?.close()
            failed[key] = System.nanoTime()
            null
        }
    }

    /**
     * 先探测上次成功的入口：健康检查已校验 device_id，注册服务慢或离线也不拖累重连。
     *
     * Probes the previously successful entry first; its health check already verifies device_id,
     * so a slow or unreachable registry never delays reconnection.
     */
    private suspend fun cached(deviceId: String): String? {
        val cached = lastGood[deviceId] ?: return null
        if (reachable(cached, deviceId)) return "$cached/"
        lastGood.remove(deviceId, cached)
        return null
    }

    /**
     * 每次重连重新读取候选，旧服务保留 301 解析；全程不向注册服务发送 AP 登录凭据。
     *
     * Reloads candidates on reconnect, retaining legacy 301 support without sending AP credentials.
     */
    suspend fun resolve(entry: String, deviceId: String): String {
        require(deviceId.matches(Regex("[a-zA-Z0-9_-]{16,80}")))
        val fixed = requireNotNull(entry.toHttpUrlOrNull())
        require(fixed.scheme == "https" && fixed.username.isEmpty() && fixed.password.isEmpty() &&
            fixed.encodedPath == "/d/$deviceId/" && fixed.query == null && fixed.fragment == null)
        cached(deviceId)?.let { return it }
        val api = fixed.newBuilder().encodedPath("/api/v1/devices/$deviceId").build().toString()
        fun read(): JSONObject? = client.newCall(Request.Builder().url("$api/resolve")
            .header("Cache-Control", "no-cache").build()).execute().use { response ->
                if (response.code == 404 || response.code == 405) return@use null
                if (!response.isSuccessful) throw registryFailure(response.code, response.body.string())
                JSONObject(response.body.string()).also { require(it.optString("device_id") == deviceId) }
            }
        val data = read() ?: return client.newCall(Request.Builder().url(entry).build()).execute().use {
            val location = it.header("Location").orEmpty()
            check(it.code == 301 && isTunnel(location)) { "AP 暂时离线" }
            location.trimEnd('/') + "/"
        }
        direct(data, deviceId)?.let { return it }
        peerToPeer(entry, deviceId, data)?.let { return it }
        val tunnel = data.optString("tunnel_url")
        if (isTunnel(tunnel) && !excluded(tunnel)) return tunnel.trimEnd('/') + "/"
        client.newCall(Request.Builder().url("$api/fallback").post(ByteArray(0).toRequestBody()).build())
            .execute().use { if (!it.isSuccessful) throw IOException("AP 暂时无法连接") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            delay(2000)
            val updated = read() ?: throw IOException("注册服务协议已变化")
            direct(updated, deviceId)?.let { return it }
            peerToPeer(entry, deviceId, updated)?.let { return it }
            val next = updated.optString("tunnel_url")
            if (isTunnel(next) && !excluded(next)) return next.trimEnd('/') + "/"
        }
        throw IOException("AP 连接超时，请稍后重试")
    }
}
