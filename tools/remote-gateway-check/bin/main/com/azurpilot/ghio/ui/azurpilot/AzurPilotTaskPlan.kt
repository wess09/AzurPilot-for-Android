package com.azurpilot.ghio.ui.azurpilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotSchema
import com.azurpilot.ghio.proot.AzurPilotTask
import com.azurpilot.ghio.proot.AzurPilotTaskState
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppIcon
import com.azurpilot.ghio.ui.ksu.AppText

/**
 * 共享 WS 任务计划，保留服务端排序；任务名突出，状态和时间使用次级字阶。
 *
 * Shares the WebSocket task plan in server order, emphasizing names over state and time.
 */
@Composable
fun AzurPilotTaskPlan(tasks: List<AzurPilotTask>, schema: AzurPilotSchema?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        tasks.forEachIndexed { index, task ->
            if (index > 0) AppHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = AppTokens.Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
            ) {
                AppIcon(
                    imageVector = if (task.state == AzurPilotTaskState.Running) {
                        Icons.Filled.PlayArrow
                    } else Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = taskStateColor(task.state),
                    modifier = Modifier.size(AppTokens.IconSize.md),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs),
                ) {
                    AppText(
                        text = schema?.taskTitle(task.name) ?: task.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 初始配置用 2020 年时间作哨兵，不能把它显示成真正的执行日期。
                    val nextRun = task.nextRun.takeIf { it.isNotBlank() && !it.startsWith("2020-01-01") }
                    AppText(
                        text = if (task.state == AzurPilotTaskState.Running || nextRun == null) {
                            taskStateText(task.state)
                        } else stringResource(
                            R.string.home_task_schedule,
                            taskStateText(task.state),
                            nextRun.replace('T', ' '),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
