package com.azurpilot.ghio.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azurpilot.ghio.R

/** 默认图标容器尺寸（24dp × 24dp）。 / Default icon container dimensions (24dp × 24dp). */
val DefaultSequentialPulseSize: Dp = 24.dp

/** 基态圆点默认直径（5.2dp）。 / Default dot diameter at rest (5.2dp). */
private val DefaultDotDiameter: Dp = 5.2.dp

/** 激活伸展峰值高度（13.0dp）。 / Peak stretched capsule height when active (13.0dp). */
private val DefaultPeakHeight: Dp = 13.0.dp

/** 圆点中心水平等间距（6.4dp）。 / Equidistant horizontal spacing between dot centers (6.4dp). */
private val DefaultDotSpacing: Dp = 6.4.dp

/** 完整三段步进循环周期时长（毫秒）。 / Full cycle duration for sequential pulse loop (ms). */
private const val CYCLE_DURATION_MS = 2000

/** 单个胶囊脉冲窗口占周期的比例。 / Fraction of total cycle for a single dot's pulse window. */
private const val PULSE_WINDOW_FRACTION = 0.44f

/**
 * 运行状态枚举，覆盖完整的任务执行生命周期状态流转。
 *
 * Execution status enum covering the complete lifecycle state transitions.
 */
enum class RunningIconStatus {
    /** 未运行 / Idle: unstarted, displays static idle icon */
    IDLE,

    /** 启动中 / Starting up: dots bloom and enter sequential loop */
    STARTING,

    /** 运行中 / Actively running: continuous sequential pulse loop */
    RUNNING,

    /** 停止中 / Stopping: smoothly settles back to static baseline before finishing */
    STOPPING,

    /** 运行失败 / Execution failed: stops animation and displays error/failed icon */
    FAILED,
}

/**
 * Material 3 Expressive (MD3E) 三段步进（Sequential Pulse）运行状态图标。
 *
 * 采用三个水平排列、等间距的实心几何圆点作为基础形态，依次从左至右纵向伸展为圆角胶囊
 * 再平滑收缩回圆点。每个圆点保持固定的水平位置，以容器垂直中心线对称伸缩，通过适当的
 * 相位差形成连续、克制、富有节奏感的几何波动，代表后台或前台任务正在活跃执行。
 *
 * Material 3 Expressive (MD3E) Sequential Pulse running status icon.
 *
 * Built with three horizontally equidistant solid geometric dots as the base form that sequentially
 * stretch vertically into rounded capsules from left to right and smoothly contract back to dots.
 * Each dot maintains a fixed horizontal position and stretches symmetrically about the container's
 * vertical centerline, generating a continuous, restrained, and rhythmic geometric wave indicating
 * active execution.
 *
 * @param modifier 图标修饰符，默认大小为 [DefaultSequentialPulseSize] / Modifier, defaulting to 24dp × 24dp.
 * @param running 是否处于活跃运行状态 / Whether the task is actively running.
 * @param color 几何图形绘制颜色，默认使用 [MaterialTheme.colorScheme.primary] / Shape color, defaults to primary.
 */
@Composable
fun SequentialPulseRunningIcon(
    modifier: Modifier = Modifier,
    running: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isLifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isLifecycleStarted = event.targetState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val context = LocalContext.current
    val areAnimatorsEnabled = remember(context) { ValueAnimator.areAnimatorsEnabled() }

    // 仅在前台、系统允许动画且处于活跃运行状态时驱动循环动画
    val shouldAnimate = running && isLifecycleStarted && areAnimatorsEnabled

    // 活跃度因子负责在启动时渐进放大波动、停止时平滑收束回圆形基态
    val activeFactor by animateFloatAsState(
        targetValue = if (shouldAnimate) 1f else 0f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "sequentialPulseActiveFactor",
    )

    val transition = rememberInfiniteTransition(label = "sequentialPulseLoop")
    val progressState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CYCLE_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sequentialPulseProgress",
    )

    Canvas(modifier = modifier.size(DefaultSequentialPulseSize)) {
        val dotDiameterPx = DefaultDotDiameter.toPx()
        val peakHeightPx = DefaultPeakHeight.toPx()
        val spacingPx = DefaultDotSpacing.toPx()

        val cx = size.width / 2f
        val cy = size.height / 2f

        // 三个圆点保持固定的水平坐标，严格居中等距排列
        val cx0 = cx - spacingPx
        val cx1 = cx
        val cx2 = cx + spacingPx

        // 仅在绘制阶段读取 progress 状态，避免每帧触发父层组合与重新测量
        val factor = activeFactor
        val t = if (factor > 0f) progressState.value else 0f

        // 依次三段触发：左 (0.00)、中 (0.18)、右 (0.36)，尾部预留呼吸间歇
        val h0 = dotDiameterPx + (peakHeightPx - dotDiameterPx) *
            calculatePulse(t, 0.00f, PULSE_WINDOW_FRACTION) * factor
        val h1 = dotDiameterPx + (peakHeightPx - dotDiameterPx) *
            calculatePulse(t, 0.18f, PULSE_WINDOW_FRACTION) * factor
        val h2 = dotDiameterPx + (peakHeightPx - dotDiameterPx) *
            calculatePulse(t, 0.36f, PULSE_WINDOW_FRACTION) * factor

        // 圆角半径随高度自适应，始终保持胶囊端部为半圆形
        val cornerRadius = CornerRadius(dotDiameterPx / 2f, dotDiameterPx / 2f)

        // 左点
        drawRoundRect(
            color = color,
            topLeft = Offset(cx0 - dotDiameterPx / 2f, cy - h0 / 2f),
            size = Size(dotDiameterPx, h0),
            cornerRadius = cornerRadius,
        )
        // 中点
        drawRoundRect(
            color = color,
            topLeft = Offset(cx1 - dotDiameterPx / 2f, cy - h1 / 2f),
            size = Size(dotDiameterPx, h1),
            cornerRadius = cornerRadius,
        )
        // 右点
        drawRoundRect(
            color = color,
            topLeft = Offset(cx2 - dotDiameterPx / 2f, cy - h2 / 2f),
            size = Size(dotDiameterPx, h2),
            cornerRadius = cornerRadius,
        )
    }
}

