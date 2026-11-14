package com.azurpilot.ghio.ui.ksu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode

/** KSU 两种主题的分页标签。 / KSU page tabs for both themes. */
@Composable
fun AppTabRow(
    selectedTabIndex: Int,
    tabs: List<String>,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.TabRow(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabClick,
            modifier = modifier,
        )
    else
        com.azurpilot.ghio.ui.ksu.material.ExpressiveTabRow(
            selectedTabIndex,
            tabs,
            onTabClick,
            modifier,
        )
}
