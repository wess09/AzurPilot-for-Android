// KernelSU Manager UI (GPL-3.0), commit 6e3860443f8fea7c5fc226277e53888033796488.
// AzurPilot adaptations: package/import names, app identity and business callbacks.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.azurpilot.ghio.ui.ksu.material

import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun ExpressiveTabRow(
    selectedTabIndex: Int,
    tabs: List<String>,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val safeIndex = selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    PrimaryTabRow(
        selectedTabIndex = safeIndex,
        modifier = modifier,
        containerColor = containerColor,
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = safeIndex == index
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onTabClick(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .clip(CircleShape)
                        .indication(interactionSource, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = if (selected) LocalContentColor.current
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}
