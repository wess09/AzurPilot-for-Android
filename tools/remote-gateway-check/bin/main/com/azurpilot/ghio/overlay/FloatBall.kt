package com.azurpilot.ghio.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.theme.AzurPilotTheme
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface

/**
 * 常驻悬浮球：环境活着期间的唯一入口
 *
 * 呈现在 WindowManager 悬浮窗（SYSTEM_ALERT_WINDOW）内的 Compose 内容，盖在其他应用 画面上；[running] 区分运行中（播放图标 +
 * 稳定颜色）与空闲（对勾 + 静止），点击回调由 [OverlayController] 接管用于展开控制面板
 *
 * 尺寸压到 32dp：它盖在其他应用画面上，再大就开始碍事了。避免常驻呼吸动画使后台窗口持续满帧绘制。
 *
 * The resident floating ball: the sole entry point while the environment is alive.
 *
 * Compose content rendered inside a WindowManager overlay window (SYSTEM_ALERT_WINDOW) drawn over
 * other apps' screens; [running] separates the running state (play icon + stable color) from
 * the idle one (check icon + still), and taps are handled by [OverlayController] to expand the
 * control panel.
 *
 * The size is squeezed down to 32dp: it sits on top of other apps' screens, so anything bigger
 * starts getting in the way. Avoid a permanent animation that keeps rendering in the background.
 *
 * @param running 运行环境是否存活 / Whether the run environment is alive
 * @param onClick 点击回调，展开/收起控制面板 / Tap callback to expand or collapse the control panel
 * @param modifier 外部布局修饰符，控制悬浮窗内的摆放 / Outer layout modifier positioning the ball inside its window
 */
@Composable
fun FloatBall(
    running: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AzurPilotTheme.palette
    val runningColor = palette.success
    val target = if (running) runningColor else MaterialTheme.colorScheme.primary
    val onTarget = if (running) palette.onSuccess else MaterialTheme.colorScheme.onPrimary
    val color by animateColorAsState(target.copy(alpha = 0.85f), tween(300))

    val stateText =
        stringResource(if (running) R.string.overlay_ball_running else R.string.overlay_ball_idle)
    val description = "AzurPilot · $stateText"
    val expandActionLabel = stringResource(R.string.overlay_ball_action_expand)

    Surface(
        onClick = onClick,
        modifier =
            modifier
                .size(BALL_SIZE)
                .clip(CircleShape)
                .border(
                    AppTokens.Separator.thickness,
                    onTarget.copy(alpha = 0.15f),
                    CircleShape,
                )
                // 悬浮窗常驻桌面，不能靠每帧修改 alpha 来提示运行状态。
                // 颜色变更已有 300ms 补间，稳定运行后无需持续刷新图层。
                .then(if (running) Modifier.alpha(0.85f) else Modifier)
                .semantics {
                    contentDescription = description
                    onClick(label = expandActionLabel, action = { onClick(); true })
                },
        shape = CircleShape,
        color = color,
        // 球盖在其他应用上需要体量感；不跟 Semi 卡片的 0 elevation
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (running) Icons.Outlined.PlayArrow else Icons.Outlined.Check,
                contentDescription = null,
                tint = onTarget,
                modifier = Modifier.size(AppTokens.IconSize.sm),
            )
        }
    }
}

/**
 * 悬浮球的直径；它盖在别人画面上，属一次性视觉尺寸，不进 Spacing / The ball's diameter; a one-off visual size since it
 * overlays other apps' screens, so it stays out of Spacing.
 */
private val BALL_SIZE = 32.dp
