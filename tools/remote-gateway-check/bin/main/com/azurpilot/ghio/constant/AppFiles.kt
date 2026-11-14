package com.azurpilot.ghio.constant

import android.content.Context
import java.io.File

/**
 * 返回外部私有目录（getExternalFilesDir(null)）下的固定子路径
 *
 * 特权进程是 shell 身份，只有这里既写得进又和 app 看到同一份。
 *
 * Fixed sub-paths under the external private directory
 * (getExternalFilesDir(null)).
 *
 * The privileged process runs as the shell identity; only this area is both
 * writable by it and seen identically by the app.
 *
 * 两棵子树：[LOG_DIR] 运行日志、[DEBUG_DIR] 启动诊断。
 *
 * Two trees: [LOG_DIR] for runtime logs and [DEBUG_DIR] for startup
 * diagnostics.
 */
object AppFiles {
    /** 运行日志树：Timber、定时触发记录、缓存帧 / The runtime-log tree: Timber output, periodic captures, cached frames. */
    const val LOG_DIR = "log"

    /** 启动诊断树：服务绑定 / 启动 trace、root launcher 输出 / The startup-diagnostic tree: service bind/start traces, root launcher output. */
    const val DEBUG_DIR = "debug"

    /** [LOG_DIR] 下：未捕获异常现场 / Under [LOG_DIR]: uncaught-crash scenes. */
    const val CRASH_DIR = "crash"
}

