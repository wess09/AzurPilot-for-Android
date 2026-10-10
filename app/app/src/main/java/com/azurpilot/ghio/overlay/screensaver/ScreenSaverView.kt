package com.azurpilot.ghio.overlay.screensaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 渲染最低亮度遮罩中的 MD3 时钟、任务状态和滑块；在主线程组合。
 *
 * 所有可见内容每十分钟整体换位，漂移边界来自实际布局尺寸；拖动期间延后换位。
 * 时间独立按分钟更新，任务仅取正在运行的实例，避免显示用户正在浏览的其他配置。
 *
 * Renders the MD3 clock, task status, and unlock slider inside the dim overlay on the main thread.
 *
 * All visible content changes position every ten minutes within measured layout bounds; movement
 * waits until dragging finishes. The clock updates independently each minute, and task information
 * belongs to the running instance rather than another configuration the user is browsing.
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
    val battery = rememberBatteryState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val timeFormatter = remember(locale, DateFormat.is24HourFormat(context)) {
        DateTimeFormatter.ofPattern(
            if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", locale,
        )
    }
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMEd"), locale)
    }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var position by remember { mutableIntStateOf(0) }
    var interacting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(CLOCK_INTERVAL_MS - System.currentTimeMillis().mod(CLOCK_INTERVAL_MS))
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(DRIFT_INTERVAL_MS)
            // 滑块在手指下面换位会中断手势，所以等松手与回弹结束后再移动。
            while (interacting) delay(100L)
            position = (position + 1) % BurnInPositions.size
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(AppTokens.Spacing.lg)
                .onSizeChanged { bounds = it },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset {
                        val (x, y) = BurnInPositions[position]
                        IntOffset(
                            ((bounds.width - contentSize.width).coerceAtLeast(0) * x / 2).roundToInt(),
                            ((bounds.height - contentSize.height).coerceAtLeast(0) * y / 2).roundToInt(),
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
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = now.format(dateFormatter),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (battery.isCharging) {
                            Icons.Outlined.BatteryChargingFull
                        } else {
                            Icons.Outlined.BatteryStd
                        },
                        contentDescription = if (battery.isCharging) {
                            stringResource(R.string.screensaver_charging)
                        } else null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(AppTokens.IconSize.sm),
                    )
                    Text(
                        text = stringResource(R.string.screensaver_battery, battery.level),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
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
 * Displays the running instance's task icon and name with offline, idle, waiting, and tool fallbacks.
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
    val task = if (run.runnerAlive && connected) {
        instance?.takeIf { it.status == AzurPilotStatus.Running }?.currentTask
            ?.takeIf { it.isNotBlank() }
            ?: overview?.takeIf {
                it.instance == run.runningConfig && it.status == AzurPilotStatus.Running
            }?.tasks?.firstOrNull { it.state == AzurPilotTaskState.Running }?.name
    } else null
    val (icon, label) = when {
        !run.reachable -> Icons.Outlined.CloudOff to stringResource(R.string.screensaver_connecting)
        run.toolAlive -> Icons.Outlined.TouchApp to stringResource(
            if (run.toolName == AzurPilotRunState.TOOL_EVENT_STORY) {
                R.string.tool_event_story
            } else R.string.tool_semi_auto,
        )
        task != null -> Icons.Outlined.TaskAlt to (schema?.taskTitle(task) ?: task)
        run.runnerAlive -> Icons.Outlined.Schedule to stringResource(R.string.screensaver_running)
        else -> Icons.Outlined.NightsStay to stringResource(R.string.screensaver_idle)
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(AppTokens.Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                    modifier = Modifier.padding(AppTokens.Spacing.sm).size(AppTokens.IconSize.md),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = run.runningConfig ?: stringResource(R.string.screensaver_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
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
        modifier = modifier
            .height(TrackHeight)
            .onSizeChanged { trackWidth = it.width.toFloat() }
            .semantics(mergeDescendants = true) {
                customActions = listOf(CustomAccessibilityAction(unlockLabel) { unlock(); true })
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
    ) {
        Box {
            Text(
                text = unlockLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(start = ThumbDiameter, end = AppTokens.Spacing.sm)
                    .alpha((1f - progress * 2).coerceIn(0f, 1f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(TrackPadding)
                    .offset { IntOffset(displayOffset.roundToInt(), 0) }
                    .size(ThumbDiameter)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
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
                            onDragEnd = { release(maxOffset > 0f && offset >= maxOffset * UNLOCK_RATIO) },
                            onDragCancel = { release(false) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (progress > 0f) {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    } else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
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
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                if (scale <= 0 || level < 0) return
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                state = BatteryState(
                    level = (level * 100 / scale).coerceIn(0, 100),
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
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
 * 每十分钟移动所有可见内容，满足长时间挂机的防烧屏间隔。
 *
 * Moves all visible content every ten minutes during long automation sessions.
 */
private const val DRIFT_INTERVAL_MS = 10 * 60_000L

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

/**
 * 在实际剩余空间内交替访问不同区域，保证连续两次不会停在同一位置。
 *
 * Alternates regions within the available space so consecutive positions differ.
 */
private val BurnInPositions = listOf(
    0f to 0f, -1f to -1f, 1f to 1f, 0f to -1f, -1f to 1f,
    1f to -1f, 0f to 1f, -1f to 0f, 1f to 0f,
)
