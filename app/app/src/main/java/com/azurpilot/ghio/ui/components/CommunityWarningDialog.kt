package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.azurpilot.ghio.R
import kotlinx.coroutines.delay

/**
 * 社区规范警告弹窗：强制展示 10 秒倒计时，并要求勾选「我已阅读并理解」后方可点击「继续」关闭
 *
 * 弹窗禁止通过返回键或点击空白外部关闭；确认后由调用方落盘持久化，后续不再弹出。
 *
 * Community guidelines warning dialog: enforces a 10-second countdown display and requires
 * checking "I have read and understood" before the "Continue" button becomes enabled.
 *
 * Dismissal via back press or outside click is disabled; once confirmed, the caller persists
 * the acknowledgement state so the dialog never pops up again.
 *
 * @param onAcknowledge 用户完成倒计时并勾选确认后点击继续的回调 /
 *   invoked when the user completes countdown, checks the box, and taps continue
 */
@Composable
fun CommunityWarningDialog(
    onAcknowledge: () -> Unit,
) {
    // 强制倒计时 10 秒；rememberSaveable 保证旋转屏幕或配置变更时不重置计时
    var remainingSeconds by rememberSaveable { mutableIntStateOf(10) }
    var isConfirmed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds -= 1
        }
    }

    val canContinue = remainingSeconds <= 0 && isConfirmed

    AlertDialog(
        onDismissRequest = {
            // 强制阅读，禁止点击外部或返回键关闭
        },
        properties = DialogProperties(
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
                // 正文槽的高度受弹窗限制，必须能滚动到末尾的确认框。
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.community_warning_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .clickable { isConfirmed = !isConfirmed }
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = isConfirmed,
                        onCheckedChange = { isConfirmed = it },
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
                enabled = canContinue,
            ) {
                Text(
                    text = if (remainingSeconds > 0) {
                        stringResource(R.string.community_warning_continue_countdown, remainingSeconds)
                    } else {
                        stringResource(R.string.community_warning_continue)
                    },
                )
            }
        },
    )
}
