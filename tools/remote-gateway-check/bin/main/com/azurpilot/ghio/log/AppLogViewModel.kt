package com.azurpilot.ghio.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.constant.AppPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `log/` 目录下的单个日志文件条目
 *
 * A single log-file entry under the `log/` directory.
 *
 * @property name 相对 `log/` 目录的路径（app.log、proot/session.log、crash/xxx.txt）/
 *   path relative to the `log/` directory (app.log, proot/session.log, crash/xxx.txt)
 * @property sizeBytes 文件大小 / file size in bytes
 * @property lastModified 最后修改时间戳 / last-modified timestamp
 */
data class AppLogFileInfo(
    /** 相对 `log/` 目录的路径（app.log、proot/session.log、crash/xxx.txt） */
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * 日志页 UI 状态
 *
 * UI state of the app-log screen.
 *
 * @property files 扫描出的文件列表 / scanned file list
 * @property loading 首次扫描是否仍在进行 / whether the initial scan is still running
 */
data class AppLogUiState(
    val files: List<AppLogFileInfo> = emptyList(),
    val loading: Boolean = true,
)

/**
 * 日志页用户意图
 *
 * User intents for the app-log screen.
 */
sealed interface AppLogIntent {
    /** 清空 app.log 滚动系列 / Clears the app.log rotation series. */
    data object ClearAll : AppLogIntent
}

/**
 * 启动器日志的文件列表（整个 `log/` 目录递归）；正文归 [LogTailViewModel]
 *
 * 「全部清除」的范围维持 app.log 滚动系列（`AppLogWriter.purge()`），不扩大到
 * session.log 与 crash——那两份分别是会话时间线与崩溃现场，清了等于自断排查后路
 *
 * File listing for the launcher log screen (the whole `log/` directory, recursive);
 * the log body itself belongs to [LogTailViewModel].
 *
 * "Clear all" stays scoped to the app.log rotation series ([AppLogWriter.purge]),
 * never reaching session.log or crash — those are the session timeline and the crash
 * scenes respectively, and wiping them would destroy the evidence needed for
 * troubleshooting.
 */
class AppLogViewModel(
    private val writer: AppLogWriter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppLogUiState())

    /** 页面 UI 状态 / Screen UI state. */
    val uiState: StateFlow<AppLogUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    /**
     * 处理用户意图；目前仅支持 [AppLogIntent.ClearAll]
     *
     * Handles a user intent; currently only [AppLogIntent.ClearAll].
     */
    fun onIntent(intent: AppLogIntent) {
        when (intent) {
            AppLogIntent.ClearAll -> viewModelScope.launch {
                // 必须 join：删除排在写入通道上，不等它刷出来的还是旧的那几份
                writer.purge().join()
                reload()
            }
        }
    }

    /** 在 IO 调度器上重扫 `log/` 目录并刷新文件列表 / Rescans the `log/` directory on the IO dispatcher and refreshes the file list. */
    private suspend fun reload() {
        val root = AppPaths.LOG_DIR
        val files = withContext(AppDispatchers.IO) {
            LauncherLogScanner.scan(root).map {
                AppLogFileInfo(
                    name = it.relativeTo(root).invariantSeparatorsPath,
                    sizeBytes = it.length(),
                    lastModified = it.lastModified(),
                )
            }
        }
        _uiState.value = AppLogUiState(files = files, loading = false)
    }
}
