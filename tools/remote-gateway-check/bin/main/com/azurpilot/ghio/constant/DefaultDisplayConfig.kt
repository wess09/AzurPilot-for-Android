package com.azurpilot.ghio.constant

/**
 * 钉死虚拟显示器的参数
 *
 * AzurPilot 桥配置（screencap 1280×720）钉死这套尺寸，改分辨率要连桥协议与
 * guest 侧校验一起动，故不做用户可选项。
 *
 * Pins the virtual display's parameters.
 *
 * The AzurPilot bridge configuration (screencap at 1280×720) is locked to this
 * size set; changing the resolution would drag the bridge protocol and guest
 * side validation along with it, so it is not offered as a user option.
 */
object DefaultDisplayConfig {
    /** 建屏时的名字，只在 dumpsys 里可见 / Name given at display creation; only visible in dumpsys. */
    const val VD_NAME = "AzurPilotVirtualDisplay"

    /** 「没有虚拟屏」的哨兵 displayId，跨进程回传用 / Sentinel displayId meaning "no virtual display", for cross-process returns. */
    const val DISPLAY_NONE = -1

    /** 虚拟屏宽（像素）/ Virtual display width in pixels. */
    const val WIDTH = 1280

    /** 虚拟屏高（像素）/ Virtual display height in pixels. */
    const val HEIGHT = 720

    /** 无法读取设备 DPI 时的密度回退值。 / Density fallback when the device DPI cannot be read. */
    const val DPI = 160
}
