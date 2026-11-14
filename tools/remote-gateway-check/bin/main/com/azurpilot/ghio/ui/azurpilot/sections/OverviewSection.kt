package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotResource
import com.azurpilot.ghio.proot.AzurPilotStatus
import com.azurpilot.ghio.proot.AzurPilotTask
import com.azurpilot.ghio.proot.AzurPilotTaskState
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApEmptyState
import com.azurpilot.ghio.ui.azurpilot.ApKeyValueRow
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.azurpilot.ApStatusPill
import com.azurpilot.ghio.ui.azurpilot.AzurPilotTaskPlan
import com.azurpilot.ghio.ui.azurpilot.formatResource
import com.azurpilot.ghio.ui.azurpilot.instanceStatusColor
import com.azurpilot.ghio.ui.azurpilot.instanceStatusText
import com.azurpilot.ghio.ui.azurpilot.isRecorded
import com.azurpilot.ghio.ui.azurpilot.resourceLabel
import com.azurpilot.ghio.ui.azurpilot.taskStateText
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.AppLabeledControlRow
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider as HorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppSwitch as Switch
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppFilterChip

/**
 * 总览：调度器状态、资源、实时画面、任务计划
 *
 * The Overview section: scheduler status, resources, the live preview, and the task plan.
 *
 * @param repository 网关仓库 / the gateway repository
 */
