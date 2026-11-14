package com.azurpilot.ghio.overlay.screensaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.azurpilot.ghio.ui.components.DefaultSequentialPulseSize
import com.azurpilot.ghio.ui.components.RunningIconStatus
import com.azurpilot.ghio.ui.components.RunningStatusIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotInstance
import com.azurpilot.ghio.proot.AzurPilotOverview
import com.azurpilot.ghio.proot.AzurPilotRunState
import com.azurpilot.ghio.proot.AzurPilotSchema
import com.azurpilot.ghio.proot.AzurPilotStatus
import com.azurpilot.ghio.proot.AzurPilotTaskState
import com.azurpilot.ghio.theme.AppTokens
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 渲染最低亮度遮罩中的 MD3 时钟、任务状态和滑块；在主线程组合。
 *
 * 所有可见内容在无操作三十秒后开始防烧屏位移，之后每十秒沿纵轴上下移动一步并在边界折返。
 * 触摸与回弹期间暂停，结束后重新计时；容器仅描边，避免大面积常亮底色。
 * 时间独立按分钟更新，任务仅取正在运行的实例，避免显示用户正在浏览的其他配置。
 *
 * Renders the MD3 clock, task status, and unlock slider inside the dim overlay on the main thread.
 *
 * All visible content starts burn-in mitigation after thirty idle seconds, then takes a vertical
 * step every ten seconds and reverses at bounds. Touches and settling pause and restart the timer.
 * Containers use outlines to avoid large illuminated fills. The clock updates each minute, and task
 * information belongs to the running instance rather than another configuration the user is browsing.
 */
