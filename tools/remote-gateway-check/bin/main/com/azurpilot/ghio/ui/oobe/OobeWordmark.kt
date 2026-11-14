package com.azurpilot.ghio.ui.oobe

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azurpilot.ghio.ui.ksu.AppText as Text
import kotlinx.coroutines.isActive
import kotlin.math.sin

/**
 * 将移动的流光色域混入 MiSans 字标，颜色只在绘制阶段变化，不重新排版文字。
 * 关闭动画或离开前台后暂停；离开页面时取消帧循环。
 *
 * Blends moving glow fields into a MiSans wordmark during drawing without relaying out text.
 * Reduced motion and backgrounding pause playback; leaving the page cancels the frame loop.
 */
@Composable
internal fun OobeWordmark(modifier: Modifier = Modifier) {
    val reduced = rememberOobeReducedMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val playing = !reduced && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            val start = withInfiniteAnimationFrameNanos { it }
            val offset = time
            while (isActive) withInfiniteAnimationFrameNanos { time = offset + (it - start) / 1_000_000_000f }
        }
    }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val colors = remember(dark) { if (dark) listOf(Color(0xFFF4F3FF), Color(0xFF95B8FF),
        Color(0xFFE1B7E1), Color(0xFFF4F3FF)) else listOf(Color(0xFF20222E), Color(0xFF4867B5),
        Color(0xFF764A73), Color(0xFF20222E)) }
    val fontScale = LocalDensity.current.fontScale
    Text("AzurPilot", modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val center = size.width * (.5f + .6f * sin(time * .7f))
            drawRect(Brush.linearGradient(colors, start = Offset(center - size.width, -size.height),
                end = Offset(center + size.width, size.height * 2f)), blendMode = BlendMode.SrcIn)
        }, color = Color.White, style = MaterialTheme.typography.displaySmall.copy(
            fontFamily = OobeBrandFont, fontWeight = FontWeight.Medium,
            fontSize = (48f / fontScale).sp, lineHeight = (58f / fontScale).sp,
            letterSpacing = (-.8f / fontScale).sp))
}
