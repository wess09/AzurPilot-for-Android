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
     * 从退出摘要或最近阶段确定失败模型，无法确定时暂禁所有 NPU；在 IO 线程调用。
     *
     * Gates the model from exit summaries or stages, or all NPU when unknown; called on IO.
     */
    @Synchronized
    fun recordDeath(since: Long) {
        val exit = if (Build.VERSION.SDK_INT >= 30) runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 16)
                .firstOrNull { it.processName == "${context.packageName}:ocr" && it.timestamp >= since }
        }.getOrNull() else null
        val stage = runCatching {
            File(context.getExternalFilesDir("debug"), "ocr/stages.jsonl")
                .readLines().lastOrNull()?.let { Json.parseToJsonElement(it).jsonObject }
                ?.takeIf { (it["timestamp_ms"]?.jsonPrimitive?.longOrNull ?: 0) >= since }
                ?.takeIf { it["app_version"]?.jsonPrimitive?.content == BuildConfig.VERSION_NAME }
        }.getOrNull()
        val hash = (exit?.processStateSummary?.toString(Charsets.UTF_8) ?:
            stage?.get("model_sha256")?.jsonPrimitive?.content)
            ?.takeIf { Regex("[a-f0-9]{64}").matches(it) } ?: "*"
        val phase = stage?.get("stage")?.jsonPrimitive?.content.orEmpty().take(80)
        disabled[hash] = "Native OCR worker exited near $phase; NPU disabled until APK update"
        prefs.edit().putInt("version", BuildConfig.VERSION_CODE).putString("models", snapshot()).commit()
        // 原生信号无法执行工作进程的 finally；同 UID 的主进程仍能读取其残留 logcat。
        val workerPid = exit?.pid ?: stage?.get("pid")?.jsonPrimitive?.intOrNull
        if (hash != "*" && workerPid != null) trace.captureNative(hash, workerPid)
        Timber.w("OCR worker exited; CPU recovery enabled for %s", hash.take(12))
    }
}
