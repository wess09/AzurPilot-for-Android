package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.ksu.ColorMode
import com.azurpilot.ghio.ui.ksu.AppSwitch as Switch
import com.azurpilot.ghio.ui.ksu.AppText as Text
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 以界面预览、可见主题选项和动态配色开关完成外观选择，修改立即生效。
 * 两套主题各自保留动态配色偏好。
 *
 * Applies appearance choices immediately through interface previews, visible theme choices,
 * and a dynamic-color switch. Each framework retains its own dynamic-color preference.
 */
@Composable
fun OobeStepPreferences(modifier: Modifier = Modifier, manager: AppSettingsManager = koinInject()) {
    val settings by manager.appearance.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mode = ColorMode.fromValue(settings.colorMode.toIntOrNull() ?: 0)
    val miuix = settings.uiMode == "miuix"
    val monet = if (miuix) settings.miuixMonet.toBoolean() else mode.isMonet
    val base = when (mode.value) { 1, 4 -> 1; 2, 5, 6 -> 2; else -> 0 }
    val updateMonet: (Boolean) -> Unit = { enabled -> scope.launch {
        manager.setAppearance {
            if (miuix) it.copy(miuixMonet = enabled.toString())
            else it.copy(colorMode = (if (enabled) mode.toMonetMode() else mode.toNonMonetMode()).toString())
        }
    } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OobePageHeader(stringResource(R.string.oobe_pref_title), stringResource(R.string.oobe_pref_subtitle),
            Icons.Outlined.ColorLens, OobeGlyph.APPEARANCE, compact = true)
        OobeStylePicker(settings.uiMode) { chosen -> scope.launch {
            manager.setAppearance { it.copy(uiMode = chosen) }
        } }
        OobeThemePicker(base) { chosen -> scope.launch {
            manager.setAppearance { it.copy(colorMode = (if (mode.isMonet) chosen + 3 else chosen).toString()) }
        } }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(monet, role = Role.Switch, onValueChange = updateMonet),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.oobe_pref_monet_title), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.oobe_pref_monet_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(monet, onCheckedChange = null)
        }
    }
}
