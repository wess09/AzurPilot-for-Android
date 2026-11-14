package com.azurpilot.ghio.log

import android.os.Build
import android.util.Log
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.constant.AppFiles
import com.azurpilot.ghio.constant.AppPaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未捕获异常落盘
 *
 * logcat 的环形缓冲装不下从崩溃到用户来反馈之间那段时间，而崩溃现场恰恰只有一次机会。
 * 落在 `log/crash/`（`crash_<yyyyMMdd_HHmmss>.txt`），保留最近 [MAX_FILES] 份，
 * 跟着导出包一起交出去
 *
 * 崩溃路径上先写本报告，再交还 [previous]（安装前的默认处理器）继续走系统崩溃流程，
 * 不吃掉链路
 *
 * Uncaught-exception dumper.
 *
 * logcat's ring buffer cannot span the gap between the crash and the moment the user
 * reports it, and the crash scene gets exactly one chance. Reports land in `log/crash/`
 * (`crash_<yyyyMMdd_HHmmss>.txt`), the most recent [MAX_FILES] are kept, and they travel
 * with the export bundle.
 *
 * On the crash path this writes its report first, then hands over to [previous] (the
 * handler installed before this one) so the system crash flow keeps running.
 *
 */
class CrashHandler : Thread.UncaughtExceptionHandler {
    private val logDir = File(AppPaths.LOG_DIR, AppFiles.CRASH_DIR)

    /** 安装前的默认处理器，崩溃写盘后交还 / The handler installed before this one, invoked after the report is written. */
    private var previous: Thread.UncaughtExceptionHandler? = null

    /**
     * 安装为全局默认未捕获异常处理器并修剪过期报告；应在 Application 启动早期调用一次
     *
     * Installs this as the default uncaught-exception handler and prunes stale
     * reports; call once early in Application startup.
     */
    fun install() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        pruneOld()
    }

    /**
     * 崩溃回调：写一次现场报告后交还 [previous]；运行在崩溃线程上
     *
     * The crash callback: writes one scene report then hands over to [previous];
     * runs on the crashing thread.
     */
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 这里已经在崩溃路径上，任何一步再抛都会把现场彻底吃掉
        runCatching { write(report(thread, throwable)) }
        previous?.uncaughtException(thread, throwable)
    }

    /** 拼崩溃报告：时间、线程、版本、设备、系统、ABI + 完整堆栈 / Builds the crash report: time, thread, version, device, OS, ABI + full stack. */
    private fun report(thread: Thread, throwable: Throwable): String = buildString {
        append("Time     : ").append(STAMP_READABLE.format(Date())).append('\n')
        append("Thread   : ").append(thread.name).append('\n')
        append("Version  : ").append(BuildConfig.VERSION_NAME)
        append(" (").append(BuildConfig.VERSION_CODE).append(") ")
        append(BuildConfig.BUILD_TYPE).append('\n')
        append("Device   : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append('\n')
        append("System   : Android ").append(Build.VERSION.RELEASE)
        append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("ABI      : ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append('\n')
        append(Log.getStackTraceString(throwable))
    }

    /** 落盘为 `crash_<时间戳>.txt` / Writes the report as `crash_<timestamp>.txt`. */
    private fun write(content: String) {
        val dir = logDir.apply { mkdirs() }
        File(dir, "crash_${STAMP_FILE.format(Date())}.txt").writeText(content)
    }

    /** 按 mtime 保留最近 [MAX_FILES] 份，其余删除；安装时调用一次 / Keeps the newest [MAX_FILES] reports by mtime and deletes the rest; called once on install. */
    private fun pruneOld() {
        runCatching {
            logDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FILES)
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        /** 崩溃报告保留份数 / Number of crash reports retained. */
        const val MAX_FILES = 10

        /** 文件名用紧凑格式、报告头用可读格式 / Compact format for file names, readable format for the report header. */
        val STAMP_FILE = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val STAMP_READABLE = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
