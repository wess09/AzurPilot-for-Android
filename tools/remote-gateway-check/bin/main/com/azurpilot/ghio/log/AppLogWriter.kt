package com.azurpilot.ghio.log

import android.os.Build
import android.util.Log
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.constant.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 应用侧日志文件写入器：把 Timber 转发来的日志异步落盘到 [AppPaths.LOG_DIR] 的
 * `app.log`，并按大小滚动保留 `app.log` + `app.1.log`..`app.4.log` 共 [MAX_FILES] 份、
 * 单份上限 [MAX_FILE_BYTES]（4 MB）。
 *
 * 写入模型：调用方 [submit] 只做非阻塞 trySend（队列满则丢弃该条，绝不反压 UI 线程），
 * 专用单线程协程（limitedParallelism(1)）逐批取出、攒批 flush——日志必须串行落盘才能
 * 保证时间线有序。自身出错时直接走 android.util.Log：再走 Timber 会回到本 writer
 * 形成自激递归。
 *
 * The app-side log file writer: persists logs forwarded from Timber to `app.log` under
 * [AppPaths.LOG_DIR] asynchronously, rotating by size across [MAX_FILES] files —
 * `app.log` plus `app.1.log`..`app.4.log`, capped at [MAX_FILE_BYTES] (4 MB) each.
 *
 * Write model: [submit] performs a non-blocking trySend only (the entry is dropped when
 * the queue is full, never back-pressuring the UI thread), while a dedicated single-thread
 * coroutine (limitedParallelism(1)) drains entries in batches with one flush per batch —
 * serial disk writes are what keep the log timeline ordered. On internal failure it logs
 * via android.util.Log directly: routing through Timber would re-enter this writer and
 * recurse.
 */
