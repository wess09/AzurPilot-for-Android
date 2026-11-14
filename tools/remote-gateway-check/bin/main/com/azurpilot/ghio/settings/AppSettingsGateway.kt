package com.azurpilot.ghio.settings

import com.azurpilot.ghio.domain.OverlayControlMode
import com.azurpilot.ghio.domain.RunMode
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel 侧看得见的那部分 app 设置；实现是 [AppSettingsManager]
 *
 * 与 [com.azurpilot.ghio.privileged.PermissionGateway] 同一路子：让 VM 测试能塞 fake，
 * 而不必把 DataStore 一起拖进来。每个设置项一个热流 + 一个挂起写入口；流在主线程
 * 收集，写入口可在任意协程调用
 *
 * The slice of app settings visible to the ViewModel side; implemented by
 * [AppSettingsManager].
 *
 * Same approach as [com.azurpilot.ghio.privileged.PermissionGateway]: VM tests
 * can inject a fake without dragging DataStore along. One hot flow plus one
 * suspending writer per setting; flows are collected on the main thread,
 * writers may be called from any coroutine.
 */
interface AppSettingsGateway {
    /** 运行模式热流，值已从存储的 name 解析 / Hot flow of the run mode, already parsed from the stored name. */
    val runMode: StateFlow<RunMode>

    /** 写入运行模式 / Writes the run mode. */
    suspend fun setRunMode(mode: RunMode)

    /** 悬浮控制呼出方式热流 / Hot flow of the overlay control mode. */
    val overlayControlMode: StateFlow<OverlayControlMode>

    /** 写入悬浮控制呼出方式 / Writes the overlay control mode. */
    suspend fun setOverlayControlMode(mode: OverlayControlMode)

    /** 屏保开关热流 / Hot flow of the screensaver switch. */
    val screenSaverEnabled: StateFlow<Boolean>

    /** 写入屏保开关 / Writes the screensaver switch. */
    suspend fun setScreenSaverEnabled(enabled: Boolean)

    /** 息屏运行的亮度（0f–1f 范围中的安全子区间）。 / Screen-off window brightness. */
    val screenSaverBrightness: StateFlow<Float>

    /** 保存屏保亮度。 / Persists the screen-off window brightness. */
    suspend fun setScreenSaverBrightness(brightness: Float)

    /** 日志自动清理热流 / Hot flow of the auto-log-clean switch. */
    val autoCleanLogs: StateFlow<Boolean>

    /** 写入日志自动清理 / Writes the auto-log-clean switch. */
    suspend fun setAutoCleanLogs(enabled: Boolean)

    /** 保活系统热流 / Hot flow of the keep-alive switch. */
    val keepAliveEnabled: StateFlow<Boolean>

    /** 写入保活系统 / Writes the keep-alive switch. */
    suspend fun setKeepAliveEnabled(enabled: Boolean)

    /** 应用锁热流 / Hot flow of the app-lock switch. */
    val appLockEnabled: StateFlow<Boolean>

    /** 写入应用锁 / Writes the app-lock switch. */
    suspend fun setAppLockEnabled(enabled: Boolean)
}
