package com.azurpilot.ghio.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.azurpilot.ghio.di.AppCoroutineScope
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.service.RunForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import timber.log.Timber

/**
 * 桌面小组件用户点击操作广播接收器
 *
 * 核心安全机制：在用户点击小组件的广播接收窗口内第一时间拉起 [RunForegroundService]，
 * 享有 Android 12+ 后台启动前台服务的临时豁免权，彻底避免 ForegroundServiceStartNotAllowedException。
 *
 * 生命周期仅限 onReceive：耗时工作全部转交协程（Koin 的 [AppCoroutineScope]，
 * Koin 未就绪时退回临时 Main 协程域）；[ACTION_REFRESH] 只重发状态，不触发动作
 *
 * The broadcast receiver handling user click actions from the AppWidgets.
 *
 * Core safety mechanism: starts [RunForegroundService] immediately inside the
 * user-touch broadcast window to use the Android 12+ temporary exemption for
 * starting a foreground service from the background, avoiding
 * ForegroundServiceStartNotAllowedException entirely.
 *
 * Its lifetime is bounded by onReceive: all slow work is handed to a
 * coroutine (Koin's [AppCoroutineScope], falling back to a temporary Main
 * scope when Koin is not ready); [ACTION_REFRESH] only republishes state and
 * triggers no action.
 */
class AzurPilotWidgetActionReceiver : BroadcastReceiver() {

    /** 分发小组件动作；仅处理 [ACTION_TOGGLE_RUNNER] 与 [ACTION_REFRESH] / Dispatches widget actions; only [ACTION_TOGGLE_RUNNER] and [ACTION_REFRESH] are handled. */
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.d("AzurPilotWidgetActionReceiver received action=%s", action)

        when (action) {
            ACTION_TOGGLE_RUNNER -> {
                handleToggle(context)
            }
            ACTION_REFRESH -> {
                AzurPilotWidgetUpdater.updateAll(context)
            }
        }
    }

    /**
     * 处理启停切换：运行中则停，未运行则走「提 FGS → 等 PRoot 就绪 → 启动」链
     *
     * Handles the run toggle: stop when running, otherwise run the chain
     * "raise FGS → await PRoot readiness → start".
     *
     * Koin 缺失或依赖未注册时静默降级（只更新小组件），不抛出——
     * 广播接收器抛异常只会在 logcat 留噪音，用户侧毫无反馈
     *
     * With Koin missing or dependencies unregistered it degrades silently
     * (only refreshing the widget) instead of throwing — a receiver exception
     * would leave nothing but logcat noise for the user.
     */
    private fun handleToggle(context: Context) {
        val koin = GlobalContext.getOrNull()
        val runController = runCatching { koin?.get<AzurPilotRunController>() }.getOrNull()
        val prootHost = runCatching { koin?.get<ProotHost>() }.getOrNull()

        val isAlive = runController?.state?.value?.runnerAlive ?: false

        if (isAlive) {
            // 正在运行 -> 停止
            Timber.i("Widget action: Stopping AzurPilot runner")
            runController.stopRunner()
            AzurPilotWidgetUpdater.updateAll(context)
        } else {
            // 未运行 -> 启动
            Timber.i("Widget action: Starting AzurPilot runner")
            // 关键：第一时间以用户交互豁免提升为前台服务
            RunForegroundService.start(context)

            val scope = runCatching { koin?.get<CoroutineScope>(named<AppCoroutineScope>()) }.getOrNull()
                ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)

            scope.launch {
                // 乐观更新小组件为准备中
                AzurPilotWidgetUpdater.updateAll(context)

                // 确保 Proot 容器拉起并就绪
                prootHost?.ensureStarted()

                if (runController != null) {
                    // 等待薄接口可达（最多等待 30 秒）
                    withTimeoutOrNull(30_000L) {
                        runController.state.first { it.reachable }
                    }
                    runController.startRunner()
                }

                // 启动后稍作延迟更新
                delay(1000L)
                AzurPilotWidgetUpdater.updateAll(context)
            }
        }
    }

    companion object {
        /** 一键启停动作 / The toggle-runner action. */
        const val ACTION_TOGGLE_RUNNER = "com.azurpilot.ghio.action.WIDGET_TOGGLE_RUNNER"

        /** 刷新全部小组件动作 / The refresh-all-widgets action. */
        const val ACTION_REFRESH = "com.azurpilot.ghio.action.WIDGET_REFRESH"
    }
}
