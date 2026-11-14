package com.azurpilot.ghio.ui.oobe

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.exp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.hypot

/**
 * OS3.0.305.0 的圆角视图启动弹簧；以原机 SpringInterpolator 的 497ms 采样校验。
 * 保留原框架的秒数映射与终点处理，不使用普通翻页曲线或归一化近似。
 *
 * The rounded-view launch spring from OS3.0.305.0, checked against the device's 497ms
 * SpringInterpolator samples. Preserves its time mapping and endpoint without normalization.
 */
internal val OobeWelcomeEasing = Easing { fraction ->
    if (fraction == 1f) 1f else {
        val damping = .95f.toDouble()
        val omega = 2.0 * Math.PI / .39f.toDouble()
        val decay = -damping * omega
        val frequency = omega * sqrt(1.0 - damping * damping)
        val seconds = (OobeWelcomeRevealDuration / 1000f * fraction).toDouble()
        (1.0 + exp(decay * seconds) *
            (-cos(frequency * seconds) + decay / frequency * sin(frequency * seconds))).toFloat()
    }
}

/**
 * 对齐 OS3 语言页 ViewPropertyAnimator 默认的加减速遮罩淡出。
 *
 * Matches the default accelerate-decelerate overlay fade used by OS3's ViewPropertyAnimator.
 */
internal val OobeWelcomeOverlayEasing = Easing { fraction ->
    ((cos((fraction + 1.0) * Math.PI) / 2.0) + .5).toFloat()
}

/**
 * 指示当前页面已结束转场，允许装饰动画开始；独立预览默认可播放。
 *
 * Allows decorative playback after page entry settles; standalone previews play by default.
 */
internal val LocalOobePageActive = compositionLocalOf { true }

/**
 * 同步 OS3 类型 202 的中心、宽度与长宽比，再用同一缩放值计算局部裁剪和圆角。
 * 返回时沿用原版按对角线比例收拢圆角的计算；屏幕边缘由宿主窗口裁剪。
 *
 * Matches OS3 type 202's center, width and aspect ratio, then derives local cropping and radius
 * from the uniform scale. Closing uses its diagonal-relative radius; the host clips screen edges.
 */
internal fun oobeWelcomeFrame(button: Rect, size: Size, progress: Float,
    closing: Boolean = false): OobeWelcomeFrame {
    val fraction = progress.coerceIn(0f, 1f)
    val width = interpolate(button.width, size.width, fraction)
    val ratio = interpolate(button.width / button.height, size.width / size.height, fraction)
    val height = width / ratio
    val centerX = interpolate(button.center.x, size.width * .5f, fraction)
    val centerY = interpolate(button.center.y, size.height * .5f, fraction)
    val widthScale = width / size.width
    val heightScale = height / size.height
    val scale = maxOf(widthScale, heightScale)
    val buttonRadius = minOf(button.width, button.height) * .5f
    val radius = if (closing) {
        hypot(width, height) * interpolate(buttonRadius / hypot(button.width, button.height), 0f, fraction)
    } else buttonRadius * (1f - (height - button.height) / (size.height - button.height))
    return OobeWelcomeFrame(
        bounds = Rect(centerX - width * .5f, centerY - height * .5f,
            centerX + width * .5f, centerY + height * .5f),
        scale = scale,
        // SurfaceControl 的 crop 使用整数像素，Compose 也在变换前截断局部裁剪尺寸。
        crop = if (widthScale > heightScale) Size(size.width, (height / scale).toInt().toFloat())
            else Size((width / scale).toInt().toFloat(), size.height),
        radius = radius / scale,
    )
}

/** 保留原框架的运算顺序，避免裁剪取整时产生一像素误差。 / Preserves native arithmetic before crop rounding. */
private fun interpolate(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/** 圆角展开在当前帧的绘制参数。 / Drawing parameters for a rounded launch frame. */
internal data class OobeWelcomeFrame(val bounds: Rect, val scale: Float, val crop: Size,
    private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        return Outline.Rounded(RoundRect(Rect(androidx.compose.ui.geometry.Offset.Zero, crop), CornerRadius(radius)))
    }
}

/**
 * 原机系统弹簧返回 497ms；语言页自己的遮罩另用 600ms。
 *
 * The device's system spring returns 497ms; the language overlay separately takes 600ms.
 */
internal const val OobeWelcomeRevealDuration = 497
internal const val OobeWelcomeOverlayDuration = 600

/**
 * 使用临界阻尼的标准阶跃响应，保持整页移动不回弹。
 * response=0.55 的弹簧收敛后映射到 875ms 页面时长；数学实现由本项目独立编写。
 *
 * Uses the standard critically damped step response for page motion without overshoot.
 * Maps the settled response=0.55 spring to the 875ms page duration, implemented independently.
 */
internal val HyperOsPageEasing = Easing { fraction ->
    val time = fraction * 9.24f
    val end = 1f - 10.24f * exp(-9.24f)
    ((1f - (1f + time) * exp(-time)) / end).coerceIn(0f, 1f)
}

/**
 * 监听系统关闭动画的设置，关闭装饰入场和页面位移。
 *
 * Observes the system animation setting to disable decorative entrances and page translation.
 */
@Composable
internal fun rememberOobeReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun disabled() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember(resolver) { mutableStateOf(disabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = disabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
