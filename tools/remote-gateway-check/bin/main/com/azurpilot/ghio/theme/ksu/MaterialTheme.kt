// KernelSU Manager UI (GPL-3.0), commit 6e3860443f8fea7c5fc226277e53888033796488.
// AzurPilot adaptations: package names, platform seed fallback and omitted WebUI CSS bridge.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.azurpilot.ghio.theme.ksu

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat

@Composable
fun MaterialKernelSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val amoledMode = appSettings.colorMode.isAmoled
    val dynamicColor = appSettings.keyColor == 0

    val colorScheme = rememberKernelSUColorScheme(
        seedColor = if (dynamicColor) Color.Unspecified else Color(appSettings.keyColor),
        isDark = darkTheme,
        isAmoled = amoledMode,
        paletteStyle = appSettings.paletteStyle,
        colorSpec = appSettings.colorSpec,
    )

    LaunchedEffect(darkTheme) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val animatedColorScheme = colorScheme.animateAsState()

    MaterialExpressiveTheme(
        colorScheme = animatedColorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = {
            content()
        }
    )
}
