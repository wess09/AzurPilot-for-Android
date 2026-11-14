package com.azurpilot.ghio.ui.ksu

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** 业务弹窗使用 KSU 的主题窗口。 / Business dialogs use KSU's themed windows. */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    if (
        com.azurpilot.ghio.theme.ksu.LocalUiMode.current ==
            com.azurpilot.ghio.theme.ksu.UiMode.Miuix
    ) {
        top.yukonga.miuix.kmp.window.WindowDialog(
            show = true,
            modifier = modifier,
            onDismissRequest = { if (properties.dismissOnClickOutside) onDismissRequest() },
        ) {
            if (!properties.dismissOnBackPress) {
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
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                title?.let {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppTextStyle provides
                            top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.headline1
                    ) {
                        it()
                    }
                }
                text?.invoke()
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
        return
    }
    com.azurpilot.ghio.ui.ksu.material.ExpressiveDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}
