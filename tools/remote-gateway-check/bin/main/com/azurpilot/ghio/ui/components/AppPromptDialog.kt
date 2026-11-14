package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.azurpilot.ghio.ui.ksu.AppDialog as AlertDialog
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton

/**
 * 渲染三按钮提示弹窗：确认 / 中间出口 / 关闭
 *
 * [neutralText] 用于「换条路走」这类出口（如 Shizuku 引导里的切 Root）， M3 的 AlertDialog 只有 confirm 与 dismiss 两槽，中间出口挤在
 * dismiss 槽里
 *
 * [destructive] 给「删掉就没了」这类确认用：M3 里破坏性动作靠 error 角色标出来， 不上色就和普通「确定」长得一模一样，误点的代价却不是一个量级
 *
 * Renders a three-button prompt dialog: confirm / neutral exit / dismiss.
 *
 * [neutralText] hosts an alternate way out (such as switching to Root in the Shizuku guide); M3's
 * AlertDialog only offers confirm and dismiss slots, so the middle exit shares the dismiss slot.
 * [destructive] marks confirmations whose outcome cannot be undone: M3 signals destructive actions
 * through the error role, and without the tint they look exactly like a plain "OK" while the cost
 * of a mis-tap is in a different league.
 *
 * @param dismissOnOutsideClick false 时返回键与点外部都关不掉弹窗，强制走按钮 / when false, neither back nor an outside
 *   tap dismisses; the user must pick a button
 */
@Composable
fun AppPromptDialog(
    title: String,
    message: String,
    icon: ImageVector,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    dismissText: String? = null,
    neutralText: String? = null,
    onNeutralClick: () -> Unit = {},
    dismissOnOutsideClick: Boolean = false,
    destructive: Boolean = false,
) {
    if (
        com.azurpilot.ghio.theme.ksu.LocalUiMode.current ==
            com.azurpilot.ghio.theme.ksu.UiMode.Miuix
    ) {
        top.yukonga.miuix.kmp.window.WindowDialog(
            show = true,
            title = title,
            onDismissRequest = { if (dismissOnOutsideClick) onDismissRequest() },
        ) {
            if (!dismissOnOutsideClick) {
                val state =
                    androidx.navigationevent.compose.rememberNavigationEventState(
                        androidx.navigationevent.NavigationEventInfo.None
                    )
                androidx.navigationevent.compose.NavigationBackHandler(
                    state,
                    isBackEnabled = true,
                    onBackCompleted = {},
                )
            }
            top.yukonga.miuix.kmp.basic.Text(message)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                dismissText?.let { text ->
                    top.yukonga.miuix.kmp.basic.TextButton(
                        text,
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                    )
                }
                neutralText?.let { text ->
                    top.yukonga.miuix.kmp.basic.TextButton(
                        text,
                        onClick = onNeutralClick,
                        modifier = Modifier.weight(1f),
                    )
                }
                top.yukonga.miuix.kmp.basic.TextButton(
                    confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = { if (dismissOnOutsideClick) onDismissRequest() },
        properties =
            DialogProperties(
                dismissOnBackPress = dismissOnOutsideClick,
                dismissOnClickOutside = dismissOnOutsideClick,
            ),
        icon = { Icon(imageVector = icon, contentDescription = null) },
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmText,
                    color =
                        if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        },
                )
            }
        },
        dismissButton = {
            if (neutralText != null || dismissText != null) {
                Row {
                    neutralText?.let {
                        TextButton(onClick = onNeutralClick) { Text(it) }
                    }
                    dismissText?.let {
                        TextButton(onClick = onDismissRequest) { Text(it) }
                    }
                }
            }
        },
    )
}
