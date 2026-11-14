package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.AppCheckbox as Checkbox
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 显示风险提示和三份离线协议；用户勾选后即可继续，跨返回与重建保留状态。
 *
 * Presents risks and three offline legal documents with an acknowledgement checkbox,
 * retaining state across navigation and recreation.
 */
@Composable
internal fun OobeStepAgreement(
    alreadyAcknowledged: Boolean,
    onAcknowledgeStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmed by rememberSaveable { mutableStateOf(alreadyAcknowledged) }
    var document by rememberSaveable { mutableStateOf<OobeLegalDocument?>(null) }
    val miuix = LocalUiMode.current == UiMode.Miuix
    LaunchedEffect(confirmed, alreadyAcknowledged) {
        onAcknowledgeStateChanged(alreadyAcknowledged || confirmed)
    }
    document?.let { OobeLegalReader(it) { document = null } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (miuix) 16.dp else 20.dp)) {
        OobePageHeader(stringResource(R.string.oobe_welcome_guidelines_title), null, Icons.Outlined.VerifiedUser, OobeGlyph.AGREEMENT)
        Text(stringResource(R.string.oobe_welcome_guidelines_desc),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.oobe_legal_risk),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface)
        val documents: @Composable () -> Unit = {
            OobeLegalDocument.entries.forEachIndexed { index, entry ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { document = entry }
                    .padding(horizontal = if (miuix) 20.dp else 4.dp,
                        vertical = if (miuix) 16.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(entry.title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        color = if (miuix) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (miuix && index < OobeLegalDocument.entries.lastIndex) {
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), thickness = .5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
        if (miuix) OobeCard { Column { documents() } }
        else Column(Modifier.fillMaxWidth()) { documents() }
        if (alreadyAcknowledged) {
            Text(stringResource(R.string.oobe_welcome_guidelines_acknowledged),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = confirmed,
                        role = Role.Checkbox, onValueChange = { confirmed = it })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(confirmed, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.oobe_welcome_guidelines_accept),
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
