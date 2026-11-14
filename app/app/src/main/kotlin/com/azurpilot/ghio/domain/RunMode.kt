package com.azurpilot.ghio.domain

/**
 * 定义目标应用的显示位置与控制通道。设置按枚举名保存，未知值回落到 [BACKGROUND]。
 *
 * Defines the target app's display and control transport. Settings persist the enum name;
 * unrecognized values fall back to [BACKGROUND].
 */
enum class RunMode {
    /** 通过无障碍控制主屏。 / Uses accessibility on the primary screen. */
    FOREGROUND,

    /** 后台：建虚拟屏承载目标应用 / Background: create a virtual display hosting the target app. */
    BACKGROUND,
}
