package com.azurpilot.ghio.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.service.HostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * 桌面小组件状态更新与分发管理器
 *
 * 负责提取后台环境与调度器的最新运行状态，缓存快照并分发刷新至所有注册的桌面小组件。
 *
 * 进程级单例；内存缓存 + SharedPreferences 双层快照：小组件可能在宿主进程死掉后
 * 由桌面进程重新渲染，内存层没了，落盘的那份就是 provideGlance 的唯一状态来源。
 * Koin 未就绪时全部调用静默降级为读缓存，不抛异常
 *
 * The AppWidget state updater and dispatcher.
 *
 * Extracts the latest runtime status from the background environment and the
 * scheduler, caches the snapshot, and fans refreshes out to every registered
 * widget.
 *
 * A process-level singleton with a two-tier snapshot (memory +
 * SharedPreferences): a widget can be re-rendered by the launcher process
 * after the host process dies, where the memory tier is gone and the
 * persisted copy is the only source for provideGlance. With Koin not ready,
 * every call degrades silently to the cache instead of throwing.
 */
object AzurPilotWidgetUpdater {

    /** 快照落盘文件；独立于 app 设置，属小组件私有缓存 / The snapshot's backing file; separate from app settings, private to the widget. */
    private const val PREFS_NAME = "azurpilot_widget_cache"
    private const val KEY_RUNNER_ALIVE = "runner_alive"
    private const val KEY_BUSY = "busy"
    private const val KEY_CONFIG = "config"
    private const val KEY_STATUS_TEXT = "status_text"
    private const val KEY_STATUS_LEVEL = "status_level"
    private const val KEY_CURRENT_TASK = "current_task"
    private const val KEY_PID = "pid"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedState: AzurPilotWidgetState? = null

    /**
     * 返回当前小组件状态：优先内存缓存，否则从 SharedPreferences 恢复并回填
     *
     * provideGlance 渲染时同步调用；无 IO 阻塞主线程的风险（SharedPreferences
     * 首次加载除外），也不需要挂起
     *
     * Returns the current widget state: the memory cache first, otherwise
     * restored from SharedPreferences and backfilled into the cache.
     *
     * Called synchronously during provideGlance rendering; no main-thread IO
     * blocking beyond SharedPreferences' first load, and no suspension
     * needed.
     */
    fun currentState(context: Context): AzurPilotWidgetState {
        cachedState?.let { return it }
        // 从 SharedPreferences 恢复缓存状态
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val state = AzurPilotWidgetState(
            runnerAlive = sp.getBoolean(KEY_RUNNER_ALIVE, false),
            busy = sp.getBoolean(KEY_BUSY, false),
            config = sp.getString(KEY_CONFIG, "ap") ?: "ap",
            statusText = sp.getString(KEY_STATUS_TEXT, "") ?: "",
            statusLevel = runCatching {
                WidgetStatusLevel.valueOf(sp.getString(KEY_STATUS_LEVEL, WidgetStatusLevel.INACTIVE.name)!!)
            }.getOrDefault(WidgetStatusLevel.INACTIVE),
            currentTask = sp.getString(KEY_CURRENT_TASK, null),
            pid = if (sp.contains(KEY_PID)) sp.getInt(KEY_PID, 0) else null,
        )
        cachedState = state
        return state
    }

    /**
     * 计算最新状态、落盘并刷新全部小组件
     *
     * 计算与落盘在调用线程完成（保证下次进程重启能读到），RemoteViews 推送
     * 转 IO 协程异步做；单个 widget 刷新失败只记日志，不影响另一个
     *
     * Computes the latest state, persists it, and refreshes every widget.
     *
     * Computing and persisting finish on the calling thread (so the next
     * process start can read them); the RemoteViews push moves to an IO
     * coroutine. A single widget's refresh failure is only logged and never
     * blocks the other.
     */
    fun updateAll(context: Context) {
        val state = computeLatestState(context)
        saveState(context, state)
        scope.launch {
            runCatching {
                AzurPilotControlWidget().updateAll(context)
            }.onFailure { Timber.w(it, "Failed to update AzurPilotControlWidget") }
            runCatching {
                AzurPilotQuickWidget().updateAll(context)
            }.onFailure { Timber.w(it, "Failed to update AzurPilotQuickWidget") }
        }
    }

    /**
     * 从 Koin 取各状态源解析快照；Koin 未就绪或依赖缺失时回落缓存，
     * 小组件宁可显示旧状态也不能崩溃
     *
     * Resolves the snapshot from the state sources in Koin; falls back to the
     * cache when Koin is not ready or a dependency is missing — a widget must
     * rather show a stale state than crash.
     */
    private fun computeLatestState(context: Context): AzurPilotWidgetState {
        val koin = GlobalContext.getOrNull() ?: return currentState(context)
        val hostState = runCatching { koin.get<HostState>() }.getOrNull()
        val prootHost = runCatching { koin.get<ProotHost>() }.getOrNull()
        val runController = runCatching { koin.get<AzurPilotRunController>() }.getOrNull()
        val repository = runCatching { koin.get<AzurPilotRepository>() }.getOrNull()

        return AzurPilotWidgetState.resolve(
            context = context,
            host = hostState?.snapshot?.value,
            prootPhase = prootHost?.state?.value?.phase,
            prootSessionActive = prootHost?.state?.value?.sessionActive ?: false,
            prootDetail = prootHost?.state?.value?.detail ?: "",
            run = runController?.state?.value,
            overview = repository?.overview?.value,
            instances = repository?.instances?.value,
        )
    }

    /** 双层落盘：先更新内存缓存再写 SharedPreferences；pid 为 null 时移除键以区分「未知」与 0 / Persists to both tiers: memory cache first, then SharedPreferences; a null pid removes the key to separate "unknown" from 0. */
    private fun saveState(context: Context, state: AzurPilotWidgetState) {
        cachedState = state
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RUNNER_ALIVE, state.runnerAlive)
            .putBoolean(KEY_BUSY, state.busy)
            .putString(KEY_CONFIG, state.config)
            .putString(KEY_STATUS_TEXT, state.statusText)
            .putString(KEY_STATUS_LEVEL, state.statusLevel.name)
            .putString(KEY_CURRENT_TASK, state.currentTask)
            .apply {
                if (state.pid != null) putInt(KEY_PID, state.pid) else remove(KEY_PID)
            }
            .apply()
    }
}
