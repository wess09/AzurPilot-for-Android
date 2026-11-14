package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 单选配置复用 KSU 的主题下拉行，长列表由原生弹层滚动。
 *
 * Single-choice settings reuse KSU's themed dropdown rows; native popups scroll long lists.
 *
 * @param options 回传值及展示标签。 / Values and display labels.
 * @param selected 当前值。 / Current value.
 * @param onSelect 选择回调。 / Selection callback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> AppSingleChoiceFlow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
    enabled: Boolean = true,
    arrangement: Alignment.Horizontal = Alignment.Start,
    icon: Any? = null,
) {
    com.azurpilot.ghio.ui.ksu.AppDropdownPreference(
        title = title,
        icon = icon,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        modifier = modifier,
        items = options.map { it.second },
        selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0),
        enabled = enabled,
        onSelectedIndexChange = { index -> onSelect(options[index].first) },
    )
}
