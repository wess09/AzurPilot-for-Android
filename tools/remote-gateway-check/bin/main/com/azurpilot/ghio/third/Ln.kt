package com.azurpilot.ghio.third

import android.os.Process
import android.util.Log

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 同时写入 Android logger（logcat 可见）、进程标准输出/错误（终端直接可见）与可选文件
 * （导出日志包可见）的日志门面
 *
 * 特权进程（app_process 拉起，无 Koin/Timber）内的专用日志通道：app 侧按仓库惯例走
 * Timber，特权进程里只能用它。每条日志双路输出：`Log.v/d/i/w/e` 进 logcat（TAG 固定
 * [TAG]），同时带 [PREFIX] 前缀打到 stdout（v/i/d）或 stderr（w/e）。
 *
 * [initFileSink] 追加第三路：特权进程经 Shizuku/root 拉起，logcat 用户拿不到，标准流
 * 也无人接——不落盘的话「搬屏盯防为何没生效」这类问题在用户导出的日志包里永远看不到
 * （issue #8 复测排查的直接障碍）。sink 写到 App 的 debug 诊断目录，随 launcher_logs
 * zip 一起导出；init 前为 no-op，任何写盘失败都吞掉，绝不影响主流程。
 *
 * 从 scrcpy 服务端的 `third/Ln.java` 移植（Apache-2.0, Genymobile/scrcpy）。
 *
 * Logging facade that writes to the Android logger (visible in "adb logcat"), the
 * process's stdout/stderr (visible in the terminal directly), and an optional file
 * (visible in the exported log bundle).
 *
 * Dedicated to the privileged process (spawned via app_process, without Koin or
 * Timber): the app side logs through Timber per repo convention, the privileged
 * process must use this. Every entry is written twice: through `Log.v/d/i/w/e`
 * into logcat (with the fixed [TAG]) and prefixed with [PREFIX] to stdout
 * (v/i/d) or stderr (w/e).
 *
 * [initFileSink] adds a third leg: the privileged process is spawned via
 * Shizuku/root, its logcat is out of the user's reach and nobody reads the standard
 * streams — without a file, questions like "why did the repatriator never fire" are
 * invisible in the log bundle users export (the direct obstacle of the issue #8
 * retest triage). The sink writes into the app's debug directory and ships with the
 * launcher_logs zip; it is a no-op before init, and every write failure is swallowed.
 *
 * Ported from scrcpy's server `third/Ln.java` (Apache-2.0, Genymobile/scrcpy).
 */
object Ln {

    private const val TAG = "AzurPilot"
    private const val PREFIX = "[MC] "

    // 进程生命周期内复用同一组流，避免每次打日志都新建 PrintStream
    private val CONSOLE_OUT = PrintStream(FileOutputStream(FileDescriptor.out))
    private val CONSOLE_ERR = PrintStream(FileOutputStream(FileDescriptor.err))

    /**
     * 枚举日志级别，从细（VERBOSE）到粗（ERROR）
     *
     * [threshold] 只放行严重程度不低于它的级别。
     *
     * Enumerates log levels from finest (VERBOSE) to coarsest (ERROR).
     *
     * The threshold only lets through entries whose severity is at or above it.
     */
    enum class Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    private var threshold = Level.DEBUG

    // 文件 sink：initFileSink 前为 null；单锁串行化追加，binder 线程与各协程都会写
    private val sinkLock = Any()
    private var sinkFile: File? = null

    /** 文件 sink 的滚动上限；超限滚动为 .1（只留一代）/ The sink rotation cap; rotated to .1 (one generation kept) */
    private const val SINK_MAX_BYTES = 512 * 1024L

    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    /**
     * 启用文件 sink：此后每条日志（含历史格式）按行追加到
     * `{dir}/remote_process_debug.log`，带时间戳与级别，超限滚动为 .1
     *
     * 幂等：重复调用换目标文件（同一目录则等效刷新）。任何参数异常按无 sink 处理。
     *
     * Enables the file sink: every entry afterwards is appended to
     * `{dir}/remote_process_debug.log` with a timestamp and level, rotated to .1 past
     * the cap.
     *
     * Idempotent: calling again retargets the file (same directory = effectively a
     * refresh). Bad arguments mean "no sink".
     */
    fun initFileSink(dir: File?) {
        if (dir == null) return
        synchronized(sinkLock) {
            runCatching { dir.mkdirs() }
            sinkFile = File(dir, "remote_process_debug.log")
            // sink 自身的启用/换向记录进文件，方便对齐会话边界
            appendSinkLocked(Level.INFO, "file sink enabled (pid=${Process.myPid()})")
        }
    }

