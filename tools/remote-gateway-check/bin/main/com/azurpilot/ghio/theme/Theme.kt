@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.azurpilot.ghio.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 补齐 MD3 的 colorScheme 没有的业务语义色角色
 *
 * MD3 的 colorScheme 只定义 primary / secondary / tertiary / error 四族，没有 success、warning 这类业务语义角色。按 MD3
 * 的自定义颜色角色（custom color role）约定补上，随明暗切换。
 *
 * 与内置角色一样成对给出：色是「容器」，[onSuccess] 是压在它上面的前景—— 少了这一半，深色档下的浅绿底就只能配上纯白前景，对比度掉到读不出。
 *
 * Adds the business-semantic color roles the MD3 colorScheme lacks.
 *
 * The MD3 colorScheme defines only the primary/secondary/tertiary/error families, with no
 * business-semantic roles such as success or warning. These follow the MD3 custom color role
 * convention and switch with dark/light.
 *
 * Like the built-in roles they come in pairs: the color is the "container" and [onSuccess] is the
 * foreground pressed onto it — without that half, the light green container in dark mode would be
 * stuck with pure-white foreground and the contrast would sink below readable.
 */
data class AppPalette(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
)

private val LightPalette =
    AppPalette(
        success = Color(0xFF146C2E),
        onSuccess = Color(0xFFFFFFFF),
        warning = Color(0xFF8F4C00),
    )

private val DarkPalette =
    AppPalette(
        success = Color(0xFF7FDA95),
        onSuccess = Color(0xFF00391A),
        warning = Color(0xFFFFB86B),
    )

/**
 * 携带 [AppPalette] 的 CompositionLocal；未显式 provide 时默认浅色档。
 *
 * The CompositionLocal carrying [AppPalette]; defaults to the light palette when not explicitly
 * provided.
 */
val LocalAppPalette = staticCompositionLocalOf { LightPalette }

/**
 * 主题扩展读取入口；Screen 不直接碰 CompositionLocal。
 *
 * The read entry for the theme extensions; Screens never touch the CompositionLocal directly.
 */
object AzurPilotTheme {
    val palette: AppPalette
        @Composable @ReadOnlyComposable get() = LocalAppPalette.current
}

/**
 * 提供全 App 的 Material 3 Expressive 主题，组件统一使用原生运动方案。
 *
 * 外观设置支持 Material 与 Miuix 两套主题；旧明暗偏好只作为迁移回落。
 *
 * Provides the app-wide Material 3 Expressive theme with native component motion. Appearance
 * settings and components support Material and Miuix themes; legacy dark mode is a migration fallback.
 */
