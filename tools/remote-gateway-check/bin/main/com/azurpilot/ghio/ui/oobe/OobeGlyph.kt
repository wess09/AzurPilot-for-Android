package com.azurpilot.ghio.ui.oobe

import androidx.annotation.RawRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.azurpilot.ghio.R
import kotlin.math.roundToInt

/**
 * 映射 OS3 原始矢量动画与本项目的快速上手指南针。
 *
 * Maps original OS3 vector animations and this project's quick-start compass.
 */
internal enum class OobeGlyph(@param:RawRes val resource: Int) {
    LANGUAGE(R.raw.oobe_os3_language),
    AGREEMENT(R.raw.oobe_os3_terms),
    APPEARANCE(R.raw.oobe_os3_settings),
    PRIVILEGE(R.raw.oobe_os3_password),
    TOUR(R.raw.oobe_tour),
}

/**
 * 直接播放原始 70dp Lottie；保留资源的路径、渐变和关键帧，不动态重着色。
 * 页面转场结束后从首帧播放，进入后台暂停并保留进度；关闭动画时显示完整图形。
 *
 * Plays the original 70dp Lottie, preserving paths, gradients, and keyframes without recoloring.
 * Starts after page entry settles, pausing in the background without losing progress.
 * Reduced motion shows the complete glyph.
 */
@Composable
internal fun OobeActivationGlyph(glyph: OobeGlyph, modifier: Modifier = Modifier) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(glyph.resource))
    val progress = rememberOobeGlyphProgress(composition)
    LottieAnimation(composition, progress = { progress.value }, modifier = modifier.size(70.dp),
        enableMergePaths = true)
}

/**
 * 在主线程保留图标播放进度，等待页面可见并在恢复前台后播放剩余帧。
 *
 * Retains glyph progress on the main thread, waiting for page entry and resuming remaining frames
 * after returning to the foreground.
 */
@Composable
internal fun rememberOobeGlyphProgress(composition: LottieComposition?): State<Float> {
    val reduced = rememberOobeReducedMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val pageActive = LocalOobePageActive.current
    val progress = remember(composition) { Animatable(0f) }
    LaunchedEffect(composition, reduced, lifecycle, pageActive) {
        val loaded = composition ?: return@LaunchedEffect
        // 末尾排他帧可能不显示图层，使用最后一个有效整帧作为静止图形。
        val complete = ((loaded.endFrame - 1f - loaded.startFrame) /
            (loaded.endFrame - loaded.startFrame)).coerceIn(0f, 1f)
        if (reduced) progress.snapTo(complete)
        else if (pageActive && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            // 转场和后台不消耗动画；恢复时只播放剩余帧，避免提前标记完成而跳到末帧。
            val remaining = (loaded.duration * (complete - progress.value)).roundToInt()
            if (remaining > 0) progress.animateTo(complete, tween(remaining, easing = LinearEasing))
        }
    }
    return progress.asState()
}