@Composable
fun ScreenSaverView(
    run: AzurPilotRunState,
    instances: List<AzurPilotInstance>,
    overview: AzurPilotOverview?,
    connected: Boolean,
    schema: AzurPilotSchema?,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val density = LocalDensity.current
    val stepPx = remember(density) { with(density) { DRIFT_STEP_DP.roundToPx() } }
    val maxDriftPx = remember(density) { with(density) { MAX_DRIFT_DP.roundToPx() } }
    val battery = rememberBatteryState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val timeFormatter =
        remember(locale, DateFormat.is24HourFormat(context)) {
            DateTimeFormatter.ofPattern(
                if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a",
                locale,
            )
        }
    val dateFormatter =
        remember(locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMEd"), locale)
        }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var drift by remember { mutableStateOf(ScreenSaverDrift()) }
    var interacting by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var touchSequence by remember { mutableIntStateOf(0) }

    val animatedOffsetY by
        animateFloatAsState(
            targetValue = drift.y.toFloat(),
            animationSpec =
                tween(
                    durationMillis = DRIFT_ANIMATION_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            label = "screenSaverDrift",
        )

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(CLOCK_INTERVAL_MS - System.currentTimeMillis().mod(CLOCK_INTERVAL_MS))
        }
    }
    LaunchedEffect(touching, interacting, touchSequence) {
        if (touching || interacting) return@LaunchedEffect
        // 无操作 30 秒后开始上下移动防止烧屏
        delay(INITIAL_IDLE_DELAY_MS)
        while (true) {
            val maxOffsetY =
                ((bounds.height - contentSize.height).coerceAtLeast(0) / 2)
                    .coerceAtMost(maxDriftPx)
            drift = drift.step(stepPx = stepPx, maxOffsetY = maxOffsetY)
            // 之后每 10 秒移动一点
            delay(DRIFT_INTERVAL_MS)
        }
    }

    Box(
        modifier =
            modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                // 只观察触摸，不消费事件；滚动与滑块仍由各自的手势处理器接收。
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.any { it.pressed }
                        if (pressed != touching) {
                            touching = pressed
                            // 快速轻点可能在同一帧内按下又松开，用序号保证空闲计时仍会重置。
                            touchSequence += 1
                        }
                    }
                }
            }
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(AppTokens.Spacing.lg)
                    .onSizeChanged { bounds = it }
        ) {
            Column(
                modifier =
                    Modifier.align(Alignment.Center)
                        .offset {
                            val maxOffsetY =
                                ((bounds.height - contentSize.height).coerceAtLeast(0) / 2)
                                    .coerceAtMost(maxDriftPx)
                            IntOffset(
                                x = 0,
                                y = animatedOffsetY.roundToInt().coerceIn(-maxOffsetY, maxOffsetY),
                            )
                        }
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.84f)
                        .onSizeChanged { contentSize = it }
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.lg),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = now.format(timeFormatter),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = now.format(dateFormatter),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector =
                            if (battery.isCharging) {
                                Icons.Outlined.BatteryChargingFull
                            } else {
                                Icons.Outlined.BatteryStd
                            },
                        contentDescription =
                            if (battery.isCharging) {
                                stringResource(R.string.screensaver_charging)
                            } else null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppTokens.IconSize.sm),
                    )
                    Text(
                        text = stringResource(R.string.screensaver_battery, battery.level),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ScreenSaverTask(run, instances, overview, connected, schema)
                SlideToUnlockBar(
                    onUnlock = onUnlock,
                    onInteractingChanged = { interacting = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 显示运行实例的任务图标与名称；离线、空闲、等待和工具运行分别提供兜底。
 *
 * Displays the running instance's task icon and name with offline, idle, waiting, and tool
 * fallbacks.
 */
@Composable
private fun ScreenSaverTask(
    run: AzurPilotRunState,
    instances: List<AzurPilotInstance>,
    overview: AzurPilotOverview?,
    connected: Boolean,
    schema: AzurPilotSchema?,
) {
    val instance = instances.firstOrNull { it.name == run.runningConfig }
    val task =
        if (run.runnerAlive && connected) {
            instance
                ?.takeIf { it.status == AzurPilotStatus.Running }
                ?.currentTask
                ?.takeIf { it.isNotBlank() }
                ?: overview
                    ?.takeIf {
                        it.instance == run.runningConfig && it.status == AzurPilotStatus.Running
                    }
                    ?.tasks
                    ?.firstOrNull { it.state == AzurPilotTaskState.Running }
                    ?.name
        } else null
    val isRunning = run.reachable && (task != null || run.runnerAlive || run.toolAlive)
    val status =
        when {
            !run.reachable -> RunningIconStatus.FAILED
            run.busy && isRunning -> RunningIconStatus.STOPPING
            run.busy && !isRunning -> RunningIconStatus.STARTING
            isRunning -> RunningIconStatus.RUNNING
            else -> RunningIconStatus.IDLE
        }
    val label =
        when {
            !run.reachable -> stringResource(R.string.screensaver_connecting)
            run.toolAlive ->
                stringResource(
                    if (run.toolName == AzurPilotRunState.TOOL_EVENT_STORY) {
                        R.string.tool_event_story
                    } else R.string.tool_semi_auto
                )
            task != null -> schema?.taskTitle(task) ?: task
            run.runnerAlive -> stringResource(R.string.screensaver_running)
            else -> stringResource(R.string.screensaver_idle)
        }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Color.Black,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AppTokens.Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                Box(
                    modifier = Modifier.padding(AppTokens.Spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    RunningStatusIcon(
                        status = status,
                        idleIcon = Icons.Outlined.NightsStay,
                        failedIcon = Icons.Outlined.CloudOff,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(DefaultSequentialPulseSize),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 只允许从滑块开始拖动到轨道末端后松手解锁；取消与短划回弹，无速度捷径。
 *
 * Unlocks only after dragging the thumb to the end and releasing; cancelled or short drags return
 * to the start without a velocity shortcut. Accessibility exposes a deliberate custom action.
 */
@Composable
private fun SlideToUnlockBar(
    onUnlock: () -> Unit,
    onInteractingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val unlock by rememberUpdatedState(onUnlock)
    val interactionChanged by rememberUpdatedState(onInteractingChanged)
    val thumbPx = with(density) { ThumbDiameter.toPx() }
    val paddingPx = with(density) { TrackPadding.toPx() }
    var trackWidth by remember { mutableFloatStateOf(0f) }
    val maxOffset = (trackWidth - thumbPx - paddingPx * 2).coerceAtLeast(0f)
    var offset by remember { mutableFloatStateOf(0f) }
    val animation = remember { Animatable(0f) }
    var settling by remember { mutableStateOf(false) }
    var releaseJob by remember { mutableStateOf<Job?>(null) }
    val displayOffset = (if (settling) animation.value else offset).coerceIn(0f, maxOffset)
    val progress = if (maxOffset > 0f) (displayOffset / maxOffset).coerceIn(0f, 1f) else 0f
    val unlockLabel = stringResource(R.string.screensaver_swipe_to_unlock)

    fun release(complete: Boolean) {
        releaseJob = scope.launch {
            animation.snapTo(offset)
            settling = true
            animation.animateTo(
                if (complete) maxOffset else 0f,
                tween(180, easing = FastOutSlowInEasing),
            )
            offset = animation.value
            settling = false
            interactionChanged(false)
            if (complete) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                unlock()
            }
        }
    }

    Surface(
        modifier =
            modifier
                .height(TrackHeight)
                .onSizeChanged { trackWidth = it.width.toFloat() }
                .semantics(mergeDescendants = true) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(unlockLabel) {
                                unlock()
                                true
                            }
                        )
                },
        shape = CircleShape,
        color = Color.Black,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box {
            Text(
                text = unlockLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier =
                    Modifier.align(Alignment.Center)
                        .padding(start = ThumbDiameter, end = AppTokens.Spacing.sm)
                        .alpha((1f - progress * 2).coerceIn(0f, 1f)),
            )
            Box(
                modifier =
                    Modifier.align(Alignment.CenterStart)
                        .padding(TrackPadding)
                        .offset { IntOffset(displayOffset.roundToInt(), 0) }
                        .size(ThumbDiameter)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary,
                            CircleShape,
                        )
                        .pointerInput(maxOffset) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    if (settling) offset = animation.value
                                    releaseJob?.cancel()
                                    settling = false
                                    interactionChanged(true)
                                },
                                onHorizontalDrag = { change, delta ->
                                    change.consume()
                                    offset = (offset + delta).coerceIn(0f, maxOffset)
                                },
                                onDragEnd = {
                                    release(maxOffset > 0f && offset >= maxOffset * UNLOCK_RATIO)
                                },
                                onDragCancel = { release(false) },
                            )
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector =
                        if (progress > 0f) {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        } else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AppTokens.IconSize.md),
                )
            }
        }
    }
}

