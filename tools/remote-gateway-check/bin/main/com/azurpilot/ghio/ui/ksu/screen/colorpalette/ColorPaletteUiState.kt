// KernelSU Manager UI (GPL-3.0), commit 6e3860443f8fea7c5fc226277e53888033796488.
// AzurPilot adaptations: package/import names, app identity and business callbacks.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.azurpilot.ghio.ui.ksu.screen.colorpalette

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.azurpilot.ghio.ui.ksu.screen.colorpalette.AppearanceUiState
import com.azurpilot.ghio.theme.ksu.ColorMode

@Immutable
data class ColorPaletteUiState(
    val uiState: AppearanceUiState,
    val currentColorMode: ColorMode,
    val currentPaletteStyle: PaletteStyle,
    val currentColorSpec: ColorSpec.SpecVersion,
)

@Immutable
data class ColorPaletteScreenActions(
    val onBack: () -> Unit,
    val onSetThemeMode: (Int) -> Unit,
    val onSetMiuixMonet: (Boolean) -> Unit,
    val onSetKeyColor: (Int) -> Unit,
    val onSetColorMode: (ColorMode) -> Unit,
    val onSetColorStyle: (String) -> Unit,
    val onSetColorSpec: (String) -> Unit,
    val onSetEnableBlur: (Boolean) -> Unit,
    val onSetEnableFloatingBottomBar: (Boolean) -> Unit,
    val onSetEnableFloatingBottomBarBlur: (Boolean) -> Unit,
    val onSetEnablePredictiveBack: (Boolean) -> Unit,
    val onSetEnableSwipeDismiss: (Boolean) -> Unit,
    val onSetPagerInterceptionMode: (Int) -> Unit,
    val onSetPageScale: (Float) -> Unit,
)
