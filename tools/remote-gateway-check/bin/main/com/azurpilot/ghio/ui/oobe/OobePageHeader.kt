package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 使用激活向导的标题层级：小米居中 32sp，Pixel 左对齐大标题。
 *
 * Uses activation hierarchy: centered 32sp Xiaomi titles and leading Pixel headlines.
 */
@Composable
internal fun OobePageHeader(title: String, subtitle: String?, icon: ImageVector,
    glyph: OobeGlyph? = null, compact: Boolean = false) {
    val miuix = LocalUiMode.current == UiMode.Miuix
    Column(Modifier.fillMaxWidth().padding(horizontal = if (miuix) 20.dp else 0.dp)
        .padding(top = if (miuix) 10.dp else 12.dp,
            bottom = if (miuix || compact) 12.dp else 28.dp),
        horizontalAlignment = if (miuix) Alignment.CenterHorizontally else Alignment.Start) {
        if (miuix && glyph != null) {
            OobeActivationGlyph(glyph)
            Spacer(Modifier.height(8.dp))
        } else if (!miuix) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
        }
        Text(title, Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp,
                lineHeight = 40.sp, fontWeight = FontWeight.Normal),
            textAlign = if (miuix) TextAlign.Center else TextAlign.Start,
            color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Spacer(Modifier.height(12.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = if (miuix) 14.sp else 16.sp, lineHeight = if (miuix) 21.sp else 24.sp),
                textAlign = if (miuix) TextAlign.Center else TextAlign.Start,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
