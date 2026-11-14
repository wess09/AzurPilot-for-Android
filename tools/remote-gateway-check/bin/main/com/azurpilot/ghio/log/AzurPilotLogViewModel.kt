package com.azurpilot.ghio.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurpilot.ghio.AppDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 错误现场列表条目
 *
 * One entry of the error-scene list.
 *
 * @property name 目录名（毫秒时间戳），路由参数 / directory name (millis timestamp),
 *   doubles as the route argument
 * @property timestamp 目录名解析出的时间戳 / the timestamp parsed from the directory name
 * @property fileCount 现场目录内文件数（log.txt + 截图）/ the number of files inside the
 *   scene directory (log.txt + screenshots)
 */
data class AzurPilotErrorDirInfo(
    /** 目录名（毫秒时间戳），路由参数 */
    val name: String,
    val timestamp: Long,
    val fileCount: Int,
)

/**
 * 按天日志列表条目
 *
 * One entry of the daily-log list.
 *
 * @property name 文件名 / the file name
 * @property sizeBytes 文件大小 / file size in bytes
 * @property lastModified 最后修改时间戳 / last-modified timestamp
 */
data class AzurPilotDailyLogInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * AzurPilot 日志页 UI 状态：错误现场 + 按天日志两区
 *
 * UI state of the AzurPilot log screen: error scenes and daily logs.
 *
 * @property errorDirs 错误现场列表，时间戳倒序 / error scenes, newest first
 * @property dailyLogs 按天日志列表，mtime 倒序 / daily logs, newest mtime first
 * @property loading 首次扫描是否仍在进行 / whether the initial scan is still running
 */
data class AzurPilotLogUiState(
    val errorDirs: List<AzurPilotErrorDirInfo> = emptyList(),
    val dailyLogs: List<AzurPilotDailyLogInfo> = emptyList(),
    val loading: Boolean = true,
)

/**
 * AzurPilot 日志列表（`rootfs/opt/azurpilot/log`）：错误现场 + 按天日志两区；
 * 正文归 [LogTailViewModel]
 *
 * Lists AzurPilot logs (`rootfs/opt/azurpilot/log`) in two sections — error scenes and
 * daily logs; the log body itself belongs to [LogTailViewModel].
 */
class AzurPilotLogViewModel(
    private val source: AzurPilotLogSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AzurPilotLogUiState())

    /** 页面 UI 状态 / Screen UI state. */
    val uiState: StateFlow<AzurPilotLogUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    /** 在 IO 调度器上重扫日志目录并刷新两区列表 / Rescans the log directory on the IO dispatcher and refreshes both sections. */
    private suspend fun reload() {
        val state = withContext(AppDispatchers.IO) {
            AzurPilotLogUiState(
                errorDirs = source.errorDirs().map { dir ->
                    AzurPilotErrorDirInfo(
                        name = dir.name,
                        timestamp = dir.name.toLongOrNull() ?: 0L,
                        fileCount = dir.listFiles()?.count { it.isFile } ?: 0,
                    )
                },
                dailyLogs = source.dailyLogs().map {
                    AzurPilotDailyLogInfo(
                        name = it.name,
                        sizeBytes = it.length(),
                        lastModified = it.lastModified(),
                    )
                },
                loading = false,
            )
        }
        _uiState.value = state
    }
}
