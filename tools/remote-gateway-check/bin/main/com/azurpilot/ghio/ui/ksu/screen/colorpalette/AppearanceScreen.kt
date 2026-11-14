package com.azurpilot.ghio.ui.ksu.screen.colorpalette

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.settings.AppSettings
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.ksu.*
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 外观页的本应用存储适配。 / App storage adapter for appearance screens. */
@Composable
fun AppearanceScreen(onBack: () -> Unit, manager: AppSettingsManager = koinInject()) {
    val settings by manager.appearance.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val activity = androidx.activity.compose.LocalActivity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    fun update(change: (AppSettings) -> AppSettings) {
        scope.launch { manager.setAppearance(change) }
    }
    val state =
        ColorPaletteUiState(
            uiState =
                AppearanceUiState(
                    themeMode = settings.colorMode.toIntOrNull() ?: LocalColorMode.current,
                    miuixMonet = settings.miuixMonet.toBoolean(),
                    keyColor = settings.keyColor.toIntOrNull() ?: 0,
                    colorStyle = settings.colorStyle,
                    colorSpec = settings.colorSpec,
                    enablePredictiveBack = settings.enablePredictiveBack.toBoolean(),
                    enableSwipeDismiss = settings.enableSwipeDismiss.toBoolean(),
                    pagerInterceptionMode = settings.pagerInterceptionMode.toIntOrNull() ?: 1,
                    enableBlur = settings.enableBlur.toBoolean(),
                    enableFloatingBottomBar = settings.enableFloatingBottomBar.toBoolean(),
                    enableFloatingBottomBarBlur = settings.enableFloatingBottomBarBlur.toBoolean(),
                    pageScale = settings.pageScale.toFloatOrNull() ?: 1f,
                ),
            currentColorMode =
                ColorMode.fromValue(settings.colorMode.toIntOrNull() ?: LocalColorMode.current),
            currentPaletteStyle =
                runCatching { PaletteStyle.valueOf(settings.colorStyle) }
                    .getOrDefault(PaletteStyle.TonalSpot),
            currentColorSpec =
                runCatching { ColorSpec.SpecVersion.valueOf(settings.colorSpec) }
                    .getOrDefault(ColorSpec.SpecVersion.SPEC_2025),
        )
    val actions =
        ColorPaletteScreenActions(
            onBack = onBack,
            onSetThemeMode = { value -> update { it.copy(colorMode = value.toString()) } },
            onSetColorMode = { value -> update { it.copy(colorMode = value.value.toString()) } },
            onSetMiuixMonet = { value -> update { it.copy(miuixMonet = value.toString()) } },
            onSetKeyColor = { value -> update { it.copy(keyColor = value.toString()) } },
            onSetColorStyle = { value -> update { it.copy(colorStyle = value) } },
            onSetColorSpec = { value -> update { it.copy(colorSpec = value) } },
            onSetEnableBlur = { value -> update { it.copy(enableBlur = value.toString()) } },
            onSetEnableFloatingBottomBar = { value ->
                update { it.copy(enableFloatingBottomBar = value.toString()) }
            },
            onSetEnableFloatingBottomBarBlur = { value ->
                update { it.copy(enableFloatingBottomBarBlur = value.toString()) }
            },
            onSetEnablePredictiveBack = { value ->
                scope.launch {
                    manager.setAppearance { it.copy(enablePredictiveBack = value.toString()) }
                    com.azurpilot.ghio.ui.ksu.setEnableOnBackInvokedCallback(
                        context.applicationInfo,
                        value,
                    )
                    activity?.recreate()
                }
            },
            onSetEnableSwipeDismiss = { value ->
                update { it.copy(enableSwipeDismiss = value.toString()) }
            },
            onSetPagerInterceptionMode = { value ->
                update { it.copy(pagerInterceptionMode = value.toString()) }
            },
            onSetPageScale = { value ->
                update { it.copy(pageScale = value.coerceIn(0.8f, 1.1f).toString()) }
            },
        )
    when (LocalUiMode.current) {
        UiMode.Miuix -> ColorPaletteScreenMiuix(state, actions)
        UiMode.Material -> ColorPaletteScreenMaterial(state, actions)
    }
}