/**
 * 状态驱动的运行状态图标容器，统一封装状态切换（未运行静态图标、启动/运行中三段步进、停止自然收束、异常失败图标）。
 *
 * Status-driven running indicator icon container encapsulating transitions across idle static icon,
 * starting/running sequential pulse animation, stopping smooth settlement, and failure icon.
 *
 * @param status 运行状态枚举 / The current execution status.
 * @param modifier 修饰符 / Modifier.
 * @param color 脉冲与图标主题色，默认使用 [MaterialTheme.colorScheme.primary] / Theme color, defaults to primary.
 * @param idleIcon 未运行状态下展示的静态图标 / Static icon shown when idle.
 * @param failedIcon 失败或异常状态下展示的静态图标 / Static icon shown when failed.
 * @param contentDescription 无障碍说明 / Content description for accessibility.
 */
@Composable
fun RunningStatusIcon(
    status: RunningIconStatus,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    idleIcon: ImageVector = Icons.Outlined.NightsStay,
    failedIcon: ImageVector = Icons.Filled.ErrorOutline,
    contentDescription: String? = null,
) {
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
                .togetherWith(
                    fadeOut(animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing))
                )
        },
        label = "runningStatusIconTransition",
        modifier = modifier,
    ) { targetStatus ->
        when (targetStatus) {
            RunningIconStatus.IDLE -> {
                Icon(
                    imageVector = idleIcon,
                    contentDescription = contentDescription,
                    tint = color,
                    modifier = Modifier.size(DefaultSequentialPulseSize),
                )
            }
            RunningIconStatus.FAILED -> {
                Icon(
                    imageVector = failedIcon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(DefaultSequentialPulseSize),
                )
            }
            RunningIconStatus.STARTING,
            RunningIconStatus.RUNNING,
            RunningIconStatus.STOPPING -> {
                val resolvedDescription = contentDescription ?: when (targetStatus) {
                    RunningIconStatus.STARTING -> stringResource(R.string.status_starting)
                    RunningIconStatus.STOPPING -> stringResource(R.string.status_stopping)
                    else -> stringResource(R.string.status_running)
                }
                SequentialPulseRunningIcon(
                    running = targetStatus == RunningIconStatus.RUNNING ||
                        targetStatus == RunningIconStatus.STARTING,
                    color = color,
                    modifier = Modifier.semantics {
                        this.contentDescription = resolvedDescription
                        this.role = Role.Image
                    },
                )
            }
        }
    }
}

/**
 * 计算单个圆点在归一化时间进度 [t] 下的伸展比例（0f..1f）。
 *
 * 波形分为三阶段：
 * 1. 0.00..0.38: 柔和伸展（采用平滑正弦/余弦缓动，起止一阶导数均为 0，无任何加速度突变与过冲）
 * 2. 0.38..0.50: 短暂保持峰值状态
 * 3. 0.50..1.00: 柔和收缩回圆点基态
 *
 * @param t 归一化周期时间 [0f, 1f)
 * @param startOffset 该点的起始相对相位 [0f, 1f)
 * @param windowDuration 伸缩窗口的时长比例
 */
private fun calculatePulse(t: Float, startOffset: Float, windowDuration: Float): Float {
    val relative = (t - startOffset).let { if (it < 0f) it + 1f else it }
    if (relative > windowDuration) return 0f

    val localProgress = (relative / windowDuration).coerceIn(0f, 1f)
    return when {
        localProgress < 0.38f -> {
            val u = localProgress / 0.38f
            0.5f * (1f - kotlin.math.cos(Math.PI.toFloat() * u))
        }
        localProgress < 0.50f -> 1f
        else -> {
            val v = (localProgress - 0.50f) / 0.50f
            0.5f * (1f + kotlin.math.cos(Math.PI.toFloat() * v))
        }
    }
}
