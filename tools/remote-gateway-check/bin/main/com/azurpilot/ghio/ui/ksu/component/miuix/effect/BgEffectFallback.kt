package com.azurpilot.ghio.ui.ksu.component.miuix.effect

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * 在不支持 RuntimeShader 的设备上复用流光预设，绘制四个柔和移动色域。
 * 暂停时保持最后一帧，时间只在绘制阶段读取。
 *
 * Reuses glow presets as four soft moving color fields on devices without RuntimeShader.
 * Pausing retains the last frame; time is read only during drawing.
 */
@Composable
internal fun BgEffectFallback(playing: Boolean, preset: BgEffectConfig.Config, modifier: Modifier,
    visible: Boolean, alpha: () -> Float) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, visible) {
        if (!playing || !visible) return@LaunchedEffect
        val start = withInfiniteAnimationFrameNanos { it }
        val offset = time
        while (isActive) withInfiniteAnimationFrameNanos { time = offset + (it - start) / 1_000_000_000f }
    }
    val surface = MiuixTheme.colorScheme.surface
    Canvas(modifier) {
        drawRect(surface)
        if (visible) {
            val elapsed = time
            val stage = elapsed / preset.colorInterpPeriod
            val base = stage.toInt()
            val fraction = (1f - cos((stage - base) * Math.PI).toFloat()) * .5f
            fun cycleColors(index: Int) = when (index.mod(4)) {
                1 -> preset.colors1
                3 -> preset.colors3
                else -> preset.colors2
            }
            val start = cycleColors(base)
            val end = cycleColors(base + 1)
            for (index in 0..3) {
                val x = preset.points[index * 3] + sin(elapsed + preset.points[index * 3 + 1]) * preset.pointOffset
                val y = preset.points[index * 3 + 1] + cos(elapsed + x) * preset.pointOffset
                fun channel(offset: Int): Float {
                    val channel = index * 4 + offset
                    return start[channel] + (end[channel] - start[channel]) * fraction
                }
                val color = Color(channel(0), channel(1), channel(2), channel(3) * alpha() * .65f)
                drawRect(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)),
                    center = Offset(x * size.width, y * size.height * .8f), radius = size.maxDimension * .65f))
            }
        }
    }
}