@Composable
fun AzurPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    legacyColorMode: Int? = null,
    content: @Composable () -> Unit,
) {
    val manager: com.azurpilot.ghio.settings.AppSettingsManager = org.koin.compose.koinInject()
    val appearance by manager.appearance.collectAsStateWithLifecycle()
    val uiMode =
        if (appearance.uiMode == "miuix") com.azurpilot.ghio.theme.ksu.UiMode.Miuix
        else com.azurpilot.ghio.theme.ksu.UiMode.Material
    val legacyMode = legacyColorMode ?: if (darkTheme) 2 else 1
    val storedMode = appearance.colorMode.toIntOrNull() ?: legacyMode
    val requestedMode = com.azurpilot.ghio.theme.ksu.ColorMode.fromValue(storedMode)
    val colorMode =
        if (uiMode == com.azurpilot.ghio.theme.ksu.UiMode.Miuix) {
            com.azurpilot.ghio.theme.ksu.ColorMode.fromValue(
                if (appearance.miuixMonet.toBoolean()) requestedMode.toMonetMode()
                else requestedMode.toNonMonetMode()
            )
        } else requestedMode
    val settings =
        com.azurpilot.ghio.theme.ksu.AppSettings(
            colorMode,
            appearance.keyColor.toIntOrNull() ?: 0,
            runCatching { com.materialkolor.PaletteStyle.valueOf(appearance.colorStyle) }
                .getOrDefault(com.materialkolor.PaletteStyle.TonalSpot),
            runCatching {
                    com.materialkolor.dynamiccolor.ColorSpec.SpecVersion.valueOf(
                        appearance.colorSpec
                    )
                }
                .getOrDefault(com.materialkolor.dynamiccolor.ColorSpec.SpecVersion.SPEC_2025),
        )
    val systemDensity = androidx.compose.ui.platform.LocalDensity.current
    val pageScale = (appearance.pageScale.toFloatOrNull() ?: 1f).coerceIn(0.8f, 1.1f)
    val density =
        androidx.compose.ui.unit.Density(systemDensity.density * pageScale, systemDensity.fontScale)
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides density,
        com.azurpilot.ghio.theme.ksu.LocalEnableBlur provides appearance.enableBlur.toBoolean(),
        com.azurpilot.ghio.theme.ksu.LocalEnableFloatingBottomBar provides
            appearance.enableFloatingBottomBar.toBoolean(),
        com.azurpilot.ghio.theme.ksu.LocalEnableFloatingBottomBarBlur provides
            appearance.enableFloatingBottomBarBlur.toBoolean(),
        LocalAppPalette provides if (darkTheme) DarkPalette else LightPalette,
        com.azurpilot.ghio.theme.ksu.LocalUiMode provides uiMode,
        com.azurpilot.ghio.theme.ksu.LocalColorMode provides colorMode.value,
    ) {
        // 业务图表仍读取 Material 色角色；Miuix 控件使用其原生主题。
        // 主题提供者保持同一组合位置，切换风格不会销毁业务状态。
        com.azurpilot.ghio.theme.ksu.MiuixKernelSUTheme(settings) {
            com.azurpilot.ghio.theme.ksu.MaterialKernelSUTheme(settings) {
                val colors = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
                androidx.compose.material3.MaterialExpressiveTheme(
                    colorScheme =
                        if (uiMode == com.azurpilot.ghio.theme.ksu.UiMode.Material)
                            androidx.compose.material3.MaterialTheme.colorScheme
                        else
                            androidx.compose.material3.MaterialTheme.colorScheme.copy(
                                primary = colors.primary,
                                onPrimary = colors.onPrimary,
                                primaryContainer = colors.primaryContainer,
                                onPrimaryContainer = colors.onPrimaryContainer,
                                secondary = colors.secondary,
                                onSecondary = colors.onSecondary,
                                secondaryContainer = colors.secondaryContainer,
                                onSecondaryContainer = colors.onSecondaryContainer,
                                tertiary = colors.primary,
                                onTertiary = colors.onPrimary,
                                tertiaryContainer = colors.tertiaryContainer,
                                onTertiaryContainer = colors.onTertiaryContainer,
                                background = colors.surface,
                                onBackground = colors.onBackground,
                                surface = colors.surface,
                                onSurface = colors.onSurface,
                                surfaceVariant = colors.surfaceVariant,
                                onSurfaceVariant = colors.onSurfaceVariantSummary,
                                surfaceContainerLowest = colors.surface,
                                surfaceContainerLow = colors.surfaceContainer,
                                surfaceContainer = colors.surfaceContainer,
                                surfaceDim = colors.surfaceContainerHigh,
                                surfaceTint = colors.primary,
                                inverseSurface = colors.onSurface,
                                inverseOnSurface = colors.surface,
                                inversePrimary = colors.primary,
                                scrim = colors.windowDimming,
                                surfaceContainerHigh = colors.surfaceContainerHigh,
                                surfaceContainerHighest = colors.surfaceContainerHighest,
                                surfaceBright = colors.background,
                                error = colors.error,
                                onError = colors.onError,
                                errorContainer = colors.errorContainer,
                                onErrorContainer = colors.onErrorContainer,
                                outline = colors.outline,
                                outlineVariant = colors.dividerLine,
                            ),
                    motionScheme = androidx.compose.material3.MotionScheme.expressive(),
                    typography =
                        if (uiMode == com.azurpilot.ghio.theme.ksu.UiMode.Material)
                            com.azurpilot.ghio.theme.ksu.Typography
                        else
                            top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.let { styles ->
                                androidx.compose.material3.Typography(
                                    displayLarge = styles.title1,
                                    displayMedium = styles.title2,
                                    displaySmall = styles.title3,
                                    headlineLarge = styles.title3,
                                    headlineMedium = styles.title4,
                                    headlineSmall = styles.headline1,
                                    titleLarge = styles.headline1,
                                    titleMedium = styles.headline2,
                                    titleSmall = styles.subtitle,
                                    bodyLarge = styles.main,
                                    bodyMedium = styles.body1,
                                    bodySmall = styles.body2,
                                    labelLarge = styles.button,
                                    labelMedium = styles.footnote1,
                                    labelSmall = styles.footnote2,
                                )
                            },
                    content = content,
                )
            }
        }
    }
}
