package com.azurpilot.ghio.ui.components

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.RawRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.AppIcon
import com.azurpilot.ghio.ui.ksu.AppText
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 传递宿主按钮的点击序号，使子图标无需单独点击区域即可播放反馈。
 *
 * Passes the host button's click sequence so child icons play feedback without a separate hit area.
 */
internal val LocalActionIconTrigger = compositionLocalOf { 0 }

/**
 * 统一应用操作与设置的语义图标；资源来自固定版本的 useAnimations，加载时使用矢量兜底。
 *
 * Unifies semantic icons for app actions and settings. Bundled useAnimations assets are pinned,
 * with vector fallbacks while loading.
 */
enum class AppSymbol(@param:RawRes val animationRes: Int, val fallback: ImageVector) {
    /** 设置入口。 / Settings entry. */
    Settings(R.raw.icon_settings, Icons.Outlined.Settings),
    /** 参数选项。 / Configuration options. */
    Options(R.raw.icon_settings2, Icons.Outlined.Tune),
    /** 外观与配色。 / Appearance and colors. */
    Appearance(R.raw.icon_sun, Icons.Outlined.Palette),
    /** 显示参数。 / Display parameters. */
    Display(R.raw.icon_monitor, Icons.Outlined.Monitor),
    /** 设备与虚拟屏。 / Device and virtual display. */
    Phone(R.raw.icon_smartphone, Icons.Outlined.PhoneAndroid),
    /** 日志与文档。 / Logs and documents. */
    Logs(R.raw.icon_file_text, Icons.Outlined.Description),
    /** 后台活动与保活。 / Background activity and persistence. */
    Activity(R.raw.icon_activity, Icons.Outlined.MonitorHeart),
    /** 权限与安全。 / Permissions and security. */
    Security(R.raw.icon_shield, Icons.Outlined.Shield),
    /** 小组件与布局。 / Widgets and layout. */
    Grid(R.raw.icon_grid, Icons.Outlined.Widgets),
    /** 运行时与控制台。 / Runtime and console. */
    Terminal(R.raw.icon_terminal, Icons.Outlined.Terminal),
    /** 芯片与硬件加速。 / Chip and hardware acceleration. */
    Cpu(R.raw.icon_cpu, Icons.Outlined.Memory),
    /** 设备报告。 / Device report. */
    Report(R.raw.icon_clipboard, Icons.AutoMirrored.Outlined.Assignment),
    /** 版本与说明。 / Versions and information. */
    Info(R.raw.icon_info, Icons.Outlined.Info),
    /** 反馈与帮助。 / Feedback and help. */
    Help(R.raw.icon_help, Icons.AutoMirrored.Outlined.HelpOutline),
    /** 社区与设备协作。 / Community and device collaboration. */
    Community(R.raw.icon_users, Icons.Outlined.Groups),
    /** 语言与公网。 / Language and public networks. */
    Globe(R.raw.icon_globe, Icons.Outlined.Language),
    /** 下载资源。 / Download resources. */
    Download(R.raw.transfer_download, Icons.Outlined.Download),
    /** 导出与发送。 / Export and send. */
    Upload(R.raw.icon_upload, Icons.Outlined.Upload),
    /** 刷新、更新与重启。 / Refresh, update and restart. */
    Refresh(R.raw.transfer_apply, Icons.Outlined.Refresh),
    /** 应用软件更新。 / Apply a software update. */
    Update(R.raw.transfer_apply, Icons.Outlined.SystemUpdateAlt),
    /** 应用锁与口令。 / App lock and credentials. */
    Lock(R.raw.icon_lock, Icons.Outlined.Lock),
    /** 定时与耗时。 / Scheduling and timing. */
    Clock(R.raw.icon_clock, Icons.Outlined.Schedule),
    /** 清理与删除。 / Clean and delete. */
    Trash(R.raw.icon_trash2, Icons.Outlined.DeleteOutline),
    /** 息屏与夜间。 / Screen saver and night mode. */
    Night(R.raw.icon_moon, Icons.Outlined.NightsStay),
    /** 启动与供电。 / Startup and power. */
    Power(R.raw.icon_power, Icons.Outlined.PowerSettingsNew),
    /** 主页。 / Home. */
    Home(R.raw.icon_home, Icons.Outlined.Home),
    /** 复制信息。 / Copy information. */
    Copy(R.raw.icon_copy, Icons.Outlined.ContentCopy),
    /** 保存配置。 / Save configuration. */
    Save(R.raw.icon_save, Icons.Outlined.Save),
    /** 网络地址与关联。 / Network addresses and links. */
    Link(R.raw.icon_link, Icons.Outlined.Link),
    /** 调试与工具。 / Diagnostics and tools. */
    Tools(R.raw.icon_tool, Icons.Outlined.Build),
    /** 播放与暂停。 / Play and pause. */
    Play(R.raw.icon_play_pause, Icons.Outlined.PlayArrow),
    /** 验证与完成。 / Verification and completion. */
    Check(R.raw.icon_check_circle, Icons.Outlined.CheckCircle),
    /** 局域网连接。 / Local network connectivity. */
    Wifi(R.raw.icon_wifi, Icons.Outlined.Wifi),
    /** 音频与音量。 / Audio and volume. */
    Audio(R.raw.icon_volume, Icons.AutoMirrored.Outlined.VolumeUp),
    /** 多进程与叠层。 / Multiple processes and layers. */
    Layers(R.raw.icon_layers, Icons.Outlined.Layers),
    /** 编辑参数。 / Edit parameters. */
    Edit(R.raw.icon_edit, Icons.Outlined.Edit),
}

