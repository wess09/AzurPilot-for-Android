package com.azurpilot.ghio.remoteaccess

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 使用拦截器模拟候选端点，验证取消慢探测和复用已验证直连时不访问外网。
 *
 * Simulates endpoints with interceptors to verify slow-probe cancellation and reuse of verified
 * direct routes without external network access.
 */
@RunWith(AndroidJUnit4::class)
class RemoteConnectionsInstrumentedTest {
    @Test fun fastCandidateCancelsSlowProbe() = runBlocking(Dispatchers.IO) {
        val deviceId = "instrumented-device-1234"
        val slowStarted = CountDownLatch(1)
        val slowCancelled = CountDownLatch(1)
        val registryReads = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = when (request.url.host) {
                "registry.example" -> {
                    registryReads.incrementAndGet()
                    JSONObject().put("device_id", deviceId).put("endpoints", JSONArray()
                        .put(JSONObject().put("url", "http://1.1.1.1/"))
                        .put(JSONObject().put("url", "http://8.8.8.8/")))
                }
                "1.1.1.1" -> {
                    slowStarted.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
                    if (chain.call().isCanceled()) slowCancelled.countDown()
                    throw IOException("Simulated unavailable endpoint")
                }
                "8.8.8.8" -> {
                    assertTrue(slowStarted.await(2, TimeUnit.SECONDS))
                    JSONObject().put("status", "ok").put("protocolVersion", 1)
                        .put("device_id", deviceId)
                }
                else -> throw IOException("Unexpected test endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val connections = RemoteConnections(client)
            val entry = "https://registry.example/d/$deviceId/"
            val started = System.nanoTime()
            assertEquals("http://8.8.8.8/", connections.resolve(entry, deviceId))
            assertTrue("Slow candidates delayed the successful route",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            assertTrue("The losing HTTP call was not cancelled",
                slowCancelled.await(1, TimeUnit.SECONDS))
            assertEquals("http://8.8.8.8/", connections.resolve(entry, deviceId))
            assertEquals(1, registryReads.get())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
