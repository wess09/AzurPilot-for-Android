package com.azurpilot.ghio.ui.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.log.LogTailViewModel
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.theme.AzurPilotTheme
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import java.io.File
import org.koin.androidx.compose.koinViewModel

/**
 * 渲染尾部加载日志查看器（带顶栏的整页）：启动器日志与 AzurPilot 日志 txt 共用
 *
 * [file] 为 null（路径解析失败/已不存在）直接显示缺失态
 *
 * Renders the tail-loading log viewer (full page with a top bar); shared by the launcher logs and
 * AzurPilot txt logs.
 *
 * A null [file] (path resolution failed / file gone) shows the missing state directly.
 *
 * @param file 要查看的日志文件；null 走缺失态 / log file to view; null shows the missing state
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTailScreen(
    title: String,
    file: File?,
    onBack: () -> Unit,
) {
    com.azurpilot.ghio.ui.ksu.AppPageScaffold(title = title, onBack = onBack) { padding ->
        if (file == null) {
            LogTailMessage(
                textRes = R.string.log_tail_missing,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LogTailContent(
                file = file,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

/**
 * 查看器正文（无顶栏）：打开读最后 ~500KB，顶部「加载更早」每次再往前 500KB
 *
 * 长行换行，不挂横向滚动：`LazyColumn` 外面套 `horizontalScroll` 时列表宽度取的是当前可见的 最长一行，竖向一滑宽度就变、横向偏移跟着被夹一次，真机上卡到滑不动
 *
 * Renders the viewer body (no top bar): opens reading the last ~500KB, and the "load earlier"
 * button at the top walks 500KB further back each time.
 *
 * Long lines wrap; no horizontal scrolling: wrapping `LazyColumn` in `horizontalScroll` sizes the
 * list to the longest currently visible line, so a vertical flick changes the width and clamps the
 * horizontal offset once per scroll — on real devices it jams until it cannot move.
 */
@Composable
fun LogTailContent(
    file: File,
    modifier: Modifier = Modifier,
    viewModel: LogTailViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(file) { viewModel.load(file.absolutePath) }

    when {
        state.loading -> LogTailMessage(textRes = R.string.common_loading, modifier = modifier)
        state.missing -> LogTailMessage(textRes = R.string.log_tail_missing, modifier = modifier)
        state.lines.isEmpty() ->
            LogTailMessage(textRes = R.string.log_tail_empty, modifier = modifier)
        else -> {
            // 在列表外算一次：给 Text 传 fontFamily 会每行每次重组合成一份新 TextStyle
            // 字号走 bodySmall：日志是要逐行读的正文，labelSmall 那档小得只剩模糊的灰点
            val lineStyle =
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            val errorColor = MaterialTheme.colorScheme.error
            val warningColor = AzurPilotTheme.palette.warning

            LazyColumn(
                modifier = modifier.padding(horizontal = AppTokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs),
                contentPadding = PaddingValues(vertical = AppTokens.Spacing.lg),
            ) {
                if (state.hasMore) {
                    item(key = "load_earlier") {
                        TextButton(
                            onClick = { viewModel.loadEarlier() },
                            enabled = !state.loadingEarlier,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text =
                                    stringResource(
                                        if (state.loadingEarlier) R.string.common_loading
                                        else R.string.log_tail_load_earlier
                                    )
                            )
                        }
                    }
                }
                // 行序固定（只读快照），索引就是稳定 key
                itemsIndexed(state.lines) { _, line ->
                    Text(
                        text = line,
                        style = lineStyle,
                        color = logLineColor(line, errorColor, warningColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogTailMessage(textRes: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(AppTokens.Spacing.lg),
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
