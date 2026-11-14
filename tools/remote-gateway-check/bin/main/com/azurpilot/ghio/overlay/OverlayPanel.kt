package com.azurpilot.ghio.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRunState
import com.azurpilot.ghio.service.HostSnapshot
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.AzurPilotControlPanel
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTonalButton as FilledTonalButton

/**
 * 悬浮控制面板：面板壳（标题/锁定/关闭 + 回 App/环境启停）， 状态行、日志板与调度器启停复用共享组合件 [AzurPilotControlPanel]
 *
 * 呈现在 WindowManager 悬浮窗（SYSTEM_ALERT_WINDOW）内、盖在目标应用画面上的 主控制面；布局三段式——头部、可滚中段、钉住的底部按钮。主线程组合， 尺寸由
 * [OverlayController] 按 Configuration 决定，本组件不自管窗口
 *
 * 底色取 surfaceContainerHighest 而非 surface + 1dp tonal：这面板浮在别的应用画面上， 得先跟背后的内容分开；原来那档 1dp tonal + 1dp
 * shadow 几乎看不出是个浮层。 取到卡片同一档还有个副作用——内层的状态卡与日志板不再"同色压同色"， 整块面板读成一个面，而不是若干发灰的嵌套块
 *
 * The floating control panel: the panel shell (title / lock / close plus back-to-app and
 * environment start/stop), while the status rows, the log board and the scheduler start/stop reuse
 * the shared composable [AzurPilotControlPanel].
 *
 * The main control surface rendered inside a WindowManager overlay window (SYSTEM_ALERT_WINDOW)
 * over the target app's screen; a three-part layout — header, scrollable middle, pinned bottom
 * buttons. Composed on the main thread; the size is decided by [OverlayController] from the
 * Configuration and this composable manages no window of its own.
 *
 * The background takes surfaceContainerHighest rather than surface + 1dp tonal: this panel floats
 * over another app's screen and must first separate itself from what is behind it; the previous 1dp
 * tonal + 1dp shadow barely read as a floating layer. Landing on the card tier has a side benefit —
 * the inner status card and log board no longer sit "same color on same color", so the whole panel
 * reads as one surface instead of several washed-out nested blocks.
 *
 * @param snapshot 环境快照，驱动环境启停按钮文案 / Environment snapshot driving the start/stop button wording
 * @param run 调度器运行态，透传给 [AzurPilotControlPanel] / Scheduler run state, passed through to
 *   [AzurPilotControlPanel]
 * @param isLocked 是否锁定拖拽 / Whether dragging is locked
 * @param onStart 启动环境（特权连接 → setup → 虚拟屏）/ Starts the environment (privileged connect → setup →
 *   virtual display)
 * @param onStop 停止环境 / Stops the environment
 * @param onRunStart 启动调度器 / Starts the scheduler
 * @param onRunStop 停止调度器 / Stops the scheduler
 * @param onToolStart 启动单个工具，参数为工具名 / Starts one tool, by name
 * @param onToolStop 停止当前工具 / Stops the current tool
 * @param onBackToApp 把外壳 App 拉回前台 / Brings the shell app to the front
 * @param onLockToggle 切换锁定态，参数为新的锁定值 / Toggles the lock with the new value
 * @param onClose 关闭面板 / Closes the panel
 * @param modifier 外部修饰符 / Outer modifier
 */
@Composable
fun OverlayPanel(
    snapshot: HostSnapshot,
    run: AzurPilotRunState,
    isLocked: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRunStart: () -> Unit,
    onRunStop: () -> Unit,
    onToolStart: (String) -> Unit,
    onToolStop: () -> Unit,
    onBackToApp: () -> Unit,
    onLockToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        // M3 的浮层档位；tonalElevation 留给"同底色分层级"，这里已经有独立 container 角色了
        shadowElevation = PanelElevation,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(AppTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
        ) {
            PanelHeader(isLocked, onLockToggle, onClose)
            // 中段可滚、底部按钮钉住：面板只占屏高 60%，状态卡 + 日志 + 工具全塞进来
            // 会超；不给中段滚动，日志板会被挤成一条线，而两颗按钮滚走了就找不着了
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
            ) {
                AzurPilotControlPanel(
                    snapshot = snapshot,
                    run = run,
                    onRunStart = onRunStart,
                    onRunStop = onRunStop,
                    onToolStart = onToolStart,
                    onToolStop = onToolStop,
                    modifier = Modifier.fillMaxWidth(),
                    logBoardHeight = OverlayLogHeight,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                // 回 App 是"离开这一层"，环境启停是"改变这一层的状态"：
                // 两件都上实心 filled 时并列摆着谁也不是主操作，降一件到 tonal
                FilledTonalButton(
                    onClick = onBackToApp,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.Home,
                        contentDescription = null,
                        Modifier.size(AppTokens.IconSize.sm),
                    )
                    Text(
                        text = stringResource(R.string.overlay_back_to_app),
                        modifier = Modifier.padding(start = AppTokens.Spacing.xs),
                    )
                }
                Button(
                    onClick = if (snapshot.environmentUp) onStop else onStart,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (snapshot.environmentUp) {
                                R.string.overlay_host_stop
                            } else {
                                R.string.overlay_host_start
                            }
                        )
                    )
                }
            }
        }
    }
}

/**
 * M3 elevation level 3：浮在他人画面之上的容器 / M3 elevation level 3: a container floating above another app's
 * screen.
 */
private val PanelElevation = 3.dp

/**
 * 面板里日志板的固定高：中段可滚之后日志必须有自己的一档，否则会被挤成一条线 / The log board's fixed height inside the panel: with a
 * scrollable middle the log needs its own tier, or it collapses into a sliver.
 */
private val OverlayLogHeight = 120.dp

/**
 * 面板头部：标题 + 锁定/关闭按钮 / The panel header: title plus lock and close buttons.
 *
 * @param isLocked 当前锁定态，决定锁图标与无障碍描述 / The current lock state, driving the lock icon and its
 *   accessibility description
 * @param onLockToggle 请求翻转锁定态 / Requests a lock toggle
 * @param onClose 请求关闭面板 / Requests closing the panel
 */
@Composable
private fun PanelHeader(
    isLocked: Boolean,
    onLockToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.overlay_panel_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        // 锁住即禁止拖拽：面板压在目标应用上，误拖会把它拽出可视区
        IconButton(onClick = { onLockToggle(!isLocked) }) {
            Icon(
                imageVector = if (isLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                contentDescription =
                    stringResource(
                        if (isLocked) R.string.overlay_unlock else R.string.overlay_lock
                    ),
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
        }
    }
}
