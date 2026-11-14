package com.azurpilot.ghio.ui.components

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.AppIcon

/**
 * 区分文件传输与处理的当前步骤，图标仅作装饰，步骤文案由调用方提供。
 *
 * Identifies the active transfer or processing step. Icons are decorative; callers provide labels.
 */
enum class TransferStage {
    /** 下载文件或源码。 / Downloading files or source code. */
    DOWNLOAD,
    /** 解压归档。 / Extracting an archive. */
    EXTRACT,
    /** 检查资源或校验文件。 / Checking resources or verifying files. */
    VERIFY,
    /** 构建资源、应用更新或重启。 / Building resources, applying updates, or restarting. */
    APPLY,
}

/**
 * 使用随包附带的 useAnimations 图标显示文件处理步骤，两套 UI 共享资源并跟随主题着色。
 *
 * 固定尺寸避免步骤切换影响布局；Lottie 进度仅在绘制阶段读取。后台页面暂停播放，系统
 * 关闭动画时显示资源首帧。加载期间使用标准 Material 图标，状态说明由相邻文案承担。
 *
 * Shows transfer stages using bundled useAnimations assets, tinted for either UI theme.
 *
 * Fixed dimensions preserve layout across stages; Lottie progress is read only while drawing.
 * Background pages pause playback; disabled system animations show the asset's first frame.
 * Standard Material icons cover loading, and adjacent text describes the state.
 */
@Composable
fun AnimatedTransferIcon(
    stage: TransferStage,
    modifier: Modifier = Modifier,
    color: Color = if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary
    } else {
        androidx.compose.material3.MaterialTheme.colorScheme.primary
    },
) {
    val resolver = LocalContext.current.contentResolver
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var animatorsEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycleOwner, resolver) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = event.targetState.isAtLeast(Lifecycle.State.STARTED)
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
        }
        val animationSettings = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animatorsEnabled = Settings.Global.getFloat(
                    resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
                ) > 0f
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            animationSettings,
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            resolver.unregisterContentObserver(animationSettings)
        }
    }
    val playing = foreground && animatorsEnabled
    Crossfade(
        targetState = stage,
        modifier = modifier.size(48.dp),
        animationSpec = tween(
            durationMillis = if (playing) 150 else 0,
            easing = LinearEasing,
        ),
        label = "transferIconStage",
    ) { visibleStage ->
        val resource = when (visibleStage) {
            TransferStage.DOWNLOAD -> R.raw.transfer_download
            TransferStage.EXTRACT -> R.raw.transfer_extract
            TransferStage.VERIFY -> R.raw.transfer_verify
            TransferStage.APPLY -> R.raw.transfer_apply
        }
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resource))
        val animation = animateLottieCompositionAsState(
            composition = composition,
            isPlaying = playing,
            restartOnPlay = false,
            iterations = LottieConstants.IterateForever,
            // 归档图标原为短悬停动画，放慢并往返播放，避免循环归位时突然合上。
            speed = if (visibleStage == TransferStage.EXTRACT) 0.2f else 1f,
            reverseOnRepeat = visibleStage == TransferStage.EXTRACT,
        )
        val properties = rememberLottieDynamicProperties(
            rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, color.toArgb(), "**"),
            rememberLottieDynamicProperty(LottieProperty.COLOR, color.toArgb(), "**"),
        )
        if (composition != null) {
            LottieAnimation(
                composition = composition,
                progress = { if (animatorsEnabled) animation.progress else 0f },
                dynamicProperties = properties,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AppIcon(
                imageVector = when (visibleStage) {
                    TransferStage.DOWNLOAD -> Icons.Outlined.Download
                    TransferStage.EXTRACT -> Icons.Outlined.Archive
                    TransferStage.VERIFY -> Icons.Outlined.FindInPage
                    TransferStage.APPLY -> Icons.Outlined.Sync
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
