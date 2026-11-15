package com.azurpilot.ghio.remoteaccess

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.oobe.rememberOobeReducedMotion

/**
 * 在远程连接等待期间播放手机与电脑同步动画，切换页面或进入后台时暂停。
 * 资源复用 AP 的 setup-illustrations/emulator.json，来自 Windows OOBE 手机配对图示。
 * 系统关闭动画时显示第 119 帧的完整设备图形，进度仅在绘制阶段读取。
 *
 * Plays the phone and computer synchronization artwork while waiting for a remote connection.
 * Reuses AP's setup-illustrations/emulator.json, originally the Windows OOBE phone pairing art.
 * Pauses off-page and in the background. Disabled system animations show the complete devices
 * at frame 119. Animation progress is read only during drawing.
 */
@Composable
fun RemoteLinkAnimation(modifier: Modifier = Modifier, active: Boolean = true) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.remote_phone_pairing),
    )
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val reducedMotion = rememberOobeReducedMotion()
    val animation = animateLottieCompositionAsState(
        composition = composition,
        isPlaying = active && lifecycle.isAtLeast(Lifecycle.State.STARTED) && !reducedMotion,
        restartOnPlay = false,
        iterations = LottieConstants.IterateForever,
    )
    LottieAnimation(
        composition = composition,
        progress = {
            if (reducedMotion) {
                composition?.let { (119f - it.startFrame) / (it.endFrame - it.startFrame) } ?: 0f
            } else animation.progress
        },
        enableMergePaths = true,
        modifier = modifier.size(220.dp),
    )
}
