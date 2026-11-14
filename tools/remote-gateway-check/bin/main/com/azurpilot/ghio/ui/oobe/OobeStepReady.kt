package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.i18n.AppLocales
import com.azurpilot.ghio.privileged.PermissionManager
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.theme.ksu.ColorMode
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text
import org.koin.compose.koinInject

/**
 * 呈现 OOBE 的准备就绪与配置摘要
 *
 * 汇总展示用户配置的语言、外观色彩与提权模式，提示首启运行时解压注意事项。
 *
 * Presents OOBE Ready to go and configuration summary.
 *
 * Summarizes the configured language, theme colors, and privilege backend;
 * provides heads-up notice about first-launch runtime extraction.
 */
@Composable
fun OobeStepReady(
    modifier: Modifier = Modifier,
    manager: AppSettingsManager = koinInject(),
    permissionManager: PermissionManager = koinInject(),
) {
    val settings by manager.appearance.collectAsStateWithLifecycle()
    val accessState by permissionManager.state.collectAsStateWithLifecycle()

    val currentLang = oobeLanguageName(AppLocales.currentTag())

    val currentColorMode = ColorMode.fromValue(settings.colorMode.toIntOrNull() ?: 0)
    val themeText = when {
        currentColorMode.value == 1 || currentColorMode.value == 4 -> stringResource(R.string.oobe_pref_theme_light)
        currentColorMode.value == 2 || currentColorMode.value == 5 || currentColorMode.value == 6 -> stringResource(R.string.oobe_pref_theme_dark)
        else -> stringResource(R.string.oobe_pref_theme_system)
    }

    val currentBackend = accessState.configuredBackend
    val isGranted = accessState.isGranted(currentBackend)
    val backendName = if (currentBackend == RemoteBackend.SHIZUKU) "Shizuku" else "Root"
    val backendStatus = "$backendName (${stringResource(accessState.oobeStatusRes())})"

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.lg),
    ) {
        OobePageHeader(
            title = stringResource(if (isGranted) R.string.oobe_ready_title else R.string.oobe_ready_pending_title),
            subtitle = stringResource(if (isGranted) R.string.oobe_ready_subtitle else R.string.oobe_ready_pending_subtitle),
            icon = Icons.Outlined.RocketLaunch,
        )

        OobeCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppTokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.oobe_ready_summary_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                SummaryRow(stringResource(R.string.oobe_ready_summary_lang, currentLang))
                SummaryRow(stringResource(R.string.oobe_ready_summary_theme, themeText))
                SummaryRow(stringResource(R.string.oobe_ready_summary_style,
                    if (settings.uiMode == "miuix") "Miuix" else "Material 3 Expressive"))
                SummaryRow(stringResource(R.string.oobe_ready_summary_priv, backendStatus), isGranted)
            }
        }

        OobeCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppTokens.Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AppTokens.IconSize.md),
                )
                Text(
                    text = stringResource(R.string.oobe_ready_launch_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 摘要行 / Summary item row. */
@Composable
private fun SummaryRow(text: String, complete: Boolean = true) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (complete) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(AppTokens.IconSize.sm),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
