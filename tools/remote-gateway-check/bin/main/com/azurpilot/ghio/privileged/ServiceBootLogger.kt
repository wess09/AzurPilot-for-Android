package com.azurpilot.ghio.privileged

import android.os.Build
import android.os.Process
import com.azurpilot.ghio.constant.AppPaths
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记录远程服务绑定链路时间线的诊断日志（App 进程侧）
 *
 * 写入 {externalFilesDir}/debug/service_bind_debug.log（与 root_launch_debug.log 同目录），
 * 按时间线记录 bind → CONNECTING → onServiceConnected → BINDER_CONNECTED，以及 onError /
 * binderDied / getInstance 超时。
 *
 * 服务进程在启动阶段静默死亡、binder 永不回投时，文件会停在 CONNECTING 行而无任何终态行，
 * 并由看门狗补一条 STUCK 标记，直接定位「流程卡在 IPC 边界、服务进程未起来」——再配合
 * 服务进程侧的 service_boot_debug.log 即可判断是 native 加载崩溃还是连接后才死亡。
 *
 * 设计为 object：RemoteServiceManager / ShizukuRemoteServiceConnector 均为单例 object，
 * 统一静态访问，不必把引用穿透各处。init() 前调用一律 no-op；路径取 [AppPaths.DEBUG_DIR]。
 *
 * Timeline diagnostic log for the remote-service binding chain (app-process
 * side).
 *
 * Writes to {externalFilesDir}/debug/service_bind_debug.log (same directory as
 * root_launch_debug.log), recording the bind → CONNECTING →
 * onServiceConnected → BINDER_CONNECTED timeline plus onError / binderDied /
 * getInstance timeouts.
 *
 * When the service process dies silently during startup and the binder never
 * comes back, the file ends at the CONNECTING line with no terminal line, and
 * the watchdog appends a STUCK marker — pinpointing "wedged at the IPC
 * boundary, service process never came up". Cross-checking the service
 * process's own service_boot_debug.log then tells a native-load crash from a
 * death after connecting.
 *
 * An object on purpose: RemoteServiceManager / ShizukuRemoteServiceConnector
 * are singleton objects too, so static access avoids threading a reference
 * through everything. Calls before init() are no-ops; the path comes from
 * [AppPaths.DEBUG_DIR].
 */
object ServiceBootLogger {

    private const val FILE_NAME = "service_bind_debug.log"

    /**
     * 单文件上限；超过即滚动为 .1（只留一代），足够装下多轮失败时间线又不刷爆磁盘
     *
     * Per-file cap; exceeded means rotate to .1 (one generation kept) — roomy
     * enough for several failure timelines without ballooning disk usage.
     */
    private const val MAX_BYTES = 512 * 1024L

    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    @Volatile
    private var debugDir: File? = null

    /**
     * 建诊断目录并写入 SESSION 行（pid / 机型 / API 级别），作为本轮时间线的起点
     *
     * Creates the diagnostic directory and writes the SESSION line (pid /
     * device / API level), starting this session's timeline.
     */
    fun init() {
        synchronized(lock) {
            val dir = AppPaths.DEBUG_DIR
            // 磁盘只读等极端情况建不了目录；诊断不强求，失败即放弃写盘不影响主流程
            runCatching { dir.mkdirs() }
            debugDir = dir
        }
        event(
            "SESSION",
            "app start pid=${Process.myPid()} device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}"
        )
    }

    /**
     * 追加一行时间线；init() 前为 no-op，写盘失败只吞掉不抛
     *
     * Appends one timeline line; a no-op before init(), and write failures are
     * swallowed, never thrown.
     */
    fun event(stage: String, msg: String = "") {
        val dir = debugDir ?: return
        synchronized(lock) {
            runCatching {
                val file = File(dir, FILE_NAME)
                rotateIfNeeded(file, dir)
                val time = Instant.now().atZone(ZoneId.systemDefault()).format(timeFmt)
                val line = if (msg.isEmpty()) "$time  $stage\n" else "$time  $stage  $msg\n"
                file.appendText(line)
            }.onFailure { Timber.w(it, "ServiceBootLogger write failed") }
        }
    }

    /**
     * 超 [MAX_BYTES] 时滚动为 .1（只留一代）；须持 [lock] 调用
     *
     * Rotates to .1 past [MAX_BYTES] (one generation kept); call with
     * [lock] held.
     */
    private fun rotateIfNeeded(file: File, dir: File) {
        if (file.exists() && file.length() > MAX_BYTES) {
            val bak = File(dir, "$FILE_NAME.1")
            if (bak.exists()) bak.delete()
            file.renameTo(bak)
        }
    }
}
