package com.azurpilot.ghio.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.azurpilot.ghio.AppDispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出类型：两类导出各自打包、各自管理旧 zip；底部 sheet 按这个类型决定标题与产物
 *
 * Export kinds: each kind packs and manages its old zips independently; the bottom
 * sheet picks the title and the artifact by this type.
 */
enum class LogExportKind(val filePrefix: String) {
    /** AzurPilot 日志（官方 issue 格式：zip 条目镜像 `log/xxx` 相对路径，只收近 7 天）/ AzurPilot logs (official-issue format: zip entries mirror the `log/xxx` relative paths, last 7 days only). */
    AZURPILOT("azurpilot_logs_"),

    /** 启动器日志（log/ + debug/ 全量收集，常驻附 getprop 的 properties.txt）/ Launcher logs (full collection of log/ + debug/, always including a getprop properties.txt). */
    LAUNCHER("launcher_logs_"),
}

/**
 * 把日志打成 zip 交出去
 *
 * 反馈问题时最费劲的一步是「把日志弄出来」——设备上没有文件管理入口，adb 又不是人人都有。
 * 打一个包直接分享出去，这一步就没了
 *
 * 不删源：导完还留在设备上，用户可以再导一次；旧 zip 按 [LogExportKind] 各自先删再打
 *
 * Packs logs into a zip and hands it over.
 *
 * The hardest step when reporting an issue is "getting the logs off the device" — there
 * is no file-manager entry on-device and adb is not something everyone has. Producing
 * one shareable bundle removes that step.
 *
 * Sources are never deleted: after an export everything stays on the device and the user
 * can export again; old zips are deleted then rebuilt per [LogExportKind].
 */
class LogExportService(
    private val context: Context,
    /** 导出根；zip 里的路径相对 [baseDir] / the export root; zip paths are relative to [baseDir]. */
    private val baseDir: () -> File,
    /** 启动器日志的根目录集合 / the launcher-log root directories. */
    private val launcherRoots: () -> List<File>,
    /** AzurPilot 日志目录（`rootfs/opt/azurpilot/log`）；zip 条目镜像成官方 issue 的 `log/xxx` / the AzurPilot log directory (`rootfs/opt/azurpilot/log`); zip entries mirror the official-issue `log/xxx` layout. */
    private val logDir: () -> File,
) {

    /**
     * 打包导出 zip；IO 调度器上执行
     *
     * Packs the export zip; runs on the IO dispatcher.
     *
     * @return 导出的 zip；null = 没有可导出的日志，或打包失败 / the exported zip;
     *   null = nothing to export, or packing failed
     */
    suspend fun exportZip(kind: LogExportKind): File? = withContext(AppDispatchers.IO) {
        if (kind == LogExportKind.LAUNCHER) {
            ProcessExitDiagnostics.collect(context, File(baseDir(), "debug/process-exits"))
        }
        val now = System.currentTimeMillis()
        val files = when (kind) {
            LogExportKind.AZURPILOT -> LogExportCollector.collectAzurPilot(logDir(), now)
            LogExportKind.LAUNCHER -> LogExportCollector.collect(launcherRoots(), now)
        }
        if (files.isEmpty()) {
            Timber.w("没有可导出的日志：%s", kind)
            return@withContext null
        }
        runCatching {
            val dir = File(baseDir(), "${LOG_DIR_NAME}/${LogExportCollector.EXPORT_DIR_NAME}")
                .apply { mkdirs() }
            // 只留最新一份：旧包对用户没用，留着纯占空间；两类导出各自管自己的前缀
            dir.listFiles()
                ?.filter { it.name.startsWith(kind.filePrefix) }
                ?.forEach { it.delete() }
            val zip = File(dir, "${kind.filePrefix}${STAMP.format(Date())}.zip")
            writeZip(kind, zip, files)
            zip
        }.onFailure { Timber.w(it, "导出日志失败：%s", kind) }.getOrNull()
    }

    /**
     * 生成系统分享 Intent；打包走 [exportZip]，无产物时返回 null
     *
     * Builds the system share Intent; packing goes through [exportZip], returning null
     * when there is no artifact.
     */
    suspend fun shareIntent(kind: LogExportKind): Intent? = exportZip(kind)?.let(::createShareIntent)

    /**
     * 写进用户经 SAF 选的位置；IO 调度器上执行
     *
     * Copies to a location the user picked via SAF; runs on the IO dispatcher.
     *
     * @return 成功返回显示名，失败或无产物返回 null / the display name on success,
     *   null on failure or when there is no artifact
     */
    suspend fun exportTo(kind: LogExportKind, target: Uri): String? = withContext(AppDispatchers.IO) {
        val zip = exportZip(kind) ?: return@withContext null
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out ->
                zip.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching null
            displayName(target) ?: zip.name
        }.onFailure { Timber.w(it, "写入导出目标失败：%s", target) }.getOrNull()
    }

    /** SAF 保存建议的文件名（与 exportZip 产物同名） / The suggested SAF save name (identical to the exportZip artifact name). */
    fun suggestedFileName(kind: LogExportKind): String = "${kind.filePrefix}${STAMP.format(Date())}.zip"

    /** 写 zip：启动器包常驻附 properties.txt，条目时间戳取源文件 mtime / Writes the zip: the launcher bundle always carries properties.txt, entry timestamps follow source mtimes. */
    private fun writeZip(kind: LogExportKind, zip: File, files: List<File>) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { out ->
            // getprop 常驻附在启动器包：ROM 差异是排障时最先要问的；AzurPilot 包走官方 issue 格式不附
            if (kind == LogExportKind.LAUNCHER) appendDeviceProperties(out)
            files.forEach { file ->
                val entry = ZipEntry(entryName(kind, file))
                entry.time = file.lastModified()
                out.putNextEntry(entry)
                file.inputStream().use { it.copyTo(out, BUFFER_SIZE) }
                out.closeEntry()
            }
        }
    }

    /** zip 条目名：AzurPilot 补 `log/` 前缀，启动器按相对 [baseDir] 路径 / The zip entry name: `log/`-prefixed for AzurPilot, relative to [baseDir] for launcher logs. */
    private fun entryName(kind: LogExportKind, file: File): String = when (kind) {
        // 官方 issue 格式：相对 AzurPilot 日志目录的路径前面补 `log/`（error/ 子目录原样带上）
        LogExportKind.AZURPILOT -> "log/" + file.relativeTo(logDir()).invariantSeparatorsPath
        LogExportKind.LAUNCHER -> file.relativeTo(baseDir()).invariantSeparatorsPath
    }

    /** 取不到就跳过：少一份设备属性不该让整个导出失败 */
    private fun appendDeviceProperties(out: ZipOutputStream) {
        runCatching {
            val process = Runtime.getRuntime().exec("getprop")
            out.putNextEntry(ZipEntry(PROPERTIES_ENTRY))
            process.inputStream.use { it.copyTo(out, BUFFER_SIZE) }
            out.closeEntry()
            process.waitFor()
        }.onFailure { Timber.w(it, "收集设备属性失败") }
    }

    /** 走 FileProvider 而非 file://：API 24 起后者直接抛 FileUriExposedException */
    private fun createShareIntent(zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_ZIP
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(com.azurpilot.ghio.R.string.log_export_subject))
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /** 查询 SAF 目标的显示名；取不到返回 null / Queries the SAF target's display name; null when unavailable. */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
    }.getOrNull()

    private companion object {
        const val LOG_DIR_NAME = "log"
        const val PROPERTIES_ENTRY = "properties.txt"
        const val MIME_ZIP = "application/zip"
        const val BUFFER_SIZE = 8 * 1024


        val STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