/**
 * 保存电池广播中的电量与充电状态；广播初值缺失时显示零，避免虚构满电。
 *
 * Holds battery level and charging state; a missing initial broadcast shows zero rather than full.
 *
 * @property level 电量百分比 / Battery percentage.
 * @property isCharging 是否充电或已充满 / Whether charging or fully charged.
 */
private data class BatteryState(val level: Int = 0, val isCharging: Boolean = false)

/**
 * 在组合可见期间订阅粘性电量广播，移除遮罩时注销；回调在主线程更新状态。
 *
 * Subscribes to sticky battery broadcasts while composed and unregisters on dismissal; callbacks
 * update state on the main thread.
 */
@Composable
private fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BatteryState()) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    if (scale <= 0 || level < 0) return
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    state =
                        BatteryState(
                            level = (level * 100 / scale).coerceIn(0, 100),
                            isCharging =
                                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                    status == BatteryManager.BATTERY_STATUS_FULL,
                        )
                }
            }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/**
 * 无操作进入防烧屏漂移的等待时间（30 秒）。
 *
 * Idle delay before burn-in drift activates (30 seconds).
 */
private const val INITIAL_IDLE_DELAY_MS = 30_000L

/**
 * 漂移激活后，每 10 秒移动一点。
 *
 * Moves a bit every 10 seconds while idle.
 */
private const val DRIFT_INTERVAL_MS = 10_000L

/**
 * 每次位移的平滑动画时长（600 毫秒）。
 *
 * Smooth animation duration for each step (600 ms).
 */
private const val DRIFT_ANIMATION_DURATION_MS = 600

/** 单次垂直位移的逻辑像素步长（16dp）。 / Vertical step size in density-independent pixels (16dp). */
private val DRIFT_STEP_DP = 16.dp

/** 垂直位移的最大范围（96dp）。 / Maximum vertical drift range (96dp). */
private val MAX_DRIFT_DP = 96.dp

/**
 * 时钟只显示分钟，下一次刷新对齐分钟边界以免时间滞后。
 *
 * Aligns the minute-only clock's next refresh to the minute boundary to avoid stale time.
 */
private const val CLOCK_INTERVAL_MS = 60_000L

/**
 * 末端保留少量容错；速度不会降低所需拖动距离。
 *
 * Allows a small end tolerance without letting velocity reduce the required drag distance.
 */
private const val UNLOCK_RATIO = 0.95f

/**
 * 提供至少 48dp 的滑块触控区。
 *
 * Provides a thumb touch target of at least 48dp.
 */
private val ThumbDiameter = 48.dp

/** 轨道围绕滑块的间距。 / The track's inset around the thumb. */
private val TrackPadding = 6.dp

/** 包含滑块与两侧内边距的轨道高度。 / The track height including the thumb and both insets. */
private val TrackHeight = 60.dp