class AppLogWriter {

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.IO.limitedParallelism(1))
    private val channel = Channel<String>(capacity = QUEUE_CAPACITY)

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private var stream: BufferedOutputStream? = null

    /** -1 表示还没打开过文件，下次写入时从磁盘读实际大小 / -1 means no file opened yet; the real size is read from disk on the next write. */
    private var writtenBytes: Long = -1L

    init {
        scope.launch {
            for (entry in channel) {
                append(entry)
                // 逐条 flush 等于每行一次 write 系统调用，下面那个 BufferedOutputStream 就白设了
                while (true) append(channel.tryReceive().getOrNull() ?: break)
                flush()
            }
        }
    }

    /**
     * 提交一条日志进写入队列；非阻塞，队列满时静默丢弃
     *
     * Submits one log entry to the write queue; non-blocking, silently dropped when
     * the queue is full.
     */
    fun submit(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        channel.trySend(format(priority, tag, message, throwable))
    }

    /**
     * 写入一次会话启动头（时间、版本、设备、系统、ABI），用于在日志里分隔启动批次
     *
     * Writes one session startup banner (time, version, device, OS, ABI) delimiting
     * launch batches in the log.
     */
    fun setup() {
        channel.trySend(
            buildString {
                append("\n").append("=".repeat(60)).append("\n")
                append("Startup time : ").append(ZonedDateTime.now().format(timestampFormat))
                    .append("\n")
                append("Version     : ").append(BuildConfig.VERSION_NAME)
                append(" (").append(BuildConfig.VERSION_CODE).append(") ")
                append(BuildConfig.BUILD_TYPE).append("\n")
                append("Device     : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                    .append("\n")
                append("OS     : Android ").append(Build.VERSION.RELEASE)
                append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                append("ABI      : ").append(Build.SUPPORTED_ABIS.joinToString()).append("\n")
                append("=".repeat(60)).append("\n\n")
            },
        )
    }

    /** 追加一条日志；任何 IO 失败都关流降级，等待下次写入重新打开 / Appends one entry; any IO failure closes the stream to degrade, reopening on the next write. */
    private fun append(entry: String) {
        runCatching {
            rotateIfNeeded()
            val bytes = entry.toByteArray(Charsets.UTF_8)
            openStream().write(bytes)
            writtenBytes += bytes.size
        }.onFailure {
            Log.w(TAG, "Failed to write app log", it)
            closeStream()
        }
    }

    private fun flush() {
        runCatching { stream?.flush() }.onFailure {
            Log.w(TAG, "Failed to flush app log", it)
            closeStream()
        }
    }

    private fun openStream(): BufferedOutputStream = stream ?: BufferedOutputStream(
        FileOutputStream(file(), true),
        BUFFER_SIZE,
    ).also {
        stream = it
        writtenBytes = file().length()
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
        writtenBytes = -1L
    }

    /**
     * 超过 [MAX_FILE_BYTES] 时滚动：删最老的 app.4.log，其余依次后移一位，
     * 当前 app.log 改名为 app.1.log，再从空文件写起
     *
     * Rotates once [MAX_FILE_BYTES] is exceeded: the oldest app.4.log is deleted, the
     * rest shift by one, the current app.log is renamed to app.1.log, and writing
     * resumes from an empty file.
     */
    private fun rotateIfNeeded() {
        if (writtenBytes < 0) {
            val file = file()
            if (!file.exists()) return
            writtenBytes = file.length()
        }
        if (writtenBytes < MAX_FILE_BYTES) return

        closeStream()
        val dir = AppPaths.LOG_DIR
        runCatching {
            File(dir, rotatedName(MAX_FILES - 1)).delete()
            for (index in MAX_FILES - 2 downTo 1) {
                val from = File(dir, rotatedName(index))
                if (from.exists()) from.renameTo(File(dir, rotatedName(index + 1)))
            }
            file().renameTo(File(dir, rotatedName(1)))
        }
        writtenBytes = 0L
    }

    /**
     * 列出全部 app.log 滚动文件，按最后修改时间倒序；失败返回空列表
     *
     * Lists the whole app.log rotation series, newest first; returns an empty list
     * on failure.
     */
    fun listFiles(): List<File> = runCatching {
        AppPaths.LOG_DIR.listFiles()?.filter { it.isFile && FILE_PATTERN.matches(it.name) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
    }.getOrElse {
        Log.w(TAG, "Failed to list app logs", it)
        emptyList()
    }

    /**
     * 清空 app.log 滚动系列（关流后逐个删除），在写入协程上排队执行；
     * 返回的 Job 供调用方 join 后再重扫目录
     *
     * Clears the app.log rotation series (closing the stream then deleting each file),
     * queued on the write coroutine; the returned Job lets callers join it before
     * rescanning the directory.
     */
    fun purge(): Job = scope.launch {
        closeStream()
        runCatching { listFiles().forEach { it.delete() } }.onFailure {
            Log.w(
                TAG,
                "Failed to clear app logs",
                it
            )
        }
    }

    /** 当前日志文件（目录不存在则先建）/ The current log file (the directory is created first when missing). */
    private fun file(): File = File(AppPaths.LOG_DIR.apply { mkdirs() }, CURRENT_FILE_NAME)

    /** 第 index 份滚动文件名 / The rotated file name for the given index. */
    private fun rotatedName(index: Int): String = "app.$index.log"

    /** 拼一行 logcat 风格记录：时间 级别 tag: 消息（+堆栈）/ Formats one logcat-style line: time level tag: message (+stack). */
    private fun format(
        priority: Int, tag: String?, message: String, throwable: Throwable?
    ): String {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        return buildString {
            append("[").append(ZonedDateTime.now().format(timestampFormat)).append("] ")
            append(level).append(" ").append(tag ?: "-").append(": ").append(message).append("\n")
            throwable?.let { append(Log.getStackTraceString(it)).append("\n") }
        }
    }

    private companion object {
        const val TAG = "AppLogWriter"

        /** 当前活跃日志文件名 / The active log file name. */
        const val CURRENT_FILE_NAME = "app.log"

        /** 写缓冲 8 KB：与攒批 flush 配合摊薄系统调用 / 8 KB write buffer: amortizes syscalls together with batch flushing. */
        const val BUFFER_SIZE = 8 * 1024

        /** 单文件上限 4 MB：5 份共 20 MB，够覆盖数轮会话又不占满外部存储 / 4 MB per file: 20 MB across 5 files covers several sessions without filling external storage. */
        const val MAX_FILE_BYTES = 4L * 1024 * 1024

        /** 滚动保留的总份数（含 app.log 本身）/ Total files kept in the rotation (app.log itself included). */
        const val MAX_FILES = 5

        /** 写入队列容量；满后 submit 丢弃新条目——日志是尽力而为，不能反压调用方 / Write-queue capacity; when full, submit drops new entries — logs are best-effort and must never back-pressure callers. */
        const val QUEUE_CAPACITY = 512

        /** `app.log` 与 `app.<n>.log`；与 [rotatedName] 是一对，改一处要改两处 */
        val FILE_PATTERN = Regex("""app(\.\d+)?\.log""")
    }
}
