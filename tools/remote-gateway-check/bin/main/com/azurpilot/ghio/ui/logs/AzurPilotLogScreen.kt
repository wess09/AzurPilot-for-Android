package com.azurpilot.ghio.ui.logs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.log.AzurPilotDailyLogInfo
import com.azurpilot.ghio.log.AzurPilotErrorDirInfo
import com.azurpilot.ghio.log.AzurPilotLogViewModel
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider as HorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppText as Text
import org.koin.androidx.compose.koinViewModel

/**
 * 渲染 AzurPilot 日志列表（二级页面）：直读内部存储的 `rootfs/opt/azurpilot/log`，不走 wrapper HTTP
 *
 * 两个分区：「错误记录」是 AzurPilot 出错时落的时间戳现场（log.txt + 截图）， 「按天日志」是整天 append 的 txt
 *
 * Renders the AzurPilot log list (second-level page): reads the on-device
 * `rootfs/opt/azurpilot/log` directly, no wrapper HTTP in between.
 *
 * Two sections: "error records" are the timestamped scenes dropped when AzurPilot fails (log.txt +
 * screenshots); "daily logs" are txt files appended across the day.
 *
 * @param onOpenDaily 点开按天日志时回传文件名 / invoked with the file name when a daily log is tapped
 * @param onOpenError 点开错误现场时回传目录名 / invoked with the directory name when an error scene is tapped
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AzurPilotLogScreen(
    onBack: () -> Unit,
    onOpenDaily: (fileName: String) -> Unit,
    onOpenError: (dirName: String) -> Unit,
    viewModel: AzurPilotLogViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    com.azurpilot.ghio.ui.ksu.AppPageScaffold(
        title = stringResource(R.string.azurpilot_log_title),
        onBack = onBack,
    ) { padding ->
        val empty = state.errorDirs.isEmpty() && state.dailyLogs.isEmpty()
        if (empty) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(AppTokens.Spacing.lg),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text =
                        stringResource(
                            if (state.loading) R.string.common_loading
                            else R.string.azurpilot_log_empty
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@AppPageScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = AppTokens.Spacing.sm),
        ) {
            if (state.errorDirs.isNotEmpty()) {
                item(key = "header_errors") {
                    AzurPilotLogSectionHeader(stringResource(R.string.azurpilot_log_section_errors))
                }
                // 同一批记录是一条列表，不是一堆并列的独立卡片：逐行套卡会把
                // 「它们属于同一处」这层意思抹掉，还多出一圈没有信息量的框
                itemsIndexed(state.errorDirs, key = { _, dir -> "error_${dir.name}" }) { index, dir
                    ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(start = AppTokens.Spacing.lg))
                    }
                    AzurPilotErrorDirRow(dir = dir, onClick = { onOpenError(dir.name) })
                }
            }
            if (state.dailyLogs.isNotEmpty()) {
                item(key = "header_daily") {
                    AzurPilotLogSectionHeader(stringResource(R.string.azurpilot_log_section_daily))
                }
                itemsIndexed(state.dailyLogs, key = { _, file -> "daily_${file.name}" }) {
                    index,
                    file ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(start = AppTokens.Spacing.lg))
                    }
                    AzurPilotDailyLogRow(file = file, onClick = { onOpenDaily(file.name) })
                }
            }
        }
    }
}

/**
 * 渲染分区标题：与列表行的首列文字对齐，读的人才能把标题和它下面那批连起来
 *
 * Renders a section header: aligned with the rows' first text column so the heading visually binds
 * to the batch below it.
 */
@Composable
private fun AzurPilotLogSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(
                start = AppTokens.Spacing.lg,
                end = AppTokens.Spacing.lg,
                top = AppTokens.Spacing.md,
                bottom = AppTokens.Spacing.xs,
            ),
    )
}

@Composable
private fun AzurPilotErrorDirRow(dir: AzurPilotErrorDirInfo, onClick: () -> Unit) {
    AzurPilotLogRow(
        title = logTimestamp(dir.timestamp),
        subtitle = stringResource(R.string.azurpilot_log_error_meta, dir.fileCount),
        onClick = onClick,
    )
}

@Composable
private fun AzurPilotDailyLogRow(file: AzurPilotDailyLogInfo, onClick: () -> Unit) {
    AzurPilotLogRow(
        title = file.name,
        subtitle =
            stringResource(
                R.string.app_log_meta,
                formatFileSize(file.sizeBytes),
                logTimestamp(file.lastModified),
            ),
        onClick = onClick,
    )
}

@Composable
private fun AzurPilotLogRow(title: String, subtitle: String, onClick: () -> Unit) {
    com.azurpilot.ghio.ui.components.AppNavigationRow(
        label = title,
        description = subtitle,
        onClick = onClick,
    )
}
