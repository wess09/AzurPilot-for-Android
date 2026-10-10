package com.azurpilot.ghio.ocr

import android.content.Context
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * 在 OCR 工作进程提供带口令的回环张量 API，独立于 UI 和特权设备桥。
 *
 * 一行 JSON 后跟 little-endian FP32 字节。由私有绑定服务管理生命周期；
 * 四个连接槽和有界报文避免无限线程或内存。推理在工作线程执行，模型锁由 [OcrEngine] 管理。
 *
 * Provides an authenticated loopback OCR tensor API in the OCR worker, separate from UI and
 * the privileged device bridge. JSON lines precede little-endian FP32 bytes. A private bound
 * service manages its lifecycle. Four connection slots and bounded messages
 * prevent unbounded threads/memory. Workers infer under [OcrEngine]'s model lock.
 */
internal class OcrWorkerServer(
    context: Context,
    private val token: String,
    disabledModels: Map<String, String>,
    hardwareAccelerationEnabled: Boolean,
) : AutoCloseable {
    private val engine by lazy { OcrEngine(context, disabledModels, hardwareAccelerationEnabled) }
    private val clients = Semaphore(4)
    private val inference = Semaphore(1)
    @Volatile private var socket: ServerSocket? = null

    /**
     * 当前监听地址；启动失败时为空。
     *
     * Active listener address, empty when startup failed.
     */
    val address: String get() = if (socket != null) "127.0.0.1:$PORT" else ""

    /**
     * 幂等启动；失败记录日志并保持空地址，由宿主阻止 AP 启动。
     *
     * Starts idempotently; failures retain an empty address so the host prevents AP startup.
     */
    @Synchronized
    fun start() {
        if (socket != null) return
        val listener = ServerSocket()
        try {
            engine.status()
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
            socket = listener
            thread(isDaemon = true, name = "ocr-accept") {
                try {
                    while (!listener.isClosed) {
                        val client = try { listener.accept() } catch (_: Exception) { break }
                        if (!clients.tryAcquire()) {
                            client.close()
                            continue
                        }
                        thread(isDaemon = true, name = "ocr-client") {
                            try {
                                serve(client)
                            } catch (error: LinkageError) {
                                // 链接错误只关闭连接，由客户端重试或报告失败，不终止宿主。
                                Timber.w(error, "OCR runtime linkage failed")
                            } finally {
                                client.close()
                                clients.release()
                            }
                        }
                    }
                } finally {
                    listener.close()
                    synchronized(this@OcrWorkerServer) {
                        if (socket === listener) socket = null
                    }
                }
            }
            Timber.i("OCR API listening on %s", address)
        } catch (error: Exception) {
            listener.close()
            Timber.w(error, "OCR API unavailable; AP startup requires the host OCR service")
        }
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            connection.soTimeout = 90_000
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            val expectedToken = token.toByteArray()
            try {
                while (true) {
                    val line = readLine(input) ?: return
                    val request = Json.parseToJsonElement(line).jsonObject
                    val token = request["token"]?.jsonPrimitive?.content.orEmpty().toByteArray()
                    require(MessageDigest.isEqual(expectedToken, token)) { "Unauthorized OCR request" }
                    when (request.getValue("method").jsonPrimitive.content) {
                        "status" -> send(output, buildJsonObject { put("ok", true); put("status", engine.status()) })
                        "set_hardware_acceleration" -> withInferenceBuffers {
                            engine.setHardwareAcceleration(request.getValue("enabled").jsonPrimitive.boolean)
                            send(output, buildJsonObject { put("ok", true); put("status", engine.status()) })
                        }
                        "test" -> withInferenceBuffers {
                            val result = engine.test(request.getValue("model_sha256").jsonPrimitive.content)
                            send(output, buildJsonObject { put("ok", true); put("result", result) })
                        }
                        "test_cpu" -> withInferenceBuffers {
                            val result = engine.testCpu(request.getValue("model_sha256").jsonPrimitive.content)
                            send(output, buildJsonObject { put("ok", true); put("result", result) })
                        }
                        "test_gpu_softmax" -> withInferenceBuffers {
                            val result = engine.testGpuSoftmax(request.getValue("model_sha256").jsonPrimitive.content)
                            send(output, buildJsonObject { put("ok", true); put("result", result) })
                        }
                        "test_mixed" -> withInferenceBuffers {
                            val result = engine.testMixed(request.getValue("model_sha256").jsonPrimitive.content)
                            send(output, buildJsonObject { put("ok", true); put("result", result) })
                        }
                        "describe" -> send(output, buildJsonObject {
                            put("ok", true)
                            put("model", engine.describe(request.getValue("model_sha256").jsonPrimitive.content))
                        })
                        "run" -> withInferenceBuffers {
                            val hash = request.getValue("model_sha256").jsonPrimitive.content
                            val spec = engine.describe(hash)
                            val shape = request.getValue("shape").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
                            require(shape.size == 4 && shape.all { it in 1..4096 })
                            val count = shape.fold(1L, Long::times)
                            require(count in 1..MAX_PAYLOAD / 4L)
                            val length = request.getValue("length").jsonPrimitive.int
                            require(length.toLong() == count * 4L)
                            val name = spec.getValue("inputs").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content
                            require(request.getValue("input_name").jsonPrimitive.content == name)
                            val payload = ByteArray(length)
                            var offset = 0
                            while (offset < length) {
                                val read = input.read(payload, offset, length - offset)
                                check(read > 0) { "Truncated OCR request" }
                                offset += read
                            }
                            val floats = FloatArray(count.toInt())
                            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                            val source = request["source"]?.jsonPrimitive?.content ?: "ap"
                            require(source in setOf("ap", "diagnostic"))
                            val result = engine.run(hash, shape, floats, source)
                            require(result.values.size <= MAX_PAYLOAD / 4)
                            val replyBytes = ByteArray(result.values.size * 4)
                            ByteBuffer.wrap(replyBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(result.values)
                            val outputName = spec.getValue("outputs").jsonArray.single().jsonObject.getValue("name")
                            send(output, buildJsonObject {
                                put("ok", true)
                                put("backend", result.backend)
                                put("length", replyBytes.size)
                                put("outputs", buildJsonArray { add(buildJsonObject {
                                    put("name", outputName)
                                    put("shape", JsonArray(result.shape.map(::JsonPrimitive)))
                                }) })
                            }, replyBytes)
                        }
                        else -> error("Unknown OCR method")
                    }
                }
            } catch (error: Exception) {
                runCatching {
                    send(output, buildJsonObject {
                        put("ok", false)
                        if (error is OcrCpuInitializationException) put("error_code", "cpu_initialization_failed")
                        put("error", error.message?.take(300) ?: "OCR request failed")
                    })
                }
                Timber.d("OCR connection closed: %s", error.javaClass.simpleName)
            }
        }
    }

    private inline fun withInferenceBuffers(action: () -> Unit) {
        // 模型锁只保护推理；收发缓冲也必须串行，否则等待的客户端会各占两份大输入。
        inference.acquire()
        try {
            action()
        } finally {
            inference.release()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 16 * 1024) {
            val next = input.read()
            if (next == -1) {
                check(bytes.size() == 0) { "Truncated OCR header" }
                return null
            }
            if (next == 10) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(next)
        }
        error("OCR request header exceeds 16 KiB")
    }

    private fun send(output: BufferedOutputStream, header: JsonObject, bytes: ByteArray = byteArrayOf()) {
        output.write(header.toString().toByteArray())
        output.write(10)
        output.write(bytes)
        output.flush()
    }

    private companion object {
        const val PORT = 22302
        const val MAX_PAYLOAD = 64 * 1024 * 1024
    }

    /**
     * 关闭监听，并在后台释放模型，避免服务主线程等待原生推理。
     *
     * Closes the listener and frees models off the service main thread.
     */
    override fun close() {
        socket?.close()
        thread(isDaemon = true, name = "ocr-close") { engine.close() }
    }
}
