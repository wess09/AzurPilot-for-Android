package com.azurpilot.ghio.constant

/**
 * 声明采集与注入的目标屏，由用户在设置里选（[com.azurpilot.ghio.domain.RunMode]）
 *
 * PRIMARY 采主屏：不建虚拟屏，尺寸跟着设备旋转走，预览里的手动触摸不接管
 * （用户直接摸真屏即可）；
 * BACKGROUND 建虚拟屏：尺寸由 PI controller 的 display_* 推导，目标应用被拉到
 * 该屏上。
 *
 * Declares the capture and injection target display, chosen by the user in
 * settings ([com.azurpilot.ghio.domain.RunMode]).
 *
 * PRIMARY captures the primary display: no virtual display is created, the
 * size follows device rotation, and manual touches in the preview are not
 * hijacked (the user simply touches the real screen).
 *
 * BACKGROUND creates a virtual display: the size derives from the PI
 * controller's display_* values, and the target app is pulled onto that
 * display.
 */
object DisplayMode {
    /** 前台：采集并注入物理主屏 / Foreground: capture and inject on the physical primary display. */
    const val PRIMARY = 1

    /** 后台：建虚拟屏承载目标应用 / Background: create a virtual display hosting the target app. */
    const val BACKGROUND = 2
}
