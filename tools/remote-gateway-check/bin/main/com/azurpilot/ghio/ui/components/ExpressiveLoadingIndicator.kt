package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 默认加载指示器尺寸。 / Default loading indicator size. */
val DefaultExpressiveIndicatorSize: Dp = 40.dp

/**
 * 使用原生 Material 3 Expressive 加载动画，统一主题、形变和系统动画缩放处理。
 *
 * Uses the native Material 3 Expressive loading indicator for consistent theme, morphing, and
 * system animation scaling behavior.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier.size(DefaultExpressiveIndicatorSize),
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (
        com.azurpilot.ghio.theme.ksu.LocalUiMode.current ==
            com.azurpilot.ghio.theme.ksu.UiMode.Miuix
    ) {
        top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator(
            modifier = modifier,
            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
        )
    } else LoadingIndicator(modifier = modifier, color = color)
}
