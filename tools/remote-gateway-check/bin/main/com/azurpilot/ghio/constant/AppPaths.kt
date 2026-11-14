package com.azurpilot.ghio.constant

import android.content.Context
import java.io.File

/**
 * App 进程运行期路径单例
 *
 * 进程级单例：[init] 在 Application.onCreate 一口气解析所有目录并定值，
 * 不保留 Context 引用；`init` 早于 Koin 就绪，因此与 RemoteBootTrace /
 * ServiceBootLogger 同属进程级全局，不进 DI。
 *
 * 外部存储不可用时 [init] 抛出 → app 启动即失败：PI / run / 日志全依赖
 * 外部私有目录，不可用就没法降级，崩在启动比 UI 进去后处处报错更直接。
 *
 * Process-level path singleton for the app process at runtime.
 *
 * [init] resolves every directory at once in Application.onCreate and pins the
 * values, retaining no Context reference; it runs before Koin is ready, so —
 * like RemoteBootTrace and ServiceBootLogger — this object stays out of DI as
 * a process-level global.
 *
 * When external storage is unavailable [init] throws and startup fails: PI,
 * run state, and logs all depend on the external private directory with no
 * possible degradation, and crashing at startup beats errors everywhere once
 * the UI is up.
 */
object AppPaths {
    /** 外部私有目录根，其余目录都挂在其下 / The external private root; every other directory hangs under it. */
    lateinit var ROOT: File
        private set

    /** 运行日志目录（[AppFiles.LOG_DIR]）/ The runtime-log directory ([AppFiles.LOG_DIR]). */
    lateinit var LOG_DIR: File
        private set

    /** 启动诊断目录（[AppFiles.DEBUG_DIR]）/ The startup-diagnostic directory ([AppFiles.DEBUG_DIR]). */
    lateinit var DEBUG_DIR: File
        private set

    /**
     * 解析全部目录；必须先于任何路径读取调用
     * Resolves every directory; must run before any path is read.
     *
     * @throws IllegalStateException 外部存储未挂载、拿不到外部私有目录时 /
     *   when the external storage is not mounted and the external private
     *   directory cannot be obtained
     */
    fun init(context: Context) {
        val root = checkNotNull(context.getExternalFilesDir(null)) {
            "The external private directory is unavailable (the external storage is not mounted)"
        }
        ROOT = root
        LOG_DIR = File(root, AppFiles.LOG_DIR)
        DEBUG_DIR = File(root, AppFiles.DEBUG_DIR)
    }
}