@Composable
fun OverviewSection(
    repository: AzurPilotRepository,
    onOpenConfig: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenStats: () -> Unit,
) {
    val overview by repository.overview.collectAsStateWithLifecycle()
    val selected by repository.selectedInstance.collectAsStateWithLifecycle()
    val remoteName by repository.remoteName.collectAsStateWithLifecycle()
    val instances by repository.instances.collectAsStateWithLifecycle()
    val schema by repository.schema.collectAsStateWithLifecycle()
    val data = overview

    ApSectionColumn {
        if (remoteName != null) AppCard {
            Text(stringResource(R.string.remote_pair_current, remoteName.orEmpty()))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                instances.forEach { instance ->
                    AppFilterChip(selected = instance.name == selected,
                        onClick = { repository.selectInstance(instance.name) },
                        label = { Text(instance.name) })
                }
            }
        }
        if (selected == null) {
            AppCard {
                ApEmptyState(
                    icon = Icons.Filled.Schedule,
                    title = stringResource(R.string.ap_overview_no_instance),
                    hint = stringResource(R.string.ap_instance_none),
                )
            }
        }

        AppCard(title = stringResource(R.string.ap_overview_scheduler)) {
            if (data == null) {
                ApEmptyState(Icons.Filled.Schedule, stringResource(R.string.ap_waiting_data))
            } else {
                val status = data.status
                ApStatusPill(
                    text = instanceStatusText(status),
                    container = instanceStatusColor(status).copy(alpha = 0.15f),
                    content = instanceStatusColor(status),
                )
                data.tasks.firstOrNull { it.state == AzurPilotTaskState.Running }?.let { task ->
                    Text(
                        text = task.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = taskStateText(task.state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                data.tasks.firstOrNull { it.state == AzurPilotTaskState.Pending }?.let { task ->
                    Text(
                        text = if (task.nextRun.startsWith("2020-01-01")) {
                            "${stringResource(R.string.ap_overview_tasks)} · ${task.name}"
                        } else {
                            "${stringResource(R.string.ap_overview_tasks)} · ${task.name} · ${task.nextRun}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            SchedulerButton(repository, running = data?.status == AzurPilotStatus.Running)
        }

        AppCard {
            com.azurpilot.ghio.ui.components.AppNavigationRow(
                label = stringResource(R.string.ap_section_config),
                description = stringResource(R.string.ap_title_task_config),
                onClick = onOpenConfig,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            com.azurpilot.ghio.ui.components.AppNavigationRow(
                label = stringResource(R.string.ap_section_logs),
                onClick = onOpenLogs,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            com.azurpilot.ghio.ui.components.AppNavigationRow(
                label = stringResource(R.string.ap_section_stats),
                onClick = onOpenStats,
            )
        }

        AppCard(title = stringResource(R.string.ap_overview_tasks)) {
            when {
                data == null -> ApEmptyState(Icons.Filled.Schedule, stringResource(R.string.ap_waiting_data))
                data.tasks.isEmpty() -> ApEmptyState(Icons.Filled.Schedule, stringResource(R.string.home_tasks_empty))
                else -> AzurPilotTaskPlan(data.tasks, schema)
            }
        }
    }
}

/**
 * 调度器启停
 *
 * 总览是「一眼看状态并动手」的那一页，启停按钮必须在它上面。状态是 error 时按钮回到「启动」 ——那正是重试的入口；进行中的那一次会禁用按钮，stop 在服务端最长要等 30s 才回话。
 *
 * The scheduler start/stop button.
 *
 * Overview is the "see the status and act" page, so the start/stop button must live here. When the
 * status is error the button flips back to "start" — that is exactly the retry entry; the in-flight
 * call disables the button, since stop can take up to 30 s on the server before it answers.
 *
 * @param repository 网关仓库 / the gateway repository
 * @param running 调度器当前是否在运行 / whether the scheduler is running
 */
@Composable
private fun SchedulerButton(repository: AzurPilotRepository, running: Boolean) {
    val busy by repository.schedulerBusy.collectAsStateWithLifecycle()
    val overview by repository.overview.collectAsStateWithLifecycle()
    val connected by repository.connected.collectAsStateWithLifecycle()
    Button(
        onClick = { repository.setSchedulerRunning(!running) },
        enabled = connected && overview != null && overview?.status != AzurPilotStatus.Updating && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(AppTokens.IconSize.md),
        )
        // 文案与图标一起交叉淡入：同一颗按钮的三种态，硬切会闪一下「换了个按钮」
        AnimatedContent(
            targetState =
                when {
                    busy -> R.string.ap_scheduler_processing
                    running -> R.string.ap_scheduler_stop
                    else -> R.string.ap_scheduler_start
                },
            transitionSpec = {
                fadeIn(androidx.compose.animation.core.spring()) togetherWith
                    fadeOut(androidx.compose.animation.core.spring())
            },
            label = "schedulerAction",
        ) { label ->
            Text(
                text = stringResource(label),
                modifier = Modifier.padding(start = AppTokens.Spacing.sm),
            )
        }
    }
}

/** 任务计数小字：运行中 / 待运行 / 等待 / A small task-count line: running / pending / waiting. */
@Composable
private fun TaskCounts(tasks: List<AzurPilotTask>) {
    Text(
        text =
            stringResource(
                R.string.ap_overview_counts,
                tasks.count { it.state == AzurPilotTaskState.Running },
                tasks.count { it.state == AzurPilotTaskState.Pending },
                tasks.count { it.state == AzurPilotTaskState.Waiting },
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
    )
}

/**
 * 资源格：两列铺开，零值显示占位符（服务端用 0 + 哨兵时间戳表达「从未同步」）
 *
 * The resource grid: two columns; zero values render as a placeholder (the server expresses "never
 * synced" as 0 plus a sentinel timestamp).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResourceGrid(resources: List<AzurPilotResource>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
        maxItemsInEachRow = 2,
    ) {
        resources.forEach { resource ->
            val recorded = isRecorded(resource.record)
            Surface(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(AppTokens.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs),
                ) {
                    Text(
                        text = resourceLabel(resource.name),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatResource(resource.value, recorded),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val limit = resource.limit
                    if (recorded && limit != null && limit > 0) {
                        Text(
                            text =
                                stringResource(
                                    R.string.ap_overview_resource_limit,
                                    formatResource(limit, true),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 实时画面：网关只在订阅期间推帧，这里不做抓拍
 *
 * The live preview: the gateway pushes frames only while subscribed; no snapshot capture here.
 *
 * @param repository 网关仓库 / the gateway repository
 * @param modifier 应用于卡片的修饰符 / the modifier applied to the card
 */
@Composable
private fun PreviewCard(repository: AzurPilotRepository, modifier: Modifier = Modifier) {
    val preview by repository.preview.collectAsStateWithLifecycle()
    AppCard(title = stringResource(R.string.ap_overview_preview), modifier = modifier) {
        // 逐帧硬换会闪；交叉淡入让「画面在动」这件事看起来是连续的
        Crossfade(
            targetState = preview?.image,
            animationSpec = androidx.compose.animation.core.spring(),
            label = "previewFrame",
        ) { image ->
            if (image == null) {
                ApEmptyState(
                    icon = Icons.Filled.Image,
                    title = stringResource(R.string.ap_preview_waiting),
                    hint = stringResource(R.string.ap_preview_waiting_hint),
                )
            } else {
                // 虚拟屏是横屏 16:9，按同一比例留位，换帧时不会跳高度
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = stringResource(R.string.ap_overview_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        preview?.capturedAt?.let {
            Text(
                text = stringResource(R.string.ap_preview_captured_at, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
