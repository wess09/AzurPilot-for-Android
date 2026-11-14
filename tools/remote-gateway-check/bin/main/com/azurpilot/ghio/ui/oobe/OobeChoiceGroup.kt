package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import com.azurpilot.ghio.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 展示完整选项；小米使用选中勾，Material 使用原生单选按钮。
 * 整行可点击并提供单选语义，避免激活页隐藏关键选择。
 *
 * Shows all choices with Xiaomi checkmarks or native Material radio buttons.
 * Entire rows expose radio semantics so activation choices remain discoverable.
 */
@Composable
internal fun <T> OobeChoiceGroup(title: String?, options: List<Pair<T, String>>, selected: T,
    onSelect: (T) -> Unit, enabled: Boolean = true) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) Text(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() }, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((value, label) in options) {
            val chosen = value == selected
            Row(Modifier.fillMaxWidth().background(
                if (!miuix && chosen) MaterialTheme.colorScheme.secondaryContainer
                else if (miuix) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(if (miuix) 16.dp else 24.dp))
                .selectable(chosen, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(value) })
                .heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
                    color = if (chosen && miuix) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface)
                if (miuix) Box(Modifier.size(24.dp)) {
                    if (chosen) Icon(painterResource(R.drawable.oobe_os3_selected), null,
                        Modifier.size(20.dp), tint = Color.Unspecified)
                } else RadioButton(chosen, onClick = null, enabled = enabled)
            }
        }
    }
}
