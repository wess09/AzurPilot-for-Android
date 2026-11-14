package com.azurpilot.ghio.settings

import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.domain.ThemeMode
import com.azurpilot.ghio.privileged.RemoteAccessState

/**
 * 设置页聚合态
 *
 * 后端的写走 PermissionGateway.setBackend（带 unbind 副作用），不直接落 AppSettings——
 * 跳过 unbind 会连着错的特权进程
 *
 * The aggregated state of the settings page.
 *
 * Backend writes go through PermissionGateway.setBackend (which carries the
 * unbind side effect) and never land directly in AppSettings — skipping the
 * unbind would leave the wrong privileged process attached.
 */
data class SettingsUiState(
    /** 特权通道连接/授权状态 / The privileged channel's connection and grant state. */
    val remoteAccess: RemoteAccessState = RemoteAccessState(),
    val themeMode: ThemeMode = ThemeMode.System,
    val autoCleanLogs: Boolean = true,
    val keepAliveEnabled: Boolean = false,
    val appLockEnabled: Boolean = true,
)

/**
 * 设置页的用户意图；由 [SettingsViewModel] 解释执行
 *
 * The user intents of the settings page, interpreted and executed by
 * [SettingsViewModel].
 */
sealed interface SettingsIntent {
    /**
     * 切换 Shizuku / Root 后端；落到 AppSettings.startupBackend 并断开当前特权进程
     *
     * Switches the Shizuku / Root backend; lands in AppSettings.startupBackend
     * and disconnects the current privileged process.
     */
    data class SetBackend(val backend: RemoteBackend) : SettingsIntent

    /** 切换主题模式 / Switches the theme mode. */
    data class SetThemeMode(val mode: ThemeMode) : SettingsIntent

    /**
     * 切换界面语言；null 恢复跟随系统；切换后 Activity 重建
     *
     * Switches the UI language; null restores follow-system. The Activity
     * recreates after the switch.
     */
    data class SetLanguage(val tag: String?) : SettingsIntent

    /** 切换日志自动清理 / Toggles the auto log clean. */
    data class SetAutoCleanLogs(val enabled: Boolean) : SettingsIntent

    /** 切换激进后台保活系统 / Toggles the persistent keep-alive system. */
    data class SetKeepAlive(val enabled: Boolean) : SettingsIntent

    /** 切换应用锁屏保护 / Toggles the global app lock screen protection. */
    data class SetAppLock(val enabled: Boolean) : SettingsIntent
}
