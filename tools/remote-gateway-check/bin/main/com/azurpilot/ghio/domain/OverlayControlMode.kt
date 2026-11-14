package com.azurpilot.ghio.domain

/**
 * 悬浮控制的呼出方式：决定用户以哪种手段唤起游戏内控制界面
 *
 * 由设置持久化（存枚举 name），[com.azurpilot.ghio.overlay.OverlayController]
 * 据此决定常驻元素；两种模式互斥，切换时旧模式先收起再上新模式。
 *
 * How the in-app overlay control is summoned: decides which means the user
 * employs to bring up the in-game control surface.
 *
 * Persisted by name in settings; [com.azurpilot.ghio.overlay.OverlayController]
 * picks the resident element accordingly. The two modes are mutually
 * exclusive; on a switch the old one is dismissed before the new one shows.
 */
enum class OverlayControlMode {
    /** 无障碍手势：同时按音量 ± 呼出，无常驻悬浮元素 / Accessibility gesture: press Volume +/- together to summon; no resident floating element. */
    ACCESSIBILITY,

    /** 常驻悬浮球：需要悬浮窗权限（SYSTEM_ALERT_WINDOW）/ Resident floating ball: requires the overlay permission (SYSTEM_ALERT_WINDOW). */
    FLOAT_BALL,
}