    /**
     * 把 System.out/System.err 整体替换为空流，后续一切写标准流的输出都被丢弃
     *
     * 框架/第三方代码可能直接往标准流里写内容，在终端里产生无法归类的噪音；
     * scrcpy 上游同样"聊胜于无"地屏蔽。应在进程启动早期调用。注意 [CONSOLE_OUT] /
     * [CONSOLE_ERR] 持有原始 FileDescriptor 流，本调用不影响 Ln 自身的输出。
     *
     * Replaces System.out/System.err with null streams, discarding everything
     * written to the standard streams afterwards.
     *
     * Framework and third-party code may write straight to the standard streams
     * and pollute the terminal with unattributable noise; upstream scrcpy does
     * the same as a "better than nothing" measure. Call early in the process
     * startup. Note that [CONSOLE_OUT] / [CONSOLE_ERR] hold the original
     * FileDescriptor streams, so this call does not affect Ln's own output.
     */
    fun disableSystemStreams() {
        val nullStream = PrintStream(NullOutputStream())
        System.setOut(nullStream)
        System.setErr(nullStream)
    }

    /**
     * 查询某级别当前是否会被输出 / Returns whether the given level currently passes the threshold.
     */
    fun isEnabled(level: Level): Boolean {
        return level.ordinal >= threshold.ordinal
    }

    /** 记录 VERBOSE 级日志（logcat + stdout + sink）/ Logs a VERBOSE entry (logcat, stdout, and sink). */
    fun v(message: String) {
        if (isEnabled(Level.VERBOSE)) {
            Log.v(TAG, message)
            CONSOLE_OUT.print(PREFIX + "VERBOSE: " + message + '\n')
            appendSinkLocked(Level.VERBOSE, message)
        }
    }

    /** 记录 DEBUG 级日志（logcat + stdout + sink）/ Logs a DEBUG entry (logcat, stdout, and sink). */
    fun d(message: String) {
        if (isEnabled(Level.DEBUG)) {
            Log.d(TAG, message)
            CONSOLE_OUT.print(PREFIX + "DEBUG: " + message + '\n')
            appendSinkLocked(Level.DEBUG, message)
        }
    }

    /** 记录 INFO 级日志（logcat + stdout + sink）/ Logs an INFO entry (logcat, stdout, and sink). */
    fun i(message: String) {
        if (isEnabled(Level.INFO)) {
            Log.i(TAG, message)
            CONSOLE_OUT.print(PREFIX + "INFO: " + message + '\n')
            appendSinkLocked(Level.INFO, message)
        }
    }

    /**
     * 记录 WARN 级日志（logcat + stderr + sink，异常栈随 [throwable] 走 stderr 与 sink）
     *
     * Logs a WARN entry (logcat, stderr, and sink; the stack trace of [throwable] goes
     * to stderr and the sink too).
     */
    fun w(message: String, throwable: Throwable?) {
        if (isEnabled(Level.WARN)) {
            Log.w(TAG, message, throwable)
            CONSOLE_ERR.print(PREFIX + "WARN: " + message + '\n')
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE_ERR)
            }
            appendSinkLocked(Level.WARN, message, throwable)
        }
    }

    /** 记录 WARN 级日志（无异常）/ Logs a WARN entry without a throwable. */
    fun w(message: String) {
        w(message, null)
    }

    /**
     * 记录 ERROR 级日志（logcat + stderr + sink，异常栈随 [throwable] 走 stderr 与 sink）
     *
     * Logs an ERROR entry (logcat, stderr, and sink; the stack trace of [throwable]
     * goes to stderr and the sink too).
     */
    fun e(message: String, throwable: Throwable?) {
        if (isEnabled(Level.ERROR)) {
            Log.e(TAG, message, throwable)
            CONSOLE_ERR.print(PREFIX + "ERROR: " + message + '\n')
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE_ERR)
            }
            appendSinkLocked(Level.ERROR, message, throwable)
        }
    }

    /** 记录 ERROR 级日志（无异常）/ Logs an ERROR entry without a throwable. */
    fun e(message: String) {
        e(message, null)
    }

    /**
     * 追加一条到文件 sink；须持 [sinkLock]。失败只吞掉——诊断通道永远不能反噬主流程
     *
     * Appends one entry to the file sink; call with [sinkLock] held. Failures are
     * swallowed — a diagnostic channel must never bite the main flow.
     */
    private fun appendSinkLocked(level: Level, message: String, throwable: Throwable? = null) {
        val file = sinkFile ?: return
        runCatching {
            if (file.exists() && file.length() > SINK_MAX_BYTES) {
                val bak = File(file.parentFile, file.name + ".1")
                if (bak.exists()) bak.delete()
                file.renameTo(bak)
            }
            val time = Instant.now().atZone(ZoneId.systemDefault()).format(timeFmt)
            file.appendText("$time  $level  $message\n")
            if (throwable != null) {
                // 栈要完整现场，直接落 printStackTrace 的输出（logcat 侧同样如此）
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                file.appendText(sw.toString() + '\n')
            }
        }
    }

    /** 吞掉一切写入的黑洞流，供 [disableSystemStreams] 挂到 System.out/err / Black-hole stream swallowing all writes, attached to System.out/err by [disableSystemStreams]. */
    private class NullOutputStream : OutputStream() {
        override fun write(b: ByteArray) {
            // ignore
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            // ignore
        }

        override fun write(b: Int) {
            // ignore
        }
    }
}
