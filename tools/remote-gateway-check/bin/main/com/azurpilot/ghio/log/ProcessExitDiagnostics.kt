package com.azurpilot.ghio.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.File

/**
 * 在导出启动器日志时收集本应用的退出记录和系统仍保留的崩溃堆栈。
 *
 * Android 11+ 可读退出原因，Android 12+ 的原生堆栈是 protobuf；最多保留四份，
 * 每份限制 4 MiB，超限时不保存不完整的 protobuf。系统可能已清除堆栈，缺失不影响导出。
 *
 * Collects this application's exit records and retained crash traces during launcher export.
 * Exit reasons require Android 11; native traces on Android 12+ are protobuf. Keeps at most
 * four traces of up to 4 MiB each, omitting oversized incomplete protobufs. Missing system
 * traces never prevent log export.
 */
internal object ProcessExitDiagnostics {
    private val format = Json { prettyPrint = true }
    /** 在 IO 线程收集，不需要特权。 / Collects on IO without privileged access. */
    @Synchronized
    fun collect(context: Context, directory: File) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val manager = context.getSystemService(ActivityManager::class.java)
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            var traceCount = 0
            val records = buildJsonArray {
                exits.forEach { exit ->
                    add(buildJsonObject {
                        put("timestamp_ms", exit.timestamp)
                        put("pid", exit.pid)
                        put("process", exit.processName)
                        put("reason", exit.reason)
                        put("status", exit.status)
                        put("description", exit.description ?: "")
                        put("rss_kib", exit.rss)
                        if (traceCount < 4 && exit.reason in setOf(
                                ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)) {
                            traceCount++
                            val suffix = if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) "pb" else "txt"
                            val file = File(directory, "trace_${exit.timestamp}_${exit.pid}.$suffix")
                            runCatching {
                                exit.traceInputStream?.use { input ->
                                    val bytes = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (bytes.size() <= 4 * 1024 * 1024) {
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        bytes.write(buffer, 0, count)
                                    }
                                    if (bytes.size() <= 4 * 1024 * 1024) {
                                        file.writeBytes(bytes.toByteArray())
                                    } else put("trace_omitted_too_large", true)
                                }
                            }.onFailure { put("trace_error", it.javaClass.simpleName) }
                            if (file.isFile) put("trace_file", file.name)
                        }
                    })
                }
            }
            File(directory, "process-exits.json").writeText(
                format.encodeToString(JsonArray.serializer(), records))
            directory.listFiles()?.filter { it.name.startsWith("trace_") }
                ?.sortedByDescending { it.name }?.drop(4)?.forEach { it.delete() }
        }.onFailure { Timber.w(it, "Process exit diagnostics unavailable") }
    }
}
