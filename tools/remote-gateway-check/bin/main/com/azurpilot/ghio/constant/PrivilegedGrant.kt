package com.azurpilot.ghio.constant

/**
 * 特权进程代授的权限位；app 侧与特权进程侧共用
 *
 * 位值只增不改，与 AIDL 的 transaction id 同理：app 升级后旧特权进程可能仍存活。
 *
 * 集合对齐 参考实现 的 PERM_ALL：特权进程上线即把全集代授一遍，不再按运行模式
 * 挑——用不上的位代授是空操作，少授反而会在用户切模式时漏掉某一项。
 *
 * Permission bits granted on the app's behalf by the privileged process;
 * shared by both the app side and the privileged side.
 *
 * Bit values only ever grow, for the same reason as AIDL transaction ids: an
 * older privileged process may still be alive after an app upgrade.
 *
 * The set aligns with the reference implementation's PERM_ALL: the privileged
 * process grants the full set as soon as it comes up, unfiltered by run mode —
 * granting unused bits is a no-op, while granting fewer would drop one when
 * the user switches modes.
 */
object PrivilegedGrant {
    /** 通知权限 / Notification permission. */
    const val NOTIFICATION = 1 shl 0

    /** 电池优化豁免 / Battery-optimization exemption. */
    const val BATTERY = 1 shl 1

    /** 后台不受限：standby bucket、bg-restriction、AppOps 与 Phantom Process Killer 一并处理 / Unrestricted background: standby bucket, bg-restriction, AppOps, and the Phantom Process Killer all handled. */
    const val BACKGROUND = 1 shl 2

    /** 悬浮窗（OP_SYSTEM_ALERT_WINDOW）；前台控制层与后台屏保都要 / Overlay windows (OP_SYSTEM_ALERT_WINDOW); needed by both the foreground control layer and the background screensaver. */
    const val OVERLAY = 1 shl 3

    /**
     * 无障碍：直接写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
     *
     * 服务 id 是 app 侧组件名，特权进程拼不出来，所以由 RemoteServiceImpl 直接引用常量
     *
     * Accessibility: writes `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
     * directly.
     *
     * The service id is an app-side component name the privileged process
     * cannot assemble, so RemoteServiceImpl references the constant directly.
     */
    const val ACCESSIBILITY = 1 shl 4

    /** 存储访问（OP_MANAGE_EXTERNAL_STORAGE；旧版回退读写外部存储）/ Storage access (OP_MANAGE_EXTERNAL_STORAGE; older releases fall back to legacy external-storage R/W). */
    const val STORAGE = 1 shl 5

    /** 全集；特权进程上线即全代授，对齐 参考实现 / The full set; granted wholesale when the privileged process comes up, per the reference implementation. */
    const val ALL = NOTIFICATION or BATTERY or BACKGROUND or OVERLAY or ACCESSIBILITY or STORAGE
}
