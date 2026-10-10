package com.azurpilot.ghio.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunState
import com.azurpilot.ghio.proot.AzurPilotTask
import com.azurpilot.ghio.proot.AzurPilotTaskState
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.service.HostSnapshot
import com.azurpilot.ghio.service.HostEnvironmentIssue
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.theme.AzurPilotTheme
import org.koin.compose.koinInject

/**
 * 渲染 AzurPilot 控制面板（共享组合件）：环境/调度器状态行 + 日志板 + 调度器启停
 *
 * 悬浮窗（OverlayPanel）与主页（HangarScreen）共用同一份。
 * 进程启停只此一处（wrapper 薄 HTTP）；AzurPilot 页只做内容面，不再开第二条启停路径
 * （见 AzurPilotRunController 头注）
 *
 * Renders the AzurPilot control panel (shared composite): environment/scheduler
 * status rows + log board + runner start/stop.
 *
 * Shared verbatim by the overlay window (OverlayPanel) and the main page
 * (HangarScreen). Process start/stop lives only here (thin HTTP wrapper); the
 * AzurPilot page is content-only and must not open a second start/stop path (see
 * the AzurPilotRunController header note).
 *
 * @param showTools 悬浮窗要工具区；主页的工具按钮已并进运行配置行（ConfigToolRow），
 *   传 false / the overlay wants the tool section; the main page has folded its
 *   tool buttons into the run-config row (ConfigToolRow), pass false
 * @param showGateway 主页传 true：额外显示 /api/v1/ws 来的「调度总览」与「自启」；
 *   悬浮窗空间紧张，保持关闭 / pass true on the main page to also show the
 *   scheduler overview and auto-start toggle from /api/v1/ws; keep it off in the
 *   space-tight overlay
 * @param showLog 主页把日志单独渲染在启停按钮下方（要控高度），传 false；悬浮窗保持
 *   默认 / the main page renders the log separately under the start/stop button
 *   (needs height control), pass false; the overlay keeps the default
 * @param logBoardHeight 日志板的固定高。null = 吃掉剩余空间（宿主是定高容器时用）；
 *   给定值 = 按这个高摆（宿主自己可滚时用，否则日志会被挤成一条线） / fixed height
 *   of the log board. null = take the remaining space (host is a fixed-height
 *   container); a value = lay out at that height (host scrolls itself, otherwise
 *   the log collapses to a single line)
 */
