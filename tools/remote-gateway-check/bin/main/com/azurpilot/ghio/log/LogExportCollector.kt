package com.azurpilot.ghio.log

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 挑出要打进 zip 的文件；不删源、不写盘，纯函数好测
 *
 * 我们的日志分在两棵目录下（`log/` 与 `debug/`），有意不归拢成一棵：`debug/` 那份的路径
 * 在特权进程侧是硬解析的（见 `RemoteBootTrace`），挪了要连着改两边
 *
 * Picks the files to pack into the export zip; no source deletion, no disk writes — a
 * pure, easily testable selection.
 *
 * The logs intentionally live under two trees (`log/` and `debug/`) rather than being
 * merged into one: the `debug/` path is hard-parsed on the privileged-process side (see
 * `RemoteBootTrace`), so moving it would require synchronized changes in both places.
 */
object LogExportCollector {

    /** 导出 zip 的存放目录名；收集时按它排除，防止 zip 套 zip / The directory name holding export zips; excluded during collection so zips never nest inside zips. */
    const val EXPORT_DIR_NAME = "export"

    /** 会无限长的那几个目录只留近 7 天；其余（app.log、触发日志）本身就有上限，全带 / Endlessly growing directories keep only the last 7 days; the rest (app.log, trigger logs) are already capped and always included. */
    const val ROLLING_KEEP_DAYS = 7L

    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    /**
     * 按次或按轮堆文件的目录
     *
     * 加新目录时记得往这里补一条，否则一年后的导出包会有上千个文件
     */
    private val ROLLING_MARKERS = listOf("/run/", "/focus/", "/logcat/", "/crash/")

    /**
     * 收集启动器日志（`log/` + `debug/` 全量，滚动目录只留近 [ROLLING_KEEP_DAYS] 天）
     *
     * Collects launcher logs (`log/` + `debug/` in full, rolling directories trimmed to
     * the last [ROLLING_KEEP_DAYS] days).
     *
     * @param roots 待扫描的根目录列表 / the root directories to scan
     * @param now 当前时间戳（毫秒），作滚动截止基准 / the current time in millis, the
     *   baseline for the rolling cutoff
     */
    fun collect(roots: List<File>, now: Long): List<File> =
        roots.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile }
            .filter { shouldExport(it, now - ROLLING_KEEP_DAYS * MS_PER_DAY) }
            .toList()

    /** 单文件是否入选：排除上一次导出的 zip，滚动目录按 mtime 截止 / Whether a file is selected: previous export zips are excluded, rolling directories cut off by mtime. */
    private fun shouldExport(file: File, rollingCutoff: Long): Boolean {
        val path = file.invariantSeparatorsPath
        // 上一次导出的 zip 不能再打进这一次，否则每导一次体积翻一倍
        if (path.contains("/$EXPORT_DIR_NAME/")) return false
        if (ROLLING_MARKERS.none { path.contains(it) }) return true
        return file.lastModified() >= rollingCutoff
    }

    /**
     * 在调用方线程递归收集 AzurPilot 日志，保留近 [ROLLING_KEEP_DAYS] 天的已知日期条目。
     *
     * 包含所有文件类型和子目录，排除根目录下的旧导出包。按天日志以文件名日期筛选，
     * 日期范围备份以结束日期筛选；错误现场兼容 `error/<实例>/<时间戳>/` 和旧的
     * `error/<时间戳>/`，入选现场内的文件不再单独筛选。无法识别的日期保留。
     *
     * Recursively collects AzurPilot logs on the caller's thread, keeping known dated entries
     * from the last [ROLLING_KEEP_DAYS] days.
     *
     * Includes all file types and subdirectories except previous exports at the log root.
     * Daily logs use their filename date; date-range backups use the end date. Error scenes
     * support both `error/<instance>/<timestamp>/` and legacy `error/<timestamp>/` layouts,
     * and selected scenes include every file without further filtering. Unknown dates are kept.
     *
     * @param now 筛选基准的毫秒时间戳；按天日期使用系统时区。 /
     *   Baseline timestamp in milliseconds; calendar dates use the system time zone.
     */
    fun collectAzurPilot(logDir: File, now: Long): List<File> {
        if (!logDir.isDirectory) return emptyList()
        val cutoffMillis = now - ROLLING_KEEP_DAYS * MS_PER_DAY
        val cutoffDay = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            .minusDays(ROLLING_KEEP_DAYS)
        val exportDir = File(logDir, EXPORT_DIR_NAME)
        return logDir.walkTopDown()
            .onEnter { it != exportDir }
            .filter { it.isFile }
            .filter { file ->
                val timestamp = errorSceneTimestampOf(file.relativeTo(logDir))
                if (timestamp != null) return@filter timestamp >= cutoffMillis
                val date = latestDateOf(file.name) ?: return@filter true
                !date.isBefore(cutoffDay)
            }
            .toList()
    }

    /**
     * 解析错误现场的路径时间戳，包括 `bak/` 中的拷贝备份；未知路径返回 null。
     *
     * Parses the timestamp in an error-scene path, including copied backups under `bak/`;
     * returns null for unknown paths.
     */
    private fun errorSceneTimestampOf(relativeFile: File): Long? {
        val directories = relativeFile.parentFile?.invariantSeparatorsPath?.split('/')
            ?: return null
        if (directories.first() != "error") return null
        val sceneIndex = if (directories.getOrNull(2) == "bak") 3 else 2
        return directories.getOrNull(sceneIndex)?.toLongOrNull()
            ?: directories.getOrNull(1)?.toLongOrNull()
    }

    /**
     * 解析文件名的日期或日期范围结束日期；未知日期返回 null。
     *
     * Parses a filename date or the end date of a date range; returns null for unknown dates.
     */
    private fun latestDateOf(name: String): LocalDate? {
        val start = datePrefixOf(name) ?: return null
        // 备份可能合并多天日志，按开始日期筛选会漏掉仍在时间窗内的记录。
        if (name.getOrNull(DATE_PREFIX_LENGTH) == '~') {
            val end = datePrefixOf(name.substring(DATE_PREFIX_LENGTH + 1)) ?: return null
            return maxOf(start, end)
        }
        return start
    }

    /** 解析文件名的 `yyyy-MM-dd` 日期前缀；对不上返回 null / Parses the `yyyy-MM-dd` date prefix of a file name; returns null when it does not match. */
    private fun datePrefixOf(name: String): LocalDate? = runCatching {
        if (name.length < DATE_PREFIX_LENGTH) return null
        LocalDate.parse(name.substring(0, DATE_PREFIX_LENGTH), DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()

    private const val DATE_PREFIX_LENGTH = 10
}
