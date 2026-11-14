package com.azurpilot.ghio.ui.logs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.log.AppLogFileInfo
import com.azurpilot.ghio.log.AppLogIntent
import com.azurpilot.ghio.log.AppLogViewModel
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.AppPromptDialog
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider as HorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import org.koin.androidx.compose.koinViewModel

/**
 * 渲染启动器日志的文件列表（二级页面）：`log/` 目录递归（app.log 系列 / session.log / crash）
 *
 * 与运行历史同一形态的两级页；版面对齐 参考实现 的 `ErrorLogView`
 *
 * Renders the launcher log file list (second-level page): recursive walk of the `log/` directory
 * (app.log family / session.log / crash).
 *
 * Same two-level shape as the run history; the layout follows the reference implementation's
 * `ErrorLogView`.
 *
 * @param onOpen 点开某行时回传日志文件名（相对 `log/` 目录） / invoked with the log file name (relative to `log/`)
 *   when a row is tapped
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogScreen(
    onBack: () -> Unit,
    onOpen: (fileName: String) -> Unit,
    viewModel: AppLogViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AppPromptDialog(
            title = stringResource(R.string.app_log_clear_title),
            message = stringResource(R.string.app_log_clear_message),
            icon = Icons.Outlined.DeleteOutline,
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.dialog_cancel),
            onConfirm = {
                viewModel.onIntent(AppLogIntent.ClearAll)
                confirmClear = false
            },
            onDismissRequest = { confirmClear = false },
            dismissOnOutsideClick = true,
            destructive = true,
        )
    }

    // M3 的顶栏滚动行为：列表滚起来时顶栏换成容器色并抬起，与主 tab 页同一套

    com.azurpilot.ghio.ui.ksu.AppPageScaffold(
        title = stringResource(R.string.app_log_title),
        onBack = onBack,
        actions = {
            if (state.files.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text(
                        text = stringResource(R.string.app_log_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    ) { padding ->
        if (state.files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(AppTokens.Spacing.lg),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text =
                        stringResource(
                            if (state.loading) R.string.common_loading else R.string.app_log_empty
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
            // 同一批日志文件是一条列表，不是一堆并列的独立卡片：
            // 逐行套卡会把「它们属于同一处」这层意思抹掉，还多出一圈没有信息量的框
            itemsIndexed(state.files, key = { _, file -> file.name }) { index, file ->
                if (index > 0) {
                    // 分隔线向内缩到文字首列，是 M3 列表分隔线的取法
                    HorizontalDivider(modifier = Modifier.padding(start = AppTokens.Spacing.lg))
                }
                AppLogFileRow(file = file, onClick = { onOpen(file.name) })
            }
        }
    }
}

@Composable
private fun AppLogFileRow(file: AppLogFileInfo, onClick: () -> Unit) {
    com.azurpilot.ghio.ui.components.AppNavigationRow(
        label = file.name,
        description =
            stringResource(
                R.string.app_log_meta,
                formatFileSize(file.sizeBytes),
                logTimestamp(file.lastModified),
            ),
        onClick = onClick,
    )
}
