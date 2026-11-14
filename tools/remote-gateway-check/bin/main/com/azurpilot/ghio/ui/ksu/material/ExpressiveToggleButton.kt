// KernelSU Manager UI (GPL-3.0), commit 6e3860443f8fea7c5fc226277e53888033796488.
// AzurPilot adaptations: package/import names, app identity and business callbacks.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.azurpilot.ghio.ui.ksu.material

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun ExpressiveToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ToggleButtonShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ToggleButtonColors = expressiveToggleButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        shapes = shapes,
        content = content,
    )
}

@Composable
fun expressiveToggleButtonColors(
    checkedContainerColor: Color = MaterialTheme.colorScheme.primary,
    checkedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
): ToggleButtonColors = ToggleButtonDefaults.colors(
    checkedContainerColor = checkedContainerColor,
    checkedContentColor = checkedContentColor,
    containerColor = containerColor,
    contentColor = contentColor,
)
