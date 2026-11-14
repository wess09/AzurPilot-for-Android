package com.azurpilot.ghio.ui.oobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 呈现 OOBE 的核心工作流与新手教程
 *
 * 四组卡片说明独立屏幕、任务启停、悬浮控制和息屏退出；图标资源保持原样。
 *
 * Presents OOBE Core workflow and beginner tutorial.
 *
 * Explains separate screens, task control, floating controls and screensaver exit through
 * four cards, preserving the existing icon resources.
 */
@Composable
fun OobeStepTutorial(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(
            if (LocalUiMode.current == UiMode.Miuix) 16.dp else 12.dp),
    ) {
        OobePageHeader(
            title = stringResource(R.string.oobe_tut_title),
            subtitle = stringResource(R.string.oobe_tut_subtitle),
            icon = Icons.Outlined.Explore,
            glyph = OobeGlyph.TOUR,
            compact = true,
        )

        TutorialCard(
            icon = Icons.Outlined.ScreenshotMonitor,
            title = stringResource(R.string.oobe_tut_vd_title),
            desc = stringResource(R.string.oobe_tut_vd_desc),
        )

        TutorialCard(
            icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
            title = stringResource(R.string.oobe_tut_task_title),
            desc = stringResource(R.string.oobe_tut_task_desc),
        )

        TutorialCard(
            icon = Icons.Outlined.Layers,
            title = stringResource(R.string.oobe_tut_overlay_title),
            desc = stringResource(R.string.oobe_tut_overlay_desc),
        )

        TutorialCard(
            icon = Icons.Outlined.Bedtime,
            title = stringResource(R.string.oobe_tut_saver_title),
            desc = stringResource(R.string.oobe_tut_saver_desc),
        )
    }
}

/** 单张教程特性卡片 / Single tutorial feature card. */
@Composable
private fun TutorialCard(
    icon: ImageVector,
    title: String,
    desc: String,
    modifier: Modifier = Modifier,
) {
    OobeCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
