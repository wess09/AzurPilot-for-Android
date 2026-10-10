package com.azurpilot.ghio.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.domain.RunMode
import com.azurpilot.ghio.overlay.screensaver.ScreenSaverOverlayManager
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.AppTokens
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 提供主界面的手动遮罩入口；缺少悬浮窗权限时打开系统授权页。
 *
 * Provides manual screensaver entry on the main screen and opens system settings when the overlay
 * permission is missing. Composition and window operations run on the main thread.
 */
@Composable
fun ScreenSaverButton(
    modifier: Modifier = Modifier,
    settings: AppSettingsManager = koinInject(),
    manager: ScreenSaverOverlayManager = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode by settings.runMode.collectAsStateWithLifecycle()
    if (mode != RunMode.BACKGROUND) return

    FilledTonalButton(
        onClick = {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            } else {
                scope.launch {
                    if (!manager.show()) {
                        Toast.makeText(context, R.string.screensaver_show_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Outlined.NightsStay,
            contentDescription = null,
            modifier = Modifier.size(AppTokens.IconSize.md),
        )
        Text(
            text = stringResource(R.string.screensaver_title),
            modifier = Modifier.padding(start = AppTokens.Spacing.sm),
        )
    }
}

/**
 * 渲染显示设置中的自动遮罩开关；默认关闭，保存后从下一次任务启动生效。
 *
 * Renders the automatic screensaver switch in display settings; it defaults to off and applies
 * from the next run after saving. A missing overlay permission opens system settings first.
 */
@Composable
fun ScreenSaverSettingsCard(settings: AppSettingsManager = koinInject()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by settings.screenSaverEnabled.collectAsStateWithLifecycle()
    val mode by settings.runMode.collectAsStateWithLifecycle()
    if (mode != RunMode.BACKGROUND) return

    AppCard(title = stringResource(R.string.screensaver_title)) {
        AppLabeledControlRow(label = stringResource(R.string.screensaver_auto)) {
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked && !Settings.canDrawOverlays(context)) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    } else {
                        scope.launch { settings.setScreenSaverEnabled(checked) }
                    }
                },
            )
        }
        Text(
            text = stringResource(R.string.screensaver_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
