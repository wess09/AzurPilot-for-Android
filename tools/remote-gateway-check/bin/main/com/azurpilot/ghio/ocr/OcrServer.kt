package com.azurpilot.ghio.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import com.azurpilot.ghio.proot.AndroidControlAuth
import com.azurpilot.ghio.settings.AppSettingsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 在宿主绑定私有 OCR 工作进程，状态和测试均走 AP 同用的认证回环接口。
 *
 * 公开阻塞方法由 IO 线程调用。原生退出后记录失败模型并重绑，失败模型改用 CPU。
 * 重试仅用于确认 Binder 已死亡的连接错误，不将普通 API 错误误报为原生崩溃。
 *
 * Binds the private OCR worker. Status and tests use AP's authenticated loopback API.
 * Blocking methods run on IO. Native exits gate failed models and rebind to CPU. Retries
 * require a dead Binder, distinguishing connection loss from ordinary API errors.
 */
class OcrServer(private val context: Context, private val settings: AppSettingsManager) {
    private val lock = Any()
    private val settingsLock = Any()
    private val recovery = OcrWorkerRecovery(context)
    private val callbacks = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ocr-lifecycle").apply { isDaemon = true }
    }
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var worker: IBinder? = null
    private var ready = CountDownLatch(0)
    private var boundAt = 0L

    /** 当前工作进程地址，绑定失败时为空。 / Worker address, empty when binding failed. */
    val address: String get() = if (worker?.isBinderAlive == true) "127.0.0.1:$PORT" else ""

    /** 返回工作进程状态，不初始化模型会话。 / Reads worker status without initializing sessions. */
    fun status(): JsonObject = request("status").getValue("status").jsonObject

    /** 经过实际 API 测试，原生退出时重试 CPU。 / Tests through the real API, retrying CPU after native exits. */
    fun test(hash: String): JsonObject = request("test", hash).getValue("result").jsonObject

    /** 只请求 CPU 的独立 LiteRT 会话计时。 / Times an isolated CPU-only LiteRT session. */
    fun testCpu(hash: String): JsonObject = request("test_cpu", hash).getValue("result").jsonObject

    /** 单独验证 GPU 末尾 Softmax。 / Tests terminal GPU Softmax independently. */
    fun testGpuSoftmax(hash: String): JsonObject = request("test_gpu_softmax", hash).getValue("result").jsonObject

    /** 对照 NPU 与 GPU 联合委派。 / Compares combined NPU/GPU delegation. */
    fun testMixed(hash: String): JsonObject = request("test_mixed", hash).getValue("result").jsonObject

    /**
     * 在 IO 保存用户选择并更新共享引擎；等待当前推理结束后生效，不重启 AP。
     *
     * Persists the choice and updates the shared engine on IO after any current inference,
     * without restarting AP. The saved choice also initializes recovered workers.
     */
    fun setHardwareAcceleration(enabled: Boolean): JsonObject = synchronized(settingsLock) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "OCR settings must run off the main thread" }
        runBlocking {
            withTimeout(10_000) {
                settings.loaded.first { it }
                settings.setOcrHardwareAccelerationEnabled(enabled)
                settings.ocrHardwareAccelerationEnabled.first { it == enabled }
            }
        }
        request("set_hardware_acceleration", enabled = enabled).getValue("status").jsonObject
    }

    /**
     * 幂等绑定并等待就绪，仅允许工作线程调用。
     *
     * Binds idempotently and waits for readiness on workers only.
     */
    fun start() = bind(wait = true)

    private fun bind(wait: Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "OCR binding must run off the main thread" }
        // 恢复工作进程前必须读到已保存的开关，不能让关闭加速的用户先跑一次 NPU。
        runBlocking { withTimeout(10_000) { settings.loaded.first { it } } }
        val stale = synchronized(lock) {
            if (worker?.isBinderAlive == false) connection?.let { it to boundAt } else null
        }
        stale?.let { lost(it.first, it.second, true) }
        val latch = synchronized(lock) {
            if (worker?.isBinderAlive == true) return
            if (connection == null) {
                val signal = CountDownLatch(1)
                val since = System.currentTimeMillis()
                val next = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        callbacks.execute {
                            synchronized(lock) {
                                if (connection === this) worker = service
                            }
                            signal.countDown()
                        }
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        callbacks.execute {
                            lost(this, since, true)
                            // 不等待同一执行器上的连接回调；AP 已持有固定地址，重绑恢复其重试。
                            runCatching { bind(wait = false) }
                                .onFailure { Timber.w(it, "OCR worker recovery binding failed") }
                        }
                    }
                    override fun onBindingDied(name: ComponentName) {
                        callbacks.execute { lost(this, since, false) }
                    }
                    override fun onNullBinding(name: ComponentName) {
                        callbacks.execute { lost(this, since, false) }
                    }
                }
                connection = next
                ready = signal
                boundAt = since
                val intent = Intent(context, OcrWorkerService::class.java)
                    .putExtra("token", AndroidControlAuth.get(context))
                    .putExtra("disabled_models", recovery.snapshot())
                    .putExtra("hardware_acceleration_enabled", settings.ocrHardwareAccelerationEnabled.value)
                if (!context.bindService(intent, next, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                    connection = null
                    signal.countDown()
                }
            }
            ready
        }
        if (wait) check(latch.await(10, TimeUnit.SECONDS) && address.isNotEmpty()) {
            "OCR worker could not start"
        }
    }

    private fun lost(previous: ServiceConnection, since: Long, crashed: Boolean) {
        synchronized(lock) {
            if (connection !== previous) return
            if (crashed) recovery.recordDeath(since)
            worker = null
            connection = null
            ready.countDown()
            runCatching { context.unbindService(previous) }
        }
    }

    private fun request(method: String, hash: String? = null, enabled: Boolean? = null): JsonObject {
        repeat(2) { attempt ->
            start()
            val binding = synchronized(lock) { Triple(connection, worker, boundAt) }
            try {
                Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", PORT), 3_000)
                    client.soTimeout = if (method in setOf("test", "test_cpu", "test_mixed", "test_gpu_softmax",
                        "set_hardware_acceleration")) 240_000 else 15_000
                    val header = buildJsonObject {
                        put("method", method)
                        put("token", AndroidControlAuth.get(context))
                        hash?.let { put("model_sha256", it) }
                        enabled?.let { put("enabled", it) }
                    }
                    BufferedOutputStream(client.getOutputStream()).also {
                        it.write((header.toString() + "\n").toByteArray(Charsets.UTF_8))
                        it.flush()
                    }
                    val input = BufferedInputStream(client.getInputStream())
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val next = input.read()
                        if (next == -1) throw EOFException("OCR worker closed the connection")
                        if (next == 10) break
                        require(bytes.size() < 1024 * 1024) { "OCR response header exceeds the limit" }
                        bytes.write(next)
                    }
                    val reply = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8.name())).jsonObject
                    check(reply["ok"]?.jsonPrimitive?.boolean == true) {
                        reply["error"]?.jsonPrimitive?.content ?: "OCR request failed"
                    }
                    return reply
                }
            } catch (error: IOException) {
                // Socket EOF 可能略早于 Binder 死亡通知；只等一次短窗口，避免误判普通网络错误。
                if (attempt == 0 && error is EOFException && binding.second?.isBinderAlive == true) {
                    Thread.sleep(200)
                }
                val dead = binding.second?.isBinderAlive == false
                if (dead) binding.first?.let { lost(it, binding.third, true) }
                if (!dead || attempt == 1) throw error
                Timber.w("OCR worker connection lost; retrying with CPU recovery")
            }
        }
        error("OCR worker retry exhausted")
    }

    private companion object { const val PORT = 22302 }
}
