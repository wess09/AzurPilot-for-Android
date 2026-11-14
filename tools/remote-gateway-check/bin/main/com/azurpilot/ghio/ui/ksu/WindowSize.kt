// KernelSU Manager UI (GPL-3.0), commit 6e3860443f8fea7c5fc226277e53888033796488.
// AzurPilot adaptations: package/import names, app identity and business callbacks.
package com.azurpilot.ghio.ui.ksu

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
fun shouldShowSplitPane(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val deviceDensity = LocalResources.current.displayMetrics.density
    val widthDp = windowInfo.containerSize.width / deviceDensity
    val heightDp = windowInfo.containerSize.height / deviceDensity
    return widthDp >= 840f || (widthDp >= 600f && heightDp / widthDp < 1.2f)
}

@Composable
fun useNavigationRail(enableFloatingBottomBar: Boolean): Boolean {
    return shouldShowSplitPane() && !(com.azurpilot.ghio.theme.ksu.LocalUiMode.current == com.azurpilot.ghio.theme.ksu.UiMode.Miuix && enableFloatingBottomBar)
}
