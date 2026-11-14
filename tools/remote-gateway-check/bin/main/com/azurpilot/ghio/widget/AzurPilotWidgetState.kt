package com.azurpilot.ghio.widget

import android.content.Context
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotInstance
import com.azurpilot.ghio.proot.AzurPilotOverview
import com.azurpilot.ghio.proot.AzurPilotRunState
import com.azurpilot.ghio.proot.AzurPilotTaskState
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.service.HostSnapshot

/**
 * 小组件视觉状态级别
 *
 * 决定状态圆点的颜色档位；ERROR 与 WARNING 只影响观感，不改变任何行为
 *
 * The status level for AppWidget visual cues.
 *
 * It picks the status dot's color tier; ERROR and WARNING are cosmetic only
 * and change no behavior.
 */
enum class WidgetStatusLevel {
    /** 运行中且可达：绿点 / Running and reachable: green dot. */
    OK,

    /** 空闲：灰点 / Idle: gray dot. */
    INACTIVE,

    /** 准备中或调度忙碌：黄点 / Preparing or scheduler busy: yellow dot. */
    WARNING,

    /** 运行环境失败：红点 / Run environment failed: red dot. */
    ERROR;

    /** 状态圆点 drawable，按级别映射 / The status dot drawable, mapped by level. */
    val dotDrawableRes: Int
        get() = when (this) {
            OK -> R.drawable.ic_widget_dot_green
            INACTIVE -> R.drawable.ic_widget_dot_gray
            WARNING -> R.drawable.ic_widget_dot_yellow
            ERROR -> R.drawable.ic_widget_dot_red
        }
}

/**
 * AzurPilot 桌面小组件状态快照
 *
 * 由 [resolve] 把外壳与运行时两侧的零散状态压平成小组件能一次渲染的纯值；
 * 全是展示字段，不回写、不触发行为
 *
 * The snapshot of AzurPilot state displayed on the AppWidget.
 *
 * [resolve] flattens the scattered state from both the shell and the runtime
 * sides into plain values the widget renders in one pass; purely presentational,
 * never written back nor triggering behavior.
 *
 * @property runnerAlive 调度器是否存活，决定启停按钮的图标与文案 / Whether the
 *   runner is alive, driving the toggle button's icon and label
 * @property busy 是否处于忙碌过渡态，此时按钮不可点 / Whether a busy transition is
 *   underway, disabling the button
 * @property config 当前配置名，空回落 "ap" / The current config name, falling
 *   back to "ap" when empty
 * @property statusText 本地化状态行文案 / The localized status line text
 * @property statusLevel 状态圆点档位 / The status dot tier
 * @property currentTask 当前运行任务名；null 显示「空闲」/ The running task's
 *   name; null renders the idle copy
 * @property pid runner 进程号；null 或非正数表示未知 / The runner pid; null or
 *   non-positive means unknown
 */
data class AzurPilotWidgetState(
    val runnerAlive: Boolean = false,
    val busy: Boolean = false,
    val config: String = "ap",
    val statusText: String = "",
    val statusLevel: WidgetStatusLevel = WidgetStatusLevel.INACTIVE,
    val currentTask: String? = null,
    val pid: Int? = null,
) {
    companion object {
        /**
         * 把宿主与运行时状态解析成小组件快照
         *
         * 状态判定按优先级短路：环境失败 > 会话未就绪 > 调度器存活 > 忙碌 > 空闲；
         * 任务名按「实例上报 → 概览里 Running 的任务 → 日志尾行」逐级回落
         *
         * Resolves the host and runtime states into a widget snapshot.
         *
         * The verdict short-circuits by priority: environment failed > session
         * not ready > runner alive > busy > idle; the task name falls back
         * through instance report → the Running task in the overview → the
         * last log line.
         *
         * @param host 宿主快照，可为 null / The host snapshot, may be null
         * @param prootPhase PRoot 会话阶段，可为 null / The PRoot session
         *   phase, may be null
         * @param prootSessionActive PRoot 会话是否存活 / Whether the PRoot
         *   session is alive
         * @param prootDetail PRoot 会话详情文案 / The PRoot session detail text
         * @param run 调度器运行态，可为 null / The runner state, may be null
         * @param overview 运行时概览，可为 null / The runtime overview, may be
         *   null
         * @param instances 运行时实例列表，可为 null / The runtime instance
         *   list, may be null
         * @return 可直接渲染的快照 / The ready-to-render snapshot
         */
        fun resolve(
            context: Context,
            host: HostSnapshot?,
            prootPhase: ProotPhase?,
            prootSessionActive: Boolean,
            prootDetail: String,
            run: AzurPilotRunState?,
            overview: AzurPilotOverview?,
            instances: List<AzurPilotInstance>?,
        ): AzurPilotWidgetState {
            val runnerAlive = run?.runnerAlive == true
            val busy = run?.busy == true
            val config = run?.selectedConfig?.ifEmpty { "ap" } ?: "ap"
            val pid = run?.pid

            // 计算当前运行任务
            val activeTask = instances?.firstOrNull()?.currentTask
                ?: overview?.tasks?.firstOrNull { it.state == AzurPilotTaskState.Running }?.name
                ?: run?.logTail?.lastOrNull()?.takeIf { runnerAlive }

            // 计算状态文本与等级
            val level: WidgetStatusLevel
            val statusText: String

            when {
                run != null && !run.reachable && prootPhase == ProotPhase.FAILED -> {
                    level = WidgetStatusLevel.ERROR
                    statusText = context.getString(R.string.widget_status_error)
                }
                run != null && !run.reachable && prootSessionActive -> {
                    level = WidgetStatusLevel.WARNING
                    statusText = context.getString(R.string.widget_status_preparing)
                }
                run != null && run.runnerAlive -> {
                    level = WidgetStatusLevel.OK
                    statusText = if (pid != null && pid > 0) {
                        context.getString(R.string.widget_status_running, pid)
                    } else {
                        context.getString(R.string.widget_status_running_no_pid)
                    }
                }
                busy -> {
                    level = WidgetStatusLevel.WARNING
                    statusText = context.getString(R.string.widget_action_busy)
                }
                else -> {
                    level = WidgetStatusLevel.INACTIVE
                    statusText = context.getString(R.string.widget_status_idle)
                }
            }

            return AzurPilotWidgetState(
                runnerAlive = runnerAlive,
                busy = busy,
                config = config,
                statusText = statusText,
                statusLevel = level,
                currentTask = activeTask,
                pid = pid,
            )
        }
    }
}
