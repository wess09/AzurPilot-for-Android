package com.azurpilot.ghio.ui.ksu.screen.colorpalette

data class AppearanceUiState(
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = "TonalSpot",
    val colorSpec: String = "SPEC_2025",
    val enablePredictiveBack: Boolean = true,
    val enableSwipeDismiss: Boolean = true,
    val pagerInterceptionMode: Int = 1,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = false,
    val enableFloatingBottomBarBlur: Boolean = false,
    val pageScale: Float = 1f,
)
