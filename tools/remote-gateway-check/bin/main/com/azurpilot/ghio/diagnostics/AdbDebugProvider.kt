package com.azurpilot.ghio.diagnostics

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.ocr.OcrServer
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.proot.AzurPilotRunController
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.koin.core.context.GlobalContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 为 ADB 提供固定的 OCR 和运行时诊断命令，沿用 App 的真实调用链。
 *
 * 仅持有系统 DUMP 权限的 Binder 调用者可访问；不接受任意代码、文件路径或 API 口令。
 * call 在 Binder 线程等待 IO 工作完成，不阻塞主线程。长命令串行，结果原子保存到
 * externalFiles/debug/adb/latest.json，冷启动主进程时不打开 Activity。
 *
 * Provides fixed ADB OCR and runtime diagnostics through the app's production call paths.
 * Only Binder callers holding system DUMP permission can access commands; accepts no arbitrary
 * code, paths, or API tokens. Calls wait on Binder threads for IO work without blocking main.
 * Long commands serialize and atomically save externalFiles/debug/adb/latest.json. Cold startup
 * launches the main process without opening an Activity.
 */
class AdbDebugProvider : ContentProvider() {
    private val commandLock = Semaphore(1)
    private val methods = listOf("help", "debug-last", "debug-export", "ocr-status", "ocr-test",
        "ocr-test-all", "ocr-test-cpu", "ocr-test-mixed", "ocr-test-gpu-softmax", "ocr-hardware-acceleration",
        "ocr-ap-test", "ocr-ap-config-test", "runtime-status", "runtime-start", "storage-status")
    private val server: OcrServer get() = GlobalContext.get().get()
    private val host: ProotHost get() = GlobalContext.get().get()
    private val controller: AzurPilotRunController get() = GlobalContext.get().get()
    private val directory: File get() = File(requireNotNull(context).getExternalFilesDir("debug")
        ?: error("External debug storage unavailable"), "adb").also {
        check(it.isDirectory || it.mkdirs()) { "Could not create debug directory" }
    }

    /** 延迟 DI 解析直到 Application 初始化完成。 / Defers DI lookup until Application initialization. */
    override fun onCreate(): Boolean = true