/**
 * 在主线程绘制随主题着色的 useAnimations 图标；触发值变化播放一次，循环只用于忙碌状态。
 *
 * 初始与关闭动画时保留完整静态图形。进度只在绘制阶段读取，离开前台停止播放；快速切换
 * 从当前进度继续，并按剩余距离使用资源自身时长。系统时长缩放由 Compose 应用一次。
 * 图标默认装饰性，独立图标按钮由调用方提供无障碍名称。
 *
 * Draws themed useAnimations icons on main. Trigger changes play once; loops indicate busy states.
 * Initial and reduced-motion states retain a complete static glyph. Progress is read during draw,
 * background playback stops, and rapid changes use the remaining distance at the asset's native
 * duration. Compose applies system duration scaling once. Icons are decorative by default;
 * standalone icon buttons supply an accessibility label.
 */
@Composable
fun AppSymbolIcon(
    symbol: AppSymbol,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.theme.LocalContentColor.current
    } else {
        androidx.compose.material3.LocalContentColor.current
    },
    animationKey: Any? = null,
    active: Boolean? = null,
    loop: Boolean = false,
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(symbol.animationRes))
    val motionAllowed = rememberIconMotionAllowed()
    val progress = remember(symbol) { Animatable(if (active == true) 1f else 0f) }
    var previousKey by remember(symbol) { mutableStateOf(animationKey) }
    var previousActive by remember(symbol) { mutableStateOf(active) }
    LaunchedEffect(composition, motionAllowed, animationKey, active, loop) {
        val changed = previousKey != animationKey || previousActive != active
        previousKey = animationKey
        previousActive = active
        val loaded = composition ?: return@LaunchedEffect
        val durationMillis = loaded.duration.roundToInt().coerceAtLeast(1)
        if (!motionAllowed) {
            progress.snapTo(if (active == true) 1f else 0f)
        } else if (loop) {
            while (true) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(durationMillis, easing = LinearEasing))
                // 动画缩放归零时让出主线程，确保设置观察器能及时取消循环。
                yield()
            }
        } else if (changed) {
            if (active == null) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(durationMillis, easing = LinearEasing))
            } else {
                val target = if (active) 1f else 0f
                val remainingMillis = (loaded.duration * abs(target - progress.value))
                    .roundToInt().coerceAtLeast(1)
                progress.animateTo(target, tween(remainingMillis, easing = LinearEasing))
            }
        }
    }
    val properties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, tint.toArgb(), "**"),
        rememberLottieDynamicProperty(LottieProperty.COLOR, tint.toArgb(), "**"),
    )
    val iconModifier = modifier.size(24.dp).semantics {
        contentDescription?.let { this.contentDescription = it }
    }
    if (composition == null) {
        AppIcon(symbol.fallback, contentDescription, modifier.size(24.dp), tint)
    } else {
        LottieAnimation(
            composition = composition,
            progress = { progress.value },
            dynamicProperties = properties,
            // 上游 32 单位画布含四边留白；放大绘制使图形和线宽对齐 MD3 的 24 dp 图标。
            modifier = iconModifier.drawWithContent {
                scale(4f / 3f) { this@drawWithContent.drawContent() }
            },
        )
    }
}

/**
 * 为设置行提供 24 dp 前导图标，支持动态 AppSymbol 与静态 ImageVector。
 * 启用态采用主色，其余继承主题次要前景色。
 *
 * Supplies a 24 dp leading icon for preference rows, supporting dynamic AppSymbol and static
 * ImageVector. Uses primary for enabled states and the theme's secondary foreground color otherwise.
 */
@Composable
fun AppPreferenceIcon(
    icon: Any?,
    modifier: Modifier = Modifier,
    animationKey: Any? = null,
    active: Boolean? = null,
) {
    if (icon == null) return
    val color = if (LocalUiMode.current == UiMode.Miuix) {
        val colors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
        if (active == true) colors.primary else colors.onSurfaceVariantSummary
    } else {
        if (active == true) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    }
    when (icon) {
        is AppSymbol -> AppSymbolIcon(
            symbol = icon,
            modifier = modifier,
            tint = color,
            animationKey = animationKey,
            active = active,
        )
        is ImageVector -> AppIcon(
            imageVector = icon,
            contentDescription = null,
            modifier = modifier.size(24.dp),
            tint = color,
        )
    }
}

/**
 * 兼容旧版仅传 AppSymbol 的调用。 / Compatibility overload for AppSymbol callers.
 */
@Composable
fun AppPreferenceIcon(
    symbol: AppSymbol,
    animationKey: Any? = null,
    active: Boolean? = null,
) = AppPreferenceIcon(
    icon = symbol,
    animationKey = animationKey,
    active = active,
)

/**
 * 将操作图标与按钮文案保持统一间距，颜色继承宿主按钮。
 *
 * Keeps action icons and button labels evenly spaced, inheriting the host button's content color.
 */
@Composable
fun AppActionLabel(symbol: AppSymbol, text: String, busy: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
    ) {
        AppSymbolIcon(
            symbol, modifier = Modifier.size(ButtonDefaults.IconSize), loop = busy,
            animationKey = LocalActionIconTrigger.current,
        )
        AppText(text)
    }
}

@Composable
private fun rememberIconMotionAllowed(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(owner, resolver) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            foreground = event.targetState.isAtLeast(Lifecycle.State.RESUMED)
            enabled = ValueAnimator.areAnimatorsEnabled()
        }
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = Settings.Global.getFloat(
                    resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
                ) > 0f
            }
        }
        owner.lifecycle.addObserver(lifecycleObserver)
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, settingsObserver,
        )
        onDispose {
            owner.lifecycle.removeObserver(lifecycleObserver)
            resolver.unregisterContentObserver(settingsObserver)
        }
    }
    return foreground && enabled
}