@Composable
fun AzurPilotControlPanel(
    snapshot: HostSnapshot,
    run: AzurPilotRunState,
    onRunStart: () -> Unit,
    onRunStop: () -> Unit,
    onToolStart: (String) -> Unit,
    onToolStop: () -> Unit,
    modifier: Modifier = Modifier,
    showTools: Boolean = true,
    showGateway: Boolean = false,
    showLog: Boolean = true,
    logBoardHeight: Dp? = null,
) {
    // wrapper 不可达时区分「环境准备中（带阶段明细）」与真正的「未就绪」——
    // 准备链全程 2~5 分钟且 release 日志静默，状态行是唯一可见的进度面
    val prootHost: ProotHost = koinInject()
    val proot by prootHost.state.collectAsStateWithLifecycle()
    val repository: AzurPilotRepository = koinInject()
    val connected by repository.connected.collectAsStateWithLifecycle()
    val overview by repository.overview.collectAsStateWithLifecycle()
    val startup by repository.startup.collectAsStateWithLifecycle()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
    ) {
        // 状态行与自启开关装同一张卡：它们答的是同一个问题「这台机器现在什么状态」。
        // 散在背景上就只是几行浮字，读的人分不出哪里是一组
        AppCard {
            AzurPilotStatusRow(
                level = if (snapshot.privilegedConnected) StatusLevel.Ok else StatusLevel.Error,
                labelRes = R.string.overlay_host_privileged,
                value = stringResource(
                    if (snapshot.privilegedConnected) {
                        R.string.host_state_connected
                    } else {
                        R.string.host_state_disconnected
                    }
                ),
            )
            AzurPilotStatusRow(
                level = if (snapshot.bridgeReachable) StatusLevel.Ok else StatusLevel.Error,
                labelRes = R.string.overlay_host_bridge,
                value = stringResource(
                    if (snapshot.bridgeReachable) {
                        R.string.host_state_ok
                    } else {
                        R.string.host_state_unreachable
                    }
                ),
            )
            AzurPilotStatusRow(
                level = if (snapshot.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE) {
                    StatusLevel.Ok
                } else {
                    StatusLevel.Inactive
                },
                labelRes = R.string.overlay_host_display,
                value = if (snapshot.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE) {
                    "#${snapshot.vdDisplayId}"
                } else {
                    stringResource(R.string.host_state_display_none)
                },
            )
            snapshot.environmentIssue?.let { issue ->
                Text(
                    text = stringResource(
                        when (issue) {
                            HostEnvironmentIssue.ROOT_BACKEND_REQUIRED -> R.string.host_shizuku_root_uid_error
                            HostEnvironmentIssue.ROOT_FALLBACK_FAILED -> R.string.host_root_fallback_failed
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AzurPilotStatusRow(
                level = azurPilotStatusLevel(run, proot.phase),
                labelRes = R.string.overlay_azurpilot_status,
                value = azurPilotRunStatusText(run, proot.phase, proot.sessionActive, proot.detail),
            )
            if (showGateway && connected && overview != null) {
                AzurPilotSchedulerOverview(overview!!.tasks)
            }
            if (showGateway) {
                AppLabeledControlRow(
                    label = stringResource(R.string.gateway_startup_auto),
                    trailing = {
                        Switch(
                            checked = startup?.enabled == true,
                            enabled = connected,
                            onCheckedChange = { repository.setStartup(enabled = it) },
                        )
                    },
                )
            }
        }
        if (showLog) {
            AzurPilotLogBoard(
                lines = run.logTail,
                linesCount = run.logLines,
                modifier = Modifier
                    .then(
                        if (logBoardHeight != null) Modifier.height(logBoardHeight)
                        else Modifier.weight(1f)
                    )
                    .fillMaxWidth(),
            )
        }
        Button(
            onClick = if (run.runnerAlive) onRunStop else onRunStart,
            enabled = run.reachable && !run.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 图标与文案一起交叉淡入：启停是同一颗按钮的两种态，硬切会闪一下"换了个按钮"
            AnimatedContent(
                targetState = run.runnerAlive,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "runnerToggle",
            ) { alive ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                ) {
                    Icon(
                        imageVector = if (alive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(AppTokens.IconSize.md),
                    )
                    Text(
                        text = stringResource(
                            if (alive) {
                                R.string.overlay_azurpilot_stop
                            } else {
                                R.string.overlay_azurpilot_start
                            }
                        ),
                    )
                }
            }
        }
        if (showTools) {
            AzurPilotToolSection(
                run = run,
                onToolStart = onToolStart,
                onToolStop = onToolStop,
            )
        }
        Text(
            text = stringResource(R.string.overlay_azurpilot_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 渲染日志板：主页与悬浮窗共用的展示件，新日志自动沉底；无内容时给占位提示
 *
 * 底色取"井"（低一档容器 + 描边）而不是与卡片同档：两个宿主的底色不同
 * （主页是 surface、悬浮窗面板是 surfaceContainerHighest），同档取色必然有一边
 * 与底同色而看不出边界；低一档加描边在两边都读得出来
 *
 * 正文用 bodySmall 等宽档：一行日志要能一眼扫完级别与正文
 *
 * Renders the log board: a display shared by the main page and the overlay; new
 * lines auto-scroll to the bottom; shows a placeholder when empty.
 *
 * The background picks the "well" recipe (one tier lower container + border)
 * instead of matching the card tier: the two hosts sit on different backgrounds
 * (surface on the main page, surfaceContainerHighest on the overlay panel), so a
 * same-tier color would blend into one of them and lose its boundary; one tier
 * lower plus a border reads on both.
 *
 * Body text uses the bodySmall monospace tier: a log line must show level and
 * text in one glance.
 */
@Composable
fun AzurPilotLogBoard(lines: List<String>, linesCount: Int, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(linesCount, lines.size) { scrollState.scrollTo(scrollState.maxValue) }
    val lineStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(AppTokens.Separator.thickness, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(AppTokens.Spacing.md),
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.overlay_azurpilot_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(text = lines.joinToString("\n"), style = lineStyle)
            }
        }
    }
}

/**
 * 状态行的健康度，只用来给指示点取色
 *
 * Health level of a status row; exists only to color the indicator dot.
 */
private enum class StatusLevel { Ok, Inactive, Error }

@Composable
private fun statusDotColor(level: StatusLevel): Color = when (level) {
    StatusLevel.Ok -> AzurPilotTheme.palette.success
    StatusLevel.Inactive -> MaterialTheme.colorScheme.outline
    StatusLevel.Error -> MaterialTheme.colorScheme.error
}

/**
 * 渲染一条状态：指示点 + 一句话
 *
 * 点是给"扫"的，文案是给"读"的——四行同色同号的裸字看不出哪条是坏的
 *
 * 点按首行的行高一格居中，而不是整行居中：状态文案会换行（"Runtime 准备中 · 拉起 proot 会话"），
 * 整行居中会把点推到两行之间，看起来哪行都不属于
 *
 * Renders one status line: indicator dot + one sentence.
 *
 * The dot is for scanning, the text for reading — four bare lines of the same
 * color and bullet hide which one is broken.
 *
 * The dot centers within the first line's height instead of the whole row: status
 * text wraps ("Runtime preparing · starting proot session"), and full-row
 * centering pushes the dot between the two lines, belonging to neither.
 */
@Composable
private fun AzurPilotStatusRow(level: StatusLevel, labelRes: Int, value: String) {
    val lineHeight = with(LocalDensity.current) {
        MaterialTheme.typography.bodyMedium.lineHeight.toDp()
    }
    // 状态是活的：点和文案一起变，硬切会让人以为只是重绘了一次
    val dotColor by animateColorAsState(statusDotColor(level), label = "statusDot")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
    ) {
        Box(
            modifier = Modifier.height(lineHeight),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(AppTokens.Indicator.dot)
                    .background(dotColor, CircleShape),
            )
        }
        Text(
            text = stringResource(labelRes, value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 返回调度器状态一句话。wrapper 不可达时必须区分「环境准备中（带阶段明细）」与真正的「未就绪」——
 * 准备链全程 2~5 分钟且 release 日志静默，这句是唯一可见的进度面。
 * 控制面板与底部控制卡都用，故独立成件
 *
 * Returns the one-line scheduler status. When the wrapper is unreachable it must
 * tell "environment preparing (with phase detail)" apart from a true "not ready"
 * — the preparation chain runs 2-5 minutes and release logs stay silent, so this
 * line is the only visible progress surface. Both the control panel and the
 * bottom control card use it, hence a standalone piece.
 */
@Composable
fun azurPilotRunStatusText(
    run: AzurPilotRunState,
    phase: ProotPhase,
    sessionActive: Boolean,
    detail: String,
): String = when {
    !run.reachable && phase == ProotPhase.FAILED ->
        stringResource(R.string.overlay_azurpilot_start_failed, detail)
    !run.reachable && sessionActive && detail.isNotEmpty() ->
        stringResource(R.string.overlay_azurpilot_preparing, detail)
    !run.reachable && sessionActive ->
        stringResource(R.string.overlay_azurpilot_preparing_generic)
    !run.reachable -> stringResource(R.string.overlay_azurpilot_unreachable)
    run.runnerAlive -> stringResource(R.string.overlay_azurpilot_running, run.pid ?: 0)
    else -> stringResource(R.string.overlay_azurpilot_stopped)
}

/**
 * 返回指示点级别，与 [azurPilotRunStatusText] 同源：文案说「失败」时点是红的，说「运行中」时点是绿的
 *
 * Returns the dot level, kept in step with [azurPilotRunStatusText]: red when the
 * text says "failed", green when it says "running".
 */
private fun azurPilotStatusLevel(run: AzurPilotRunState, phase: ProotPhase): StatusLevel = when {
    !run.reachable && phase == ProotPhase.FAILED -> StatusLevel.Error
    run.runnerAlive -> StatusLevel.Ok
    else -> StatusLevel.Inactive
}

/**
 * 渲染工具区：半自动点击 / 活动剧情——与虚拟屏页同款槽位按钮（[ToolSlotButton]）
 *
 * 某工具在跑时对应槽位变「停止」（槽位即归属），另一槽保持可点=换工具；
 * 与调度器的互斥（启工具自动停 runner、启 runner 自动停工具）由 wrapper 集中执行，
 * 可用性只沿用面板既有的 wrapper 可达/忙碌判断，不拿 runnerAlive/toolAlive 互相禁用
 *
 * Renders the tool section: semi-auto click / event story — same slot buttons as
 * the virtual-display page ([ToolSlotButton]).
 *
 * While a tool runs, its slot turns into "Stop" (the slot owns the tool) and the
 * other stays tappable = switch tools; the mutual exclusion with the scheduler
 * (starting a tool stops the runner and vice versa) is enforced centrally by the
 * wrapper, so availability simply reuses the panel's existing wrapper
 * reachable/busy checks instead of disabling via runnerAlive/toolAlive.
 */
@Composable
private fun AzurPilotToolSection(
    run: AzurPilotRunState,
    onToolStart: (String) -> Unit,
    onToolStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs)) {
        Text(
            text = stringResource(R.string.tool_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        ToolSlotRow(run = run, onToolStart = onToolStart, onToolStop = onToolStop)
    }
}

/**
 * 工具槽的定义：标签与运行状态里对应的 toolName
 *
 * 两个工具在多处出现（虚拟屏页的竖排、悬浮窗的横排），字段只在这里写一份
 * ——按钮形制与互斥规则是共享的，重复定义迟早会漂
 *
 * Definition of one tool slot: the label and the matching toolName in run state.
 *
 * Both tools appear in several places (vertical on the virtual-display page,
 * horizontal in the overlay); the fields are written here exactly once — button
 * shape and exclusion rules are shared, and duplicated definitions always drift.
 */
private data class ToolSlot(val labelRes: Int, val toolName: String)

private val ToolSlots = listOf(
    ToolSlot(R.string.tool_semi_auto, AzurPilotRunState.TOOL_SEMI_AUTO),
    ToolSlot(R.string.tool_event_story, AzurPilotRunState.TOOL_EVENT_STORY),
)

/**
 * 渲染一个工具槽：按 [ToolSlot.toolName] 认领当前在跑的是不是自己
 *
 * Renders one tool slot: claims the currently running tool as its own by matching
 * [ToolSlot.toolName].
 */
@Composable
private fun ToolSlotAction(
    slot: ToolSlot,
    run: AzurPilotRunState,
    onToolStart: (String) -> Unit,
    onToolStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolSlotButton(
        labelRes = slot.labelRes,
        running = run.toolAlive && run.toolName == slot.toolName,
        onStart = { onToolStart(slot.toolName) },
        onStop = onToolStop,
        enabled = run.reachable && !run.busy,
        modifier = modifier,
    )
}

/**
 * 渲染工具槽横排：悬浮窗工具区用（面板宽度只够平铺，竖排会把日志板挤没）
 *
 * Renders the tool slots in a row: for the overlay tool section (the panel is
 * just wide enough to tile them; a column would squeeze out the log board).
 */
@Composable
fun ToolSlotRow(
    run: AzurPilotRunState,
    onToolStart: (String) -> Unit,
    onToolStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
    ) {
        ToolSlots.forEach { slot ->
            ToolSlotAction(
                slot = slot,
                run = run,
                onToolStart = onToolStart,
                onToolStop = onToolStop,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 工具槽竖排：虚拟屏页用，落在画面右侧的留白里
 *
 * 这个位子不是随便挑的：虚拟屏是 16:9，手机横屏接近 20:9，画面按高度撑满后左右天然
 * 空出近 190dp。把入口放进这段留白，画面尺寸一点不减——压在画面下沿的那一版要吃掉
 * 78dp 的竖向高度，正是这一页最稀缺的一维
 *
 * Renders the tool slots in a column: for the virtual-display page, dropped into
 * the whitespace right of the frame.
 *
 * The spot is not arbitrary: the virtual display is 16:9 while a phone in
 * landscape is nearly 20:9, so a height-fitted frame naturally leaves ~190dp on
 * each side. Placing the entries in that whitespace costs the frame nothing —
 * the variant pressed under the frame's bottom edge eats 78dp of vertical
 * height, the scarcest dimension on this page.
 */
@Composable
fun ToolSlotColumn(
    run: AzurPilotRunState,
    onToolStart: (String) -> Unit,
    onToolStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md, Alignment.CenterVertically),
    ) {
        ToolSlots.forEach { slot ->
            ToolSlotAction(
                slot = slot,
                run = run,
                onToolStart = onToolStart,
                onToolStop = onToolStop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 工具槽位按钮（共享）：虚拟屏页（画面右侧竖排）与悬浮窗工具区（[AzurPilotToolSection]）共用
 *
 * 形制取 tonal 而非实心 filled：一页里同时出现「开始挂机」这个主操作与两个工具入口时，
 * 同款实心按钮会三点抢同一级视觉重量，主次就没了。本槽工具在跑时变「停止」（槽位即归属）
 *
 * 标签居中且允许换行：两处宿主都窄（悬浮窗半栏 ~147dp、虚拟屏侧栏 ~124dp），
 * 日文的工具名一行放不下，居中的两行比贴左的单行省略号好读
 *
 * Renders the shared tool slot button: used by the virtual-display page (vertical,
 * right of the frame) and the overlay tool section ([AzurPilotToolSection]).
 *
 * Tonal rather than filled: when a page shows the primary "start grinding" action
 * next to two tool entries, three identical filled buttons compete for the same
 * visual weight and the hierarchy collapses. A running slot turns into "Stop"
 * (the slot owns the tool).
 *
 * The label is centered and allowed to wrap: both hosts are narrow (overlay
 * half-column ~147dp, virtual-display side rail ~124dp) and Japanese tool names
 * do not fit one line; a centered two-liner reads better than a left-aligned
 * ellipsis.
 */
@Composable
fun ToolSlotButton(
    labelRes: Int,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = if (running) onStop else onStart,
        enabled = enabled,
        // 竖向空间不富裕，按钮自身的内边距收紧一档；M3 默认 24dp 是给宽标签留的
        contentPadding = PaddingValues(
            horizontal = AppTokens.Spacing.md,
            vertical = AppTokens.Spacing.xs,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        // 交叉淡入而非硬切：槽位在「半自动点击 / 停止」之间换的不只是字，是这一槽的归属
        AnimatedContent(
            targetState = running,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "toolSlotLabel",
        ) { isRunning ->
            Text(
                text = stringResource(if (isRunning) R.string.tool_stop else labelRes),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 渲染调度总览：来自 `/api/v1/ws` 的 `overview` 主题（与日志板同源不同接口）
 *
 * 控制面板只给「下一个要跑什么」这一件事——完整任务表在 AzurPilot 页的总览里。
 *
 * Renders the scheduler overview: the `overview` topic from `/api/v1/ws` (same
 * source as the log board, different endpoint).
 *
 * The panel conveys exactly one thing — what runs next; the full task table lives
 * in the AzurPilot page's overview.
 */
@Composable
private fun AzurPilotSchedulerOverview(tasks: List<AzurPilotTask>) {
    val next = tasks.firstOrNull { it.state != AzurPilotTaskState.Running } ?: tasks.firstOrNull()
    val text = when {
        tasks.isEmpty() -> stringResource(R.string.gateway_overview_empty)
        next != null -> stringResource(
            R.string.gateway_overview_next,
            next.name,
            next.nextRun,
            tasks.size,
        )
        else -> stringResource(R.string.gateway_overview_empty)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
