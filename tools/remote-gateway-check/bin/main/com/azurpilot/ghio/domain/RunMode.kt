package com.azurpilot.ghio.domain

import com.azurpilot.ghio.constant.DisplayMode

/**
 * 运行模式：目标应用跑在前台主屏还是后台虚拟屏
 *
 * 用户在设置里选择，持久化存枚举 name（解析失败回落 [BACKGROUND]）；[displayMode]
 * 把它映射到采集/注入层使用的 [DisplayMode] 常量。
 *
 * The run mode: whether the target app runs on the foreground primary display
 * or a background virtual display.
 *
 * Chosen in settings and persisted by enum name (parse failures fall back to
 * [BACKGROUND]); [displayMode] maps it to the [DisplayMode] constants used by
 * the capture / injection layer.
 *
 * @property displayMode 对应 [DisplayMode] 的整型常量 / The matching [DisplayMode]
 *   integer constant.
 */
enum class RunMode(val displayMode: Int) {
    /** 前台：采集并注入物理主屏，不建虚拟屏 / Foreground: capture and inject on the physical primary display, no virtual display. */
    FOREGROUND(DisplayMode.PRIMARY),

    /** 后台：建虚拟屏承载目标应用 / Background: create a virtual display hosting the target app. */
    BACKGROUND(DisplayMode.BACKGROUND),
}
