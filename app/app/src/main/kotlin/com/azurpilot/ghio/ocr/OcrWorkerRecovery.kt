package com.azurpilot.ghio.ocr

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.azurpilot.ghio.BuildConfig
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.File

/**
 * 在宿主保存工作进程失败的模型记录，防止同一 APK 反复触发原生崩溃。
 *
 * 仅宿主读写 preferences，绑定 Intent 向工作进程传递快照；升级 APK 后清除记录。
 *
 * Stores failed worker model gates in the host to prevent repeated native crashes in one APK.
 * Only the host reads/writes preferences; bind intents carry snapshots to workers. APK upgrades
 * clear the gates.
 */
internal class OcrWorkerRecovery(private val context: Context) {
    private val trace = OcrTrace(context)
    private val prefs = context.getSharedPreferences("ocr_worker_recovery", Context.MODE_PRIVATE)
    private val disabled = if (prefs.getInt("version", -1) == BuildConfig.VERSION_CODE) {
        runCatching {
            Json.parseToJsonElement(prefs.getString("models", "{}")!!).jsonObject
                .mapValues { it.value.jsonPrimitive.content }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    } else mutableMapOf()

    /** 返回不含口令的禁用模型快照。 / Returns a disabled-model snapshot without tokens. */
    @Synchronized
    fun snapshot(): String = JsonObject(disabled.mapValues { JsonPrimitive(it.value) }).toString()

    /**
     * 根据退出摘要或匹配阶段记录硬件故障；CPU 路径的退出仅采集日志。在 IO 线程调用。
     *
     * Gates hardware failures from exit summaries or matching stages. CPU exits only collect logs.
     * Called on IO.
     */
    @Synchronized
    fun recordDeath(since: Long) {
        val exit = if (Build.VERSION.SDK_INT >= 30) runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .firstOrNull { it.processName == "${context.packageName}:ocr" && it.timestamp >= since }
        }.getOrNull() else null
        val stage = runCatching {
            val directory = context.getExternalFilesDir("debug") ?: return@runCatching null
            File(directory, "ocr/stages.jsonl").readLines().asReversed().firstNotNullOfOrNull { line ->
                runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()?.takeIf {
                    val timestamp = it["timestamp_ms"]?.jsonPrimitive?.longOrNull ?: 0
                    timestamp >= since && it["app_version"]?.jsonPrimitive?.content == BuildConfig.VERSION_NAME &&
                        (exit == null || (it["pid"]?.jsonPrimitive?.intOrNull == exit.pid &&
                            timestamp <= exit.timestamp))
                }
            }
        }.getOrNull()
        val summary = exit?.processStateSummary?.toString(Charsets.UTF_8)
        val modelHash = Regex("[a-f0-9]{64}")
        val summaryHash = summary?.removePrefix(OcrTrace.CPU_SUMMARY_PREFIX)?.takeIf(modelHash::matches)
        val stageHash = stage?.get("model_sha256")?.jsonPrimitive?.content
        val hash = summaryHash ?: stageHash?.takeIf(modelHash::matches) ?: "*"
        val phase = stage?.takeIf { stageHash == hash }?.get("stage")?.jsonPrimitive?.content
        val cpuExit = if (summaryHash != null) {
            summary.startsWith(OcrTrace.CPU_SUMMARY_PREFIX)
        } else phase == "litert_cpu_compile"
        if (cpuExit) {
            Timber.w("OCR worker exited on the CPU path for %s", hash.take(12))
        } else {
            val location = phase?.takeUnless { it.isBlank() || it == "litert_cpu_compile" }
                ?.take(80) ?: "an unknown stage"
            disabled[hash] = "Native OCR worker exited near $location; NPU disabled until APK update"
            prefs.edit().putInt("version", BuildConfig.VERSION_CODE).putString("models", snapshot()).commit()
            Timber.w("OCR worker exited; CPU recovery enabled for %s", hash.take(12))
        }
        // 原生信号无法执行工作进程的 finally；同 UID 的主进程仍能读取其残留 logcat。
        val workerPid = exit?.pid ?: stage?.get("pid")?.jsonPrimitive?.intOrNull
        if (hash != "*" && workerPid != null) trace.captureNative(hash, workerPid)
    }
}
