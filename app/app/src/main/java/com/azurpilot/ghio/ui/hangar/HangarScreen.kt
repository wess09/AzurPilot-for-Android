package com.azurpilot.ghio.ui.hangar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.AzurPilotRunState
import com.azurpilot.ghio.service.HostState
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.AzurPilotControlPanel
import com.azurpilot.ghio.ui.components.AzurPilotLogBoard
import com.azurpilot.ghio.ui.components.ScreenSaverButton
import org.koin.compose.koinInject

/**
 * 日志区固定高度：约 20 行可见，其余靠板内滚动；再往下由整页滚动接手
 *
 * Fixed height of the log area: about 20 visible lines, the rest scrolls inside
 * the board; anything beyond is handed to the page-level scroll.
 */
private val LogBoardHeight = 320.dp

/**
 * 主页 tab：运行配置选择 + AzurPilot 控制面板 + 运行日志
 *
 * 整页可滚（矮屏 / 分屏 / 横屏下按钮不会被裁）。虚屏实时画面已从本页移除；
 * 环境仍由 [HostState.ensureEnvironmentStarted] 在本页可见时自动拉起，与画面无关。
 * 配置面（改任务参数）仍在 AzurPilot WebUI tab，本页只选「跑哪个配置」；
 * 半自动点击 / 活动剧情那两个工具直接操作虚拟屏，入口已挪到虚拟屏页
 *
 * Renders the Hangar tab: run-config selection + AzurPilot control panel + run log.
 *
 * The whole page scrolls (the start button stays unclipped on short screens,
 * split-screen, and landscape). The live virtual-display feed has been removed
 * from this page; the environment is still raised automatically by
 * [HostState.ensureEnvironmentStarted] while the page is visible, regardless of
 * the feed. Task-parameter editing stays in the AzurPilot WebUI tab; this page
 * only picks which config to run, and the two tools that drive the virtual
 * display directly (semi-auto click / event story) have moved to the
 * virtual-display page.
 *
 * @param active 是否为 pager 当前页；true 且特权连接就绪时触发一次环境拉起 /
 *   whether this is the pager's current page; true with a ready privileged
 *   connection triggers one environment start
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HangarScreen(
    active: Boolean,
    modifier: Modifier = Modifier,
    hostState: HostState = koinInject(),
    runController: AzurPilotRunController = koinInject(),
) {
    val snapshot by hostState.snapshot.collectAsStateWithLifecycle()
    val run by runController.state.collectAsStateWithLifecycle()
    // M3 的顶栏滚动行为：内容滚起来时顶栏换成容器色并抬起，
    // 「下面还有东西」这件事由顶栏自己交代，不用另加一条分隔线
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 本页可见且特权连接就绪时自动补一次「开始」链路建虚拟屏（HostState 内幂等）
    LaunchedEffect(active, snapshot.privilegedConnected) {
        if (active && snapshot.privilegedConnected) {
            hostState.ensureEnvironmentStarted()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_hangar)) },
            // AppRoot 的 Scaffold 已吃掉状态栏顶部 inset，这里不能再加一次
            windowInsets = WindowInsets(0, 0, 0, 0),
            scrollBehavior = scrollBehavior,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // nestedScroll 要排在 verticalScroll 左边才是滚动节点的父级，才收得到回弹
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                // 内容进了 scrollable Column 就再用不得 weight——日志区因此取固定高，
                // 两者合起来保证矮屏能滚到底、开始按钮不会被裁
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppTokens.Spacing.lg,
                    end = AppTokens.Spacing.lg,
                    // 首个元素是 outlined 输入框：它的可见边框比组合件顶边低 8dp 上下，
                    // 不留顶距就会贴到顶栏下沿
                    top = AppTokens.Spacing.sm,
                    bottom = AppTokens.Spacing.md,
                ),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
        ) {
            ConfigSelector(run = run, onSelect = runController::selectConfig)
            // 面板本身带上「开始 / 停止」，日志改由本页在按钮下方单独渲染
            AzurPilotControlPanel(
                snapshot = snapshot,
                run = run,
                onRunStart = { runController.startRunner() },
                onRunStop = { runController.stopRunner() },
                onToolStart = { runController.startTool(it) },
                onToolStop = { runController.stopTool() },
                modifier = Modifier.fillMaxWidth(),
                showTools = false,
                // 主页带上 /api/v1/ws 的调度总览与自启开关
                showGateway = true,
                showLog = false,
            )
            ScreenSaverButton()
            AzurPilotLogBoard(
                lines = run.logTail,
                linesCount = run.logLines,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LogBoardHeight),
            )
        }
    }
}

/**
 * 运行配置下拉。调度器在跑时锁切换——生效配置以 /status 回报的 runningConfig 为准，
 * 选择下次启动生效。
 *
 * 外面不套卡：outlined 输入框本就设计成落在 surface 上，再套一层 filled 卡是「框里再套框」
 *
 * Renders the run-config dropdown. Switching is locked while the scheduler runs —
 * the effective config is the runningConfig reported by /status; a selection takes
 * effect on the next start.
 *
 * No card wrapper outside: an outlined field is designed to sit directly on the
 * surface; wrapping a filled card around it is a box inside a box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigSelector(run: AzurPilotRunState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val switchable = !run.runnerAlive && run.configs.isNotEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (switchable) expanded = it },
    ) {
        OutlinedTextField(
            value = if (run.runnerAlive) {
                run.runningConfig ?: run.selectedConfig
            } else {
                run.selectedConfig
            },
            onValueChange = {},
            readOnly = true,
            enabled = switchable,
            label = { Text(stringResource(R.string.hangar_config_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            run.configs.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(name)
                        expanded = false
                    },
                )
            }
        }
    }
}
