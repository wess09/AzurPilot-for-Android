package com.azurpilot.ghio.remoteaccess

import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 在 IO 调度器解析固定入口并验证客户端可达的 HTTP 地址，失败后请求 Cloudflare。
 *
 * Resolves registry entries and checks HTTP reachability on the IO dispatcher,
 * requesting Cloudflare after direct candidates fail.
 */
class RemoteConnections(private val client: OkHttpClient) {
    private val failed = ConcurrentHashMap<String, Long>()
    private val probeClient = client.newBuilder().connectTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS).followRedirects(false).build()

    /**
     * 暂时排除握手或会话失败的入口，使下一次重连可以选择备用路径。
     *
     * Temporarily excludes a failed handshake or session so reconnect can select a fallback.
     */
    fun connectionFailed(url: String) {
        failed[url.trimEnd('/')] = System.nanoTime()
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
        val whenFailed = failed[key] ?: return false
        if (System.nanoTime() - whenFailed < TimeUnit.SECONDS.toNanos(30)) return true
        failed.remove(key, whenFailed)
        return false
    }

    private fun reachable(url: String, deviceId: String): Boolean = runCatching {
        probeClient.newCall(Request.Builder().url(url.trimEnd('/') + "/healthz").build())
            .execute().use { response ->
                if (!response.isSuccessful) return@use false
                val data = JSONObject(response.body.string())
                data.optString("status") == "ok" && data.optInt("protocolVersion") == 1 &&
                    data.optString("device_id") == deviceId
            }
    }.getOrDefault(false)

    private fun direct(data: JSONObject, deviceId: String): String? {
        val endpoints = data.optJSONArray("endpoints") ?: return null
        for (index in 0 until endpoints.length()) {
            val url = endpoints.optJSONObject(index)?.optString("url").orEmpty()
            if (isDirect(url) && !excluded(url) && reachable(url, deviceId)) return url.trimEnd('/') + "/"
        }
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
        val tunnel = data.optString("tunnel_url")
        if (isTunnel(tunnel) && !excluded(tunnel)) return tunnel.trimEnd('/') + "/"
        client.newCall(Request.Builder().url("$api/fallback").post(ByteArray(0).toRequestBody()).build())
            .execute().use { if (!it.isSuccessful) throw IOException("AP 暂时无法连接") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (System.nanoTime() < deadline) {
            delay(2000)
            val updated = read() ?: throw IOException("注册服务协议已变化")
            direct(updated, deviceId)?.let { return it }
            val next = updated.optString("tunnel_url")
            if (isTunnel(next) && !excluded(next)) return next.trimEnd('/') + "/"
        }
        throw IOException("AP 连接超时，请稍后重试")
    }
}
