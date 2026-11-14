package com.azurpilot.ghio.ui.logs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.azurpilot.ghio.constant.AppPaths
import java.io.File

/**
 * 渲染一份启动器日志的正文（二级页面）
 *
 * [fileName] 是相对 `log/` 目录的路径（路由参数已解码），解析时做一道 canonical 校验，
 * 防路由参数越出日志目录；正文渲染归共用的尾部加载查看器
 *
 * Renders the body of one launcher log (second-level page).
 *
 * [fileName] is a path relative to the `log/` directory (route argument already
 * decoded); resolution runs a canonical-path check so a crafted route argument
 * cannot escape the log directory. The body is rendered by the shared tail-loading
 * viewer.
 *
 * @param fileName 日志文件相对 `log/` 目录的路径 / log file path relative to the
 *   `log/` directory
 */
@Composable
fun AppLogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val file = remember(fileName) {
        runCatching {
            val candidate = File(AppPaths.LOG_DIR, fileName).canonicalFile
            candidate.takeIf { it.startsWith(AppPaths.LOG_DIR.canonicalFile) && it.isFile }
        }.getOrNull()
    }
    LogTailScreen(title = fileName, file = file, onBack = onBack)
}
