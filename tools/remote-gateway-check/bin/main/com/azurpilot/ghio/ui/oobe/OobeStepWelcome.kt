package com.azurpilot.ghio.ui.oobe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.component.miuix.effect.BgEffectBackground
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * 小米欢迎页使用 40/40/20 空间比例与一次品牌入场；Pixel 使用留白大标题和边缘线条。
 * 返回欢迎页不重复入场，装饰不进入无障碍树。
 *
 * Uses Xiaomi's 40/40/20 welcome proportions and a single brand entrance, or Pixel's spacious
 * headline and edge artwork. Returning skips the entrance; decoration stays out of semantics.
 */
@Composable
internal fun OobeStepWelcome(modifier: Modifier, onNext: () -> Unit, nextLabel: String,
    enabled: Boolean, onDismiss: (() -> Unit)?, dismissLabel: String,
    onNextBoundsChanged: (Rect) -> Unit = {}, exiting: Boolean = false) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    val reduced = rememberOobeReducedMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var introduced by rememberSaveable { mutableStateOf(false) }
    val scale = remember { Animatable(if (introduced || reduced || !miuix) 1f else .5f) }
    val alpha = remember { Animatable(if (introduced || reduced || !miuix) 1f else 0f) }
    val button = remember { Animatable(if (introduced || reduced || !miuix) 1f else 0f) }
    LaunchedEffect(miuix, reduced) {
        if (introduced || reduced || !miuix) {
            scale.snapTo(1f); alpha.snapTo(1f); button.snapTo(1f)
        } else {
            // 品牌与按钮沿用 OS3 首次入场时序；隐藏期间不接受继续操作。
            introduced = true
            launch { alpha.animateTo(1f, tween(300, delayMillis = 60)) }
            launch {
                scale.animateTo(.95f, tween(440, easing = Easing { sin(it * Math.PI / 2).toFloat() }))
                scale.animateTo(1f, tween(700, easing = Easing { 1f - (1f - it) * (1f - it) * (1f - it) }))
            }
            launch {
                button.animateTo(1f, tween(450, delayMillis = 1340,
                    easing = Easing { 1f - (1f - it) * (1f - it) * (1f - it) }))
            }
        }
    }
    val title = stringResource(R.string.oobe_welcome_title)
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        if (miuix) BgEffectBackground(
            dynamicBackground = !reduced && lifecycle.isAtLeast(Lifecycle.State.RESUMED),
            modifier = Modifier.matchParentSize(), isFullSize = true,
        ) { }
        Canvas(Modifier.fillMaxSize()) {
            if (!miuix) {
                val path = Path()
                val origin = Offset(size.width * .78f, size.height * .12f)
                for (i in 0..240) {
                    val angle = i * Math.PI * 2 / 240
                    val radius = size.width * .52f * (1f + .08f * cos(angle * 12).toFloat())
                    val point = origin + Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius)
                    if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.close()
                drawPath(path, colors.primary.copy(alpha = .35f), style = Stroke(1.dp.toPx()))
                drawOval(colors.tertiary.copy(alpha = .3f), topLeft = Offset(-size.width * .2f, size.height * .72f),
                    size = androidx.compose.ui.geometry.Size(size.width * 1.25f, size.width * .8f), style = Stroke(1.dp.toPx()))
            }
        }
        // 背景画入系统栏，交互内容仍避让手势区和屏幕挖孔。
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (miuix) {
            // 低矮窗口改用居中堆叠，避免字标与继续按钮按比例定位后重叠。
            val compact = maxHeight < 320.dp
            val compactGap = (maxHeight - 128.dp).coerceIn(0.dp, 24.dp)
            val brandY = if (compact) ((maxHeight - 128.dp - compactGap) / 2).coerceAtLeast(0.dp)
                else maxHeight * .4f
            val nextY = if (compact) brandY + 58.dp + compactGap
                else (maxHeight * .8f - 70.dp).coerceAtLeast(0.dp)
            Box(Modifier.align(Alignment.TopCenter).offset(y = brandY)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }
                .clearAndSetSemantics { contentDescription = title; heading() }) {
                OobeWordmark()
            }
            OobeNextButton(nextLabel, enabled && button.value == 1f && !exiting, onNext,
                Modifier.align(Alignment.TopCenter).offset(y = nextY)
                    .onGloballyPositioned { onNextBoundsChanged(it.boundsInRoot()) }
                    .graphicsLayer {
                        this.alpha = if (exiting) 0f else button.value
                        scaleX = .9f + .1f * button.value
                        scaleY = scaleX
                    })
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 32.dp)) {
                    Column(Modifier.align(Alignment.CenterStart).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
                        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 42.sp, lineHeight = 48.sp, fontWeight = FontWeight.Normal), color = colors.onBackground)
                        Spacer(Modifier.height(20.dp))
                        Text(stringResource(R.string.oobe_welcome_subtitle), style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant)
                    }
                }
                Row(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.End) { OobeNextButton(nextLabel, enabled, onNext) }
            }
        }
        if (onDismiss != null) TextButton(onDismiss, Modifier.align(Alignment.TopStart).padding(12.dp)
            .heightIn(min = 48.dp)) { Text(dismissLabel) }
        }
    }
}
