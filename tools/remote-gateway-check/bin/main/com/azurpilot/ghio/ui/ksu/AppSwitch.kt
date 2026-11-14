package com.azurpilot.ghio.ui.ksu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode

/** 使用 KSU 的原生主题开关。 / Uses KSU's native themed switch. */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Switch(checked, onCheckedChange, modifier, enabled = enabled)
    else
        com.azurpilot.ghio.ui.ksu.material.ExpressiveSwitch(
            checked,
            onCheckedChange,
            modifier,
            enabled = enabled,
        )
}