    /**
     * 校验真实 Binder 调用者，再执行白名单命令并返回无口令 JSON。
     *
     * Checks the Binder caller before running an allowlisted command and returning token-free JSON.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // ContentProvider.call 不自动实施完整读写权限检查，不能只依赖 Manifest。
        requireNotNull(context).enforceCallingPermission(Manifest.permission.DUMP, "ADB diagnostics require DUMP")
        val reply = when (method) {
            "help" -> buildJsonObject {
                put("ok", true)
                put("methods", JsonArray(methods.map(::JsonPrimitive)))
                put("model_argument", "ONNX SHA-256 from ocr-status")
                put("hardware_acceleration_argument", "on / off; persists the app setting")
            }
            "debug-last" -> runCatching {
                val file = File(directory, "latest.json")
                if (file.isFile) Json.parseToJsonElement(file.readText()).jsonObject
                else buildJsonObject { put("ok", false); put("error", "No debug command has run") }
            }.getOrElse { buildJsonObject { put("ok", false); put("error", "Debug report unavailable") } }
            else -> {
                if (!commandLock.tryAcquire()) buildJsonObject {
                    put("ok", false); put("error", "Another debug command is running")
                } else try {
                    runBlocking(AppDispatchers.IO) { execute(method, arg, extras) }
                } finally { commandLock.release() }
            }
        }
        return Bundle().apply { putString("json", reply.toString()) }
    }

    private suspend fun execute(method: String, arg: String?, extras: Bundle?): JsonObject {
        val id = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        fun report(state: String, result: JsonElement? = null, error: String? = null) = buildJsonObject {
            put("request_id", id)
            put("command", method)
            put("app_version", BuildConfig.VERSION_NAME)
            put("version_code", BuildConfig.VERSION_CODE)
            put("state", state)
            put("ok", state == "completed")
            put("started_ms", started)
            put("elapsed_ms", System.currentTimeMillis() - started)
            put("report_path", File(directory, "latest.json").absolutePath)
            result?.let { put("result", it) }
            error?.let { put("error", it.take(500)) }
        }
        return try {
            require(method in methods) { "Unknown debug command; use help" }
            require(extras == null || extras.isEmpty) { "Debug commands do not accept extras" }
            require(method in listOf("ocr-test", "ocr-test-cpu", "ocr-test-mixed", "ocr-test-gpu-softmax", "ocr-ap-test",
                "ocr-hardware-acceleration") || arg == null) { "Unexpected argument" }
            save(report("running"))
            if (method.startsWith("ocr-") || method.startsWith("runtime-")) {
                // 冷启动时 Binder 已可达而 Application.onCreate 仍在主线程执行；排队后再取 DI。
                withTimeout(10_000) { withContext(Dispatchers.Main) { GlobalContext.get() } }
            }
            val result: JsonElement = when (method) {
                "ocr-status" -> server.status()
                "ocr-hardware-acceleration" -> server.setHardwareAcceleration(when (arg) {
                    "on" -> true
                    "off" -> false
                    else -> error("Hardware acceleration argument must be on or off")
                })
                "ocr-test" -> server.test(modelHash(arg))
                "ocr-test-cpu" -> server.testCpu(modelHash(arg))
                "ocr-test-gpu-softmax" -> server.testGpuSoftmax(modelHash(arg))
                "ocr-test-mixed" -> server.testMixed(modelHash(arg))
                "ocr-test-all" -> {
                    val candidates = server.status().getValue("model_status").jsonArray
                        .map { it.jsonObject }.filter { it["testable"]?.jsonPrimitive?.booleanOrNull == true }
                    val results = mutableListOf<JsonElement>()
                    for (candidate in candidates) {
                        results += server.test(candidate.getValue("model_sha256").jsonPrimitive.content)
                        save(report("running", JsonArray(results)))
                    }
                    JsonArray(results)
                }
                "ocr-ap-test" -> host.testOcrIntegration(modelHash(arg))
                "ocr-ap-config-test" -> {
                    val state = controller.state.value
                    val result = host.testConfiguredOcr(state.runningConfig ?: state.selectedConfig)
                    buildJsonObject {
                        result.forEach { (key, value) -> put(key, value) }
                        put("running_config", state.runningConfig?.let(::JsonPrimitive) ?: JsonNull)
                        put("game_process_alive", state.runnerAlive)
                    }
                }
                "runtime-status" -> runtimeStatus()
                "storage-status" -> AppStorageDiagnostics.measure(requireNotNull(context))
                "runtime-start" -> {
                    host.ensureStarted()
                    withTimeout(120_000) {
                        while (host.state.value.phase != ProotPhase.RUNNING) {
                            check(host.state.value.phase != ProotPhase.FAILED) { host.state.value.detail }
                            delay(250)
                        }
                    }
                    runtimeStatus()
                }
                "debug-export" -> exportLogs()
                else -> error("Unsupported debug command")
            }
            report("completed", result).also(::save)
        } catch (error: Exception) {
            Timber.w("ADB diagnostic %s failed: %s", method.take(40), error.javaClass.simpleName)
            report("failed", error = error.message ?: error.javaClass.simpleName).also { runCatching { save(it) } }
        } catch (error: LinkageError) {
            report("failed", error = error.message ?: error.javaClass.simpleName).also { runCatching { save(it) } }
        }
    }

    private fun modelHash(arg: String?): String = requireNotNull(arg) { "Model SHA-256 required" }.also {
        require(Regex("[a-f0-9]{64}").matches(it)) { "Invalid model SHA-256" }
    }

    private fun runtimeStatus() = buildJsonObject {
        put("phase", host.state.value.phase.name)
        put("detail", host.state.value.detail.take(500))
        put("start_requested", host.startRequested)
    }

    private fun save(report: JsonObject) {
        val names = if (report["state"]?.jsonPrimitive?.content == "running") listOf("latest")
            else listOf("latest", "result")
        for (name in names) {
            val temporary = File(directory, "$name.tmp")
            temporary.writeText(report.toString())
            Files.move(temporary.toPath(), File(directory, "$name.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun exportLogs(): JsonObject {
        val target = File(directory, "ocr-debug.zip")
        val temporary = File(directory, "ocr-debug.tmp")
        val ocr = File(directory.parentFile, "ocr")
        ZipOutputStream(temporary.outputStream()).use { zip ->
            // 仅导出不含口令和输入张量的 OCR 阶段与原生快照，不收集用户配置。
            val files = ocr.listFiles().orEmpty().filter {
                it.name in listOf("stages.jsonl", "stages.previous.jsonl") ||
                    Regex("native-[a-f0-9]{12}\\.log").matches(it.name)
            }.sortedBy { it.name }.take(16)
            for (file in files) {
                require(file.isFile && file.length() <= 512 * 1024) { "Unexpected OCR log size" }
                zip.putNextEntry(ZipEntry("ocr/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (name in listOf("latest.json", "result.json")) {
                val file = File(directory, name)
                if (file.isFile) {
                    zip.putNextEntry(ZipEntry("adb/$name"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
        return buildJsonObject { put("archive_path", target.absolutePath); put("bytes", target.length()) }
    }

    /** 不提供数据库查询。 / Does not expose database queries. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = error("Use call")

    /** 不发布文件类型。 / Publishes no file types. */
    override fun getType(uri: Uri): String? = null

    /** 不允许数据库插入。 / Disallows database insertion. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Use call")

    /** 不允许数据库删除。 / Disallows database deletion. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Use call")

    /** 不允许数据库更新。 / Disallows database updates. */
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = error("Use call")
}
