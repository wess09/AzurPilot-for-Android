package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppCheckbox as Checkbox
import com.azurpilot.ghio.ui.ksu.AppDialog as AlertDialog
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 社区规范警告弹窗：勾选「我已阅读并理解」后即可点击「继续」关闭。
 *
 * 弹窗禁止通过返回键或点击空白外部关闭；确认后由调用方落盘持久化，后续不再弹出。
 *
 * Community guidelines warning dialog: requires checking
 * "I have read and understood" before the "Continue" button becomes enabled.
 *
 * Dismissal via back press or outside click is disabled; once confirmed, the caller persists the
 * acknowledgement state so the dialog never pops up again.
 *
 * @param onAcknowledge 用户勾选确认后点击继续的回调 / invoked when the user checks the
 *   box, and taps continue
 */
@Composable
fun CommunityWarningDialog(onAcknowledge: () -> Unit) {
    var isConfirmed by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            // 强制阅读，禁止点击外部或返回键关闭
        },
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.community_warning_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
        },
        text = {
            Column(
                // 大字体下为标题和继续按钮留出空间，正文及确认框仍可滚动。
                modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .4f).dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.community_warning_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .toggleable(
                                value = isConfirmed,
                                role = Role.Checkbox,
                                onValueChange = { isConfirmed = it },
                            )
                            .padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = isConfirmed,
                        onCheckedChange = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.community_warning_confirm_read),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAcknowledge,
                enabled = isConfirmed,
            ) {
                Text(
                    text = stringResource(R.string.community_warning_continue),
                )
            }
        },
    )
}
