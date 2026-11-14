package com.azurpilot.ghio.ui.logs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.azurpilot.ghio.log.AzurPilotLogSource
import org.koin.compose.koinInject

/**
 * 渲染一份 AzurPilot 按天日志的正文（二级页面），与启动器日志共用尾部加载查看器
 *
 * Renders the body of one daily AzurPilot log (second-level page); shares the
 * tail-loading viewer with the launcher log screen.
 *
 * @param fileName 按天日志的文件名（路由参数已解码）/ daily log file name (route
 *   argument already decoded)
 */
@Composable
fun AzurPilotLogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
    source: AzurPilotLogSource = koinInject(),
) {
    val file = remember(fileName) { source.dailyFile(fileName) }
    LogTailScreen(title = fileName, file = file, onBack = onBack)
}
