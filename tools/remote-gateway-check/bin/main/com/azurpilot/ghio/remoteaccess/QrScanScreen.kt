package com.azurpilot.ghio.remoteaccess

import android.animation.ValueAnimator
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.AppTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 保存本次识别的内存截图和预览坐标，动画期间不再读取变化的相机帧。
 *
 * Keeps an in-memory crop and preview coordinates so feedback never follows changing camera frames.
 *
 * @property secret 本次有效密钥。 / The validated secret.
 * @property bounds 二维码在全屏预览中的边界。 / QR bounds in full-screen preview coordinates.
 * @property thumbnail 仅用于本次识别反馈的内存截图。 / An in-memory crop used only for this feedback.
 */
internal data class QrScanCapture(val secret: String, val bounds: Rect, val thumbnail: ImageBitmap?)

/**
 * 以全屏预览为主，顶栏仅保留关闭与补光，底部提供扫描提示；识别反馈只出现一次。
 * 黑白相机控件使用 48dp 点击区，说明沿用主题字阶与应用间距，不限制可识别区域。
 *
 * Prioritizes full-screen preview with close and torch controls, a bottom hint, and one recognition
 * response. Monochrome controls use 48dp targets, theme typography and app spacing without limiting
 * the scannable area.
 */
@Composable
internal fun QrScanScreen(
    allowed: Boolean,
    failed: Boolean,
    flashAvailable: Boolean,
    torchEnabled: Boolean,
    capture: QrScanCapture?,
    onPreviewReady: (PreviewView) -> Unit,
    onClose: () -> Unit,
    onToggleTorch: () -> Unit,
    onRequestPermission: () -> Unit,
    onCaptureFinished: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed) AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    doOnLayout { onPreviewReady(this) }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (capture != null) QrRecognitionOverlay(capture, onCaptureFinished)

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(AppTokens.Spacing.xl)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CameraAction(onClose, stringResource(R.string.common_close)) {
                    Icon(Icons.Outlined.Close, null, tint = Color.White)
                }
                if (allowed && !failed && flashAvailable) CameraAction(
                    onToggleTorch,
                    stringResource(if (torchEnabled) R.string.remote_pair_torch_off else R.string.remote_pair_torch_on),
                    enabled = capture == null,
                    active = torchEnabled,
                ) {
                    Icon(if (torchEnabled) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
                        null, tint = if (torchEnabled) Color.Black else Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!allowed || failed) {
                Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 320.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md)) {
                    Text(stringResource(if (!allowed) R.string.remote_pair_camera_permission
                        else R.string.remote_pair_camera_failed),
                        color = Color.White, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge)
                    if (!allowed) TextButton(onRequestPermission) {
                        Text(stringResource(R.string.remote_pair_camera_allow), color = Color.White)
                    }
                }
                Spacer(Modifier.weight(1f))
            }
            Column(
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .55f))
                    .padding(AppTokens.Spacing.xl)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null, tint = Color.White,
                    modifier = Modifier.size(AppTokens.IconSize.md))
                Text(stringResource(if (capture == null) R.string.remote_pair_scan
                    else R.string.remote_pair_scan_detected), color = Color.White,
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (capture == null) Text(stringResource(R.string.remote_pair_scan_hint),
                    color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(AppTokens.Spacing.xl))
        }
    }
}

/**
 * 黑白相机控件保持触摸与读屏语义，关闭始终可中断识别。
 *
 * Accessible camera controls keep close available during recognition.
 */
@Composable
private fun CameraAction(onClick: () -> Unit, label: String, enabled: Boolean = true,
    active: Boolean = false, content: @Composable () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)
        .clip(CircleShape).background(if (active) Color.White else Color.Black.copy(alpha = .5f))
        .semantics { contentDescription = label }) { content() }
}

/**
 * 先在真实识别位置显现白框，再只用平移与缩放移至中央；结束后才返回配对结果。
 * 系统关闭动画时仅显示居中的确认帧，退到后台或关闭页面不会提交结果。
 *
 * Reveals a white frame at the detected location, then translates and scales it to center before
 * returning the pairing result. Reduced motion keeps a centered confirmation; background or close
 * never submits a result.
 */
@Composable
private fun QrRecognitionOverlay(capture: QrScanCapture, onComplete: () -> Unit) {
    val opacity = remember(capture) { Animatable(0f) }
    val travel = remember(capture) { Animatable(0f) }
    val complete by rememberUpdatedState(onComplete)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(capture) {
        val motion = ValueAnimator.areAnimatorsEnabled()
        if (!motion) travel.snapTo(1f)
        opacity.animateTo(1f, tween(120, easing = QrAcquireEasing))
        if (motion) travel.animateTo(1f, tween(280, easing = QrTransferEasing))
        delay(120)
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        complete()
    }
    BoxWithConstraints(Modifier.fillMaxSize().drawBehind {
        drawRect(Color.Black.copy(alpha = .4f * travel.value))
    }) {
        val density = LocalDensity.current
        val source = capture.bounds
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val targetSide = minOf(with(density) { 220.dp.toPx() }, viewportWidth * .6f, viewportHeight * .4f)
        val targetScale = targetSide / maxOf(source.width, source.height)
        val shape = RoundedCornerShape(12.dp)
        Box(Modifier.requiredSize(with(density) { source.width.toDp() }, with(density) { source.height.toDp() })
            .graphicsLayer {
                val progress = travel.value
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = source.left + (viewportWidth * .5f - source.width * targetScale * .5f - source.left) * progress
                translationY = source.top + (viewportHeight * .5f - source.height * targetScale * .5f - source.top) * progress
                scaleX = 1f + (targetScale - 1f) * progress
                scaleY = scaleX
                alpha = opacity.value
            }.clip(shape).border(2.dp, Color.White, shape).padding(3.dp)) {
            capture.thumbnail?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize().clip(shape),
                    contentScale = ContentScale.FillBounds)
            }
        }
    }
}

/** 识别反馈快速出现；屏内转移使用加减速曲线，均只运行一次。 / One-shot reveal and on-screen movement curves. */
private val QrAcquireEasing = CubicBezierEasing(.23f, 1f, .32f, 1f)
private val QrTransferEasing = CubicBezierEasing(.77f, 0f, .175f, 1f)
