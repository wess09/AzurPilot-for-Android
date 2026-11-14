package com.azurpilot.ghio.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurpilot.ghio.config.UserConfigurationStore
import com.azurpilot.ghio.i18n.AppLocales
import com.azurpilot.ghio.privileged.PermissionGateway
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页的 Activity 作用域会话
 *
 * 后端选择是 app 设置而非运行配置；主题/语言/日志清理都是只改观感与维护行为的外壳设置。
 * [uiState] 聚合两套持久栈（DataStore 的开关项 + JSON 仓的主题偏好）与特权通道状态；
 * 主线程 ViewModel，所有写经 [onIntent] 转到各自协程
 *
 * The Activity-scoped session of the settings page.
 *
 * The backend choice is an app setting, not a run configuration; theme /
 * language / log cleanup are shell settings that only affect appearance and
 * maintenance behavior. [uiState] aggregates both persistence stacks (the
 * DataStore toggles plus the JSON store's theme preference) and the
 * privileged-channel state; a main-thread ViewModel, with every write routed
 * through [onIntent] into its own coroutine.
 */
class SettingsViewModel(
    private val permissionGateway: PermissionGateway,
    private val appSettings: AppSettingsGateway,
    private val userConfigurationStore: UserConfigurationStore,
) : ViewModel() {

    /** 设置页聚合态；仅订阅期间保持活跃（5s 停留窗口），初始为默认值 / The settings aggregate state; active only while subscribed (5 s grace window), starting from the defaults. */
    val uiState: StateFlow<SettingsUiState> = combine(
        permissionGateway.state,
        userConfigurationStore.data,
        appSettings.autoCleanLogs,
        appSettings.keepAliveEnabled,
        appSettings.appLockEnabled,
    ) { remoteAccess, userConfig, autoCleanLogs, keepAliveEnabled, appLockEnabled ->
        SettingsUiState(
            remoteAccess = remoteAccess,
            themeMode = userConfig.themeMode,
            autoCleanLogs = autoCleanLogs,
            keepAliveEnabled = keepAliveEnabled,
            appLockEnabled = appLockEnabled,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    /**
     * 设置页意图入口；主线程调用，各分支自行落协程 / The settings intent entry
     * point; called on the main thread, each branch moves to its own coroutine.
     */
    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetBackend -> viewModelScope.launch {
                permissionGateway.setBackend(intent.backend)
            }

            is SettingsIntent.SetThemeMode -> viewModelScope.launch {
                userConfigurationStore.update { it.copy(themeMode = intent.mode) }
            }

            // 语言切换即时生效并触发 Activity 重建，不需要落协程
            is SettingsIntent.SetLanguage -> AppLocales.apply(intent.tag)

            is SettingsIntent.SetAutoCleanLogs -> viewModelScope.launch {
                appSettings.setAutoCleanLogs(intent.enabled)
            }

            is SettingsIntent.SetKeepAlive -> viewModelScope.launch {
                appSettings.setKeepAliveEnabled(intent.enabled)
            }

            is SettingsIntent.SetAppLock -> viewModelScope.launch {
                appSettings.setAppLockEnabled(intent.enabled)
            }
        }
    }
}
