package com.azurpilot.ghio.constant

import com.azurpilot.ghio.BuildConfig
import java.io.File

/**
 * 收口 /data/local/tmp 下的固定路径
 *
 * shell 与 root 身份都属主可写可执行；外部私有目录挂 noexec、app 私有目录
 * shell 进不去，跨进程标志文件只能落这里。
 *
 * Fixed paths under /data/local/tmp.
 *
 * Both shell and root identities own and can write/execute here; the external
 * private directory is mounted noexec and app-private storage is closed to
 * shell, so cross-process flag files have nowhere else to land.
 */
object ShellDirs {
    private const val BASE = "/data/local/tmp"
    private const val PKG = BuildConfig.APPLICATION_ID

    /** 屏幕电源状态标志，PowerController 跨进程读写 / The screen power-state flag, read/written across processes by PowerController. */
    val POWER_OFF_FLAG = File("$BASE/azurpilot_power_off_flag_$PKG")

    /** 强制分辨率状态标志，ScreenManager 跨进程读写 / The forced-resolution state flag, read/written across processes by ScreenManager. */
    val SCREEN_FLAG = File("$BASE/azurpilot_screen_flag_$PKG")
}
