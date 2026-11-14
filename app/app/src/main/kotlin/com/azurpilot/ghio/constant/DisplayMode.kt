package com.azurpilot.ghio.constant

/**
 * 保留虚拟屏特权接口的模式值。主屏选择由 App 内的无障碍桥处理。
 *
 * Retains the privileged interface's virtual-display mode value.
 * The app's accessibility bridge handles primary mode.
 */
object DisplayMode {
    /** 后台：建虚拟屏承载目标应用 / Background: create a virtual display hosting the target app. */
    const val BACKGROUND = 2
}
