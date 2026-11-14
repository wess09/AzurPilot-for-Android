package com.azurpilot.ghio.ui.ksu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.components.AppPreferenceIcon

/** KSU 两种主题的下拉设置行。 / KSU dropdown preference for both themes. */
@Composable
fun AppDropdownPreference(
    title: String,
    modifier: Modifier = Modifier,
    items: List<String>,
    summary: String? = null,
    insideMargin: androidx.compose.foundation.layout.PaddingValues =
        top.yukonga.miuix.kmp.basic.BasicComponentDefaults.InsideMargin,
    selectedIndex: Int,
    enabled: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
    icon: Any? = null,
) {
    if (items.isEmpty()) return
    androidx.compose.foundation.layout.Box(modifier) {
        if (LocalUiMode.current == UiMode.Miuix)
            top.yukonga.miuix.kmp.preference.WindowDropdownPreference(
                title = title.ifEmpty { items[selectedIndex.coerceIn(items.indices)] },
                showValue = title.isNotEmpty(),
                summary = summary,
                startAction = icon?.let { { AppPreferenceIcon(it, animationKey = selectedIndex) } },
                insideMargin = insideMargin,
                maxHeight = 360.dp,
                items = items,
                selectedIndex = selectedIndex,
                enabled = enabled,
                onSelectedIndexChange = onSelectedIndexChange,
            )
        else
            com.azurpilot.ghio.ui.ksu.material.SegmentedDropdownItem(
                title = title,
                leadingContent = icon?.let { { AppPreferenceIcon(it, animationKey = selectedIndex) } },
                summary = summary,
                items = items,
                selectedIndex = selectedIndex,
                enabled = enabled,
                onItemSelected = onSelectedIndexChange,
            )
    }
}
