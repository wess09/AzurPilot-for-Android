package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.ksu.material.TonalCard

/**
 * 呈现向导卡片；Material 使用容器色保持浅色层次，Miuix 保留原生圆角与色彩。
 * 内容自行提供内边距，避免窄屏出现双重留白。
 *
 * Presents wizard cards with Material container colors for light-theme hierarchy and native
 * Miuix shape and colors. Content owns its padding to avoid doubled spacing on narrow screens.
 */
@Composable
internal fun OobeCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        AppCard(modifier = modifier, contentPadding = PaddingValues(0.dp), content = content)
    } else {
        TonalCard(modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(content = content)
        }
    }
}
