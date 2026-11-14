package com.azurpilot.ghio.remote

import android.os.Build
import android.os.Environment
import android.os.Process
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.constant.AppFiles
import com.azurpilot.ghio.third.Ln
import java.io.File

/**
 * 特权服务进程的启动诊断 trace（Shizuku / root 用户服务进程侧）
 *
 * 卡死发生在 [RemoteServiceImpl] 构造阶段（init 块里的进程内初始化），早于 setup(userDir)，
 * 此时进程既没有 Context 也拿不到 App 传来的外部路径。FakeContext.getExternalFilesDir() 解析的是
 * com.android.shell 的目录而非本应用，不可用。
 *
 * 因此这里用 Environment.getExternalStorageDirectory() + BuildConfig.APPLICATION_ID 自行推导出与
 * App 侧 getExternalFilesDir(null) 相同的路径：
 * /storage/emulated/0/Android/data/{pkg}/files/debug/service_boot_debug.log
 * shell uid 对该目录可写（已实测写 {userDir}/debug/...），文件与 root_launch_debug.log 同目录、
 * 可被 App 直接读取，无需 /data/local/tmp、无需跨进程拷贝。
 *
 * 全程 runCatching 兜底：推导/写入失败也不影响主流程
 * （App 侧 service_bind_debug.log + logcat 仍可定位）。mark() 内部 synchronized，任意线程可调。
 *
 * Boot diagnostic trace for the privileged service process (Shizuku / root user-service side).
 *
 * The hang under diagnosis happens during [RemoteServiceImpl] construction (in-process init in
 * the init block), before setup(userDir); at that point the process has neither a Context nor
 * the external paths passed in by the app. FakeContext.getExternalFilesDir() resolves to
 * com.android.shell's directory rather than this app's, so it is unusable.
 *
 * Instead, the same path the app's getExternalFilesDir(null) would produce is derived here from
 * Environment.getExternalStorageDirectory() + BuildConfig.APPLICATION_ID:
 * /storage/emulated/0/Android/data/{pkg}/files/debug/service_boot_debug.log
 * The shell uid can write there (verified on device via {userDir}/debug/...), the file sits in
 * the same directory as root_launch_debug.log and is directly readable by the app — no
 * /data/local/tmp and no cross-process copying needed.
 *
 * Every step is wrapped in runCatching: a derivation or write failure never affects the main
 * flow (the app-side service_bind_debug.log plus logcat still localize the problem).
 * mark() synchronizes internally and may be called from any thread.
 */
object RemoteBootTrace {

    private const val FILE_NAME = "service_boot_debug.log"

    /** 超过上限即整文件删除重写，防失控增长 / The file is deleted and rewritten once it exceeds this cap */
    private const val MAX_BYTES = 256 * 1024L

    private val lock = Any()

    @Volatile
    private var headerWritten = false

    private val traceFile: File by lazy {
        File(
            Environment.getExternalStorageDirectory(),
            "Android/data/${BuildConfig.APPLICATION_ID}/files/${AppFiles.DEBUG_DIR}/$FILE_NAME"
        )
    }

    /**
     * trace 所在的诊断目录；与 App 侧 [com.azurpilot.ghio.constant.AppPaths.DEBUG_DIR]
     * 指向同一处。供进程内其它落盘诊断（如 [com.azurpilot.ghio.third.Ln] 的文件 sink）
     * 复用同一条已验证可写的路径推导。
     *
     * The diagnostic directory holding the trace file; the same directory the app side
     * sees as [com.azurpilot.ghio.constant.AppPaths.DEBUG_DIR]. Lets other in-process
     * file diagnostics (the [com.azurpilot.ghio.third.Ln] file sink, say) reuse this
     * verified-writable path derivation.
     */
    val debugDir: File by lazy { traceFile.parentFile ?: File(".") }

    /**
     * 记一笔启动阶段 trace：先落盘，再镜像到 logcat
     *
     * 首次写入先补一行头信息（pid / 机型 / ABI / 时间戳），后续只追加数据行；
     * 文件超 [MAX_BYTES] 时整体删除重建。落盘任何失败都被吞掉，绝不向上抛。
     *
     * Records one boot-stage trace entry: appended to the file first, then mirrored to logcat.
     *
     * The first write adds a header line (pid / model / ABI / timestamp) and later marks append
     * data lines only. When the file exceeds [MAX_BYTES] it is deleted and rebuilt. Any write
     * failure is swallowed — this never throws.
     *
     * @param stage 阶段名（如 CTOR_START）/ stage name (e.g. CTOR_START)
     * @param msg 附加信息，可空 / optional detail message
     */
    fun mark(stage: String, msg: String = "") {
        synchronized(lock) {
            runCatching {
                val file = traceFile
                if (file.exists() && file.length() > MAX_BYTES) {
                    file.delete()
                    headerWritten = false
                }
                if (!headerWritten) {
                    file.parentFile?.mkdirs()
                    file.appendText(
                        "==== service boot pid=${Process.myPid()} ${Build.MANUFACTURER} ${Build.MODEL} " +
                                "api=${Build.VERSION.SDK_INT} abi=${
                                    Build.SUPPORTED_ABIS.joinToString(
                                        ","
                                    )
                                } " +
                                "t=${System.currentTimeMillis()} ====\n"
                    )
                    headerWritten = true
                }
                val line = if (msg.isEmpty()) {
                    "${System.currentTimeMillis()}  $stage\n"
                } else {
                    "${System.currentTimeMillis()}  $stage  $msg\n"
                }
                file.appendText(line)
            }
        }
        // 同时进 logcat / root 的 stderr 日志（Ln 写 FileDescriptor.out/err）。
        Ln.i("[BOOT] $stage${if (msg.isEmpty()) "" else " $msg"}")
    }
}
