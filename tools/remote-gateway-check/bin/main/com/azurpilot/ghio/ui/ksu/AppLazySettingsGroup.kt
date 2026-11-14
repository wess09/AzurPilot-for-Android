@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.azurpilot.ghio.ui.ksu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.material.SegmentedItem
import com.azurpilot.ghio.ui.ksu.material.SegmentedItemContainer
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor

/**
 * 将设置分组拆为带稳定键的标题和行，主线程只组合可见控件。 标题占一项，每个值占一项；调用方据此计算目录跳转下标。
 *
 * Splits settings groups into keyed headings and rows, composing only visible controls on main.
 * Each heading and value occupies one item so callers can calculate directory jump indices.
 */
fun <T> LazyListScope.appSettingsGroup(
    key: String,
    values: List<T>,
    title: @Composable () -> String,
    itemKey: (T) -> String,
    contentType: String = "settingsRow",
    content: @Composable (T) -> Unit,
) {
    if (values.isEmpty()) return
    item(key = "$key:heading", contentType = "settingsHeading") {
        val text = title()
        if (LocalUiMode.current == UiMode.Miuix) {
            top.yukonga.miuix.kmp.basic.SmallTitle(text)
        } else {
            AppText(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 13.dp, bottom = 8.dp),
            )
        }
    }
    itemsIndexed(
        items = values,
        key = { _, value -> "$key:row:${itemKey(value)}" },
        contentType = { _, _ -> contentType },
    ) { index, value ->
        AppLazySettingsItem(index = index, count = values.size) { content(value) }
    }
}

/**
 * 绘制懒列表中的单行分组外观，保留原卡片圆角和颜色。
 *
 * Draws one grouped lazy row with the original card corners and colors.
 */
@Composable
fun AppLazySettingsItem(index: Int, count: Int, content: @Composable () -> Unit) {
    if (LocalUiMode.current == UiMode.Miuix) {
        val top = if (index == 0) CardDefaults.CornerRadius else 0.dp
        val bottom = if (index == count - 1) CardDefaults.CornerRadius else 0.dp
        val colors = CardDefaults.defaultColors()
        CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
            Box(
                Modifier.fillMaxWidth().squircleSurface(
                    color = colors.color,
                    topStart = top,
                    topEnd = top,
                    bottomEnd = bottom,
                    bottomStart = bottom,
                )
            ) {
                content()
            }
        }
    } else {
        Box(Modifier.padding(bottom = if (index == count - 1) 0.dp else 2.dp)) {
            SegmentedItem(index, count) { SegmentedItemContainer(content = content) }
        }
    }
}
