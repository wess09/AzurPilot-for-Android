// KernelSU Manager UI (GPL-3.0), commit 6e3860443f8fea7c5fc226277e53888033796488.
// AzurPilot adaptations: package/import names, app identity and business callbacks.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

// Mirrored from compose-miuix-ui example.

package com.azurpilot.ghio.ui.ksu.component.miuix.effect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.azurpilot.ghio.theme.ksu.isInDarkTheme
import com.azurpilot.ghio.ui.ksu.shouldShowSplitPane
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BgEffectBackground(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    bgModifier: Modifier = Modifier,
    isFullSize: Boolean = false,
    effectBackground: Boolean = true,
    alpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    val deviceType = if (shouldShowSplitPane()) DeviceType.PAD else DeviceType.PHONE
    val isDarkTheme = isInDarkTheme()
    if (!isRuntimeShaderSupported()) {
        Box(modifier = modifier) {
            BgEffectFallback(dynamicBackground, BgEffectConfig.get(deviceType, isDarkTheme),
                Modifier.fillMaxSize().then(bgModifier), effectBackground, alpha)
            content()
        }
        return
    }
    Box(
        modifier = modifier,
    ) {
        val surface = MiuixTheme.colorScheme.surface
        val painter = remember { BgEffectPainter() }

        val preset = remember(deviceType, isDarkTheme) {
            BgEffectConfig.get(deviceType, isDarkTheme)
        }

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier)
                .bgEffectDraw(
                    painter = painter,
                    preset = preset,
                    deviceType = deviceType,
                    isDarkTheme = isDarkTheme,
                    surface = surface,
                    effectBackground = effectBackground,
                    isFullSize = isFullSize,
                    playing = dynamicBackground,
                    alpha = alpha,
                ),
        )
        content()
    }
}
