package com.azurpilot.ghio.log

import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 冷启动自动清理：7 天前的 AzurPilot 日志静默删除（session.log 截尾在 `ProotHost.cleanupStale`）
 *
 * 固定 7 天无配置项（开关是 `AppSettings.autoCleanLogs`，调用方判）：
 * - 按天日志 txt：按文件名 `yyyy-MM-dd` 前缀定日期，名字对不上的不动；
 * - `log/error/<毫秒时间戳>/`：按时间戳定日期，过期整文件夹删。
 *
 * crash/（10 份上限）与 app.log（4MB×5 滚动）自带天花板，不归这里管
 *
 * Cold-start auto-clean: silently deletes AzurPilot logs older than 7 days (session.log
 * truncation lives in `ProotHost.cleanupStale`).
 *
 * Fixed 7 days with no setting (the toggle is `AppSettings.autoCleanLogs`, checked by the
 * caller):
 * - daily log txts: dated by the `yyyy-MM-dd` file-name prefix; names that do not parse
 *   are left alone;
 * - `log/error/<millis timestamp>/`: dated by the timestamp; expired folders are deleted
 *   whole.
 *
 * crash/ (a 10-file cap) and app.log (a 4 MB × 5 rotation) have their own ceilings and
 * are not this class's concern.
 */
class LogCleaner(
    private val logSource: AzurPilotLogSource,
) {

    /**
     * 执行过期清理并 Timber.w 汇总删除量；调用方必须包 runCatching
     *
     * Runs the expiry sweep and logs a Timber.w summary of what was removed; callers
     * must wrap in runCatching.
     */
    fun cleanOutdated() {
        val cutoffDay = LocalDate.now().minusDays(KEEP_DAYS)
        val cutoffMillis = cutoffDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var deleted = 0
        var freedBytes = 0L

        logSource.dailyLogs().forEach { file ->
            val date = datePrefixOf(file.name) ?: return@forEach
            if (date.isBefore(cutoffDay)) {
                val size = file.length()
                if (file.delete()) {
                    deleted++
                    freedBytes += size
                }
            }
        }

        logSource.errorDirs().forEach { dir ->
            val ts = dir.name.toLongOrNull() ?: return@forEach
            if (ts < cutoffMillis) {
                val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (dir.deleteRecursively()) {
                    deleted++
                    freedBytes += size
                }
            }
        }

        Timber.w("LogCleaner: 过期日志清理完成，删除 %d 项，释放 %s", deleted, formatBytes(freedBytes))
    }

    /** 解析文件名的 `yyyy-MM-dd` 日期前缀；对不上返回 null / Parses the `yyyy-MM-dd` date prefix of a file name; returns null when it does not match. */
    private fun datePrefixOf(name: String): LocalDate? = runCatching {
        if (name.length < DATE_PREFIX_LENGTH) return null
        LocalDate.parse(name.substring(0, DATE_PREFIX_LENGTH), DATE_FORMAT)
    }.getOrNull()

    /** 删除量的人读格式（B / KB / MB）/ Formats the freed amount for humans (B / KB / MB). */
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / 1024 / 1024} MB"
    }

    private companion object {
        /** 保留天数：按天日志与错误现场共用 / Retention days, shared by daily logs and error scenes. */
        const val KEEP_DAYS = 7L

        /** `yyyy-MM-dd` 的长度 / The length of `yyyy-MM-dd`. */
        const val DATE_PREFIX_LENGTH = 10

        /** 与按天日志文件名前缀一致 / Matches the daily-log file-name prefix. */
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
