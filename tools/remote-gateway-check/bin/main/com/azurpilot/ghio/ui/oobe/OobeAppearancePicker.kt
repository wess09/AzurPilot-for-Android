package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 用两张界面预览直接展示风格差异，整张预览支持单选语义。
 * 预览绘制本应用的容器形状，不代替原始 OS3 图标资源。
 *
 * Presents two interface previews as accessible radio choices.
 * Previews draw this app's container shapes and do not replace original OS3 glyph resources.
 */
@Composable
internal fun OobeStylePicker(selected: String, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((value, label) in listOf("material" to "Material 3 Expressive", "miuix" to "Miuix")) {
            val chosen = selected == value
            Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                .border(if (chosen) 2.dp else 1.dp,
                    if (chosen) colors.primary else colors.outlineVariant, RoundedCornerShape(24.dp))
                .selectable(chosen, role = Role.RadioButton, onClick = { onSelect(value) })
                .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(112.dp).clearAndSetSemantics { }) {
                    val miuix = value == "miuix"
                    val background = if (miuix) Color(0xFFF5F5F7) else Color(0xFFF0F2FD)
                    drawRoundRect(background, cornerRadius = CornerRadius(16.dp.toPx()))
                    if (miuix) drawRoundRect(Brush.linearGradient(listOf(Color(0xFFE7E0FF), Color(0xFFFADCEA),
                        Color(0xFFDCE9FF))), size = Size(size.width, size.height * .42f), cornerRadius = CornerRadius(16.dp.toPx()))
                    val inset = 14.dp.toPx()
                    drawRoundRect(if (miuix) Color(0xFF333646) else Color(0xFF4C60A0),
                        topLeft = Offset(inset, 24.dp.toPx()), size = Size(size.width * .42f, 7.dp.toPx()),
                        cornerRadius = CornerRadius(4.dp.toPx()))
                    for (row in 0..2) {
                        drawRoundRect(Color.White, topLeft = Offset(inset, (56 + row * 20).dp.toPx()),
                            size = Size(size.width - inset * 2, 14.dp.toPx()),
                            cornerRadius = CornerRadius(if (miuix) 5.dp.toPx() else 9.dp.toPx()))
                    }
                    drawRoundRect(if (miuix) Color(0xFF3482FF) else Color(0xFF667DC5),
                        topLeft = Offset(size.width - inset - 32.dp.toPx(), 96.dp.toPx()),
                        size = Size(32.dp.toPx(), 10.dp.toPx()), cornerRadius = CornerRadius(8.dp.toPx()))
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = if (chosen) colors.primary else colors.onSurface)
                    if (chosen) Icon(painterResource(R.drawable.oobe_os3_selected), null, Modifier.size(20.dp),
                        tint = if (selected == "miuix") Color.Unspecified else colors.primary)
                }
            }
        }
    }
}

/** 用一排选项呈现主题模式，避免将三项主题隐藏在滚动列表中。 / Presents theme modes in one visible row. */
@Composable
internal fun OobeThemePicker(selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.oobe_pref_theme_title), style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, resource) in listOf(0 to R.string.oobe_pref_theme_system, 1 to R.string.oobe_pref_theme_light,
                2 to R.string.oobe_pref_theme_dark)) {
                val chosen = value == selected
                Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .border(if (chosen) 2.dp else 1.dp, if (chosen) colors.primary else colors.outlineVariant,
                        RoundedCornerShape(16.dp))
                    .selectable(chosen, role = Role.RadioButton, onClick = { onSelect(value) })
                    .heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(resource), style = MaterialTheme.typography.bodyMedium,
                        color = if (chosen) colors.primary else colors.onSurface, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
