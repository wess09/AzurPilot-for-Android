package com.azurpilot.ghio.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 收口 App 当前解析出的明暗档，供 Activity 之外的地方取用（悬浮窗、悬浮球）
 *
 * 这些地方不该自己去问系统：服务进程的 Configuration 可能停在服务创建那一刻，
 * 系统换深浅色时它不跟 Activity 一起刷新，照它取色就会出现「App 已经浅色、
 * 悬浮窗还是深色」这种对不上的情况。由 Activity 把最终结果播出来，别处只读。
 *
 * [darkTheme] 为 null = 本次进程里还没播过（App 尚未启动过），此时调用方按
 * 自己的兜底判断；一旦播过就以它为准。
 *
 * Publishes the app's currently resolved dark/light setting to places outside
 * an Activity (overlay windows, the floating ball).
 *
 * Those places must not ask the system themselves: a service process's
 * Configuration can be frozen at service creation and does not refresh with
 * the Activity when the system switches dark/light, so colors taken from it
 * produce mismatches like "the app already went light while the overlay stayed
 * dark". The Activity publishes the final result; everywhere else only reads.
 *
 * A null [darkTheme] means nothing has been published yet in this process (the
 * app has not started), and callers apply their own fallback; once published,
 * the value is authoritative.
 */
object AppThemeState {

    private val _darkTheme = MutableStateFlow<Boolean?>(null)

    /** 当前明暗档；null = 尚未播报过，调用方自兜底 / The current dark/light setting; null until first publish (callers apply their own fallback). */
    val darkTheme: StateFlow<Boolean?> = _darkTheme.asStateFlow()

    /**
     * 播报最新明暗档；由 Activity 的主题回调在主线程调用
     * Publishes the latest dark/light setting; called on the main thread from
     * the Activity's theme callback.
     */
    fun publish(dark: Boolean) {
        _darkTheme.value = dark
    }
}
