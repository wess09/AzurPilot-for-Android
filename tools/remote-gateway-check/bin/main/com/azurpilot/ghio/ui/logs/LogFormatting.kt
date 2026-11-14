package com.azurpilot.ghio.ui.logs

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志页的时间与体积格式化
 *
 * 固定 `yyyy-MM-dd HH:mm:ss` 而不跟 locale 走：这些页面是排障面，
 * 用户要拿这个时间去对 logcat 与 框架日志，两边格式一致才对得上
 *
 * Time and size formatting for the log pages.
 *
 * The fixed `yyyy-MM-dd HH:mm:ss` ignores the locale: these pages are
 * troubleshooting surfaces and users match the shown time against logcat and
 * framework logs, which only lines up when both sides use the same format.
 */

/**
 * 返回毫秒时间戳的 `yyyy-MM-dd HH:mm:ss` 形式 / Returns a millisecond timestamp
 * as `yyyy-MM-dd HH:mm:ss`.
 */
internal fun logTimestamp(atMillis: Long): String = TIMESTAMP.format(Date(atMillis))

/**
 * 返回人类可读的体积（B/KB/MB，逐档向下取整） / Returns a human-readable size
 * (B/KB/MB, floor-divided per tier).
 */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < KB -> "$bytes B"
    bytes < MB -> "${bytes / KB} KB"
    else -> "${bytes / MB} MB"
}

private const val KB = 1024L
private const val MB = KB * 1024

private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
