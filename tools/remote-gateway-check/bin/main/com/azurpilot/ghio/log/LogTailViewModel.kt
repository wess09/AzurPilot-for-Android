package com.azurpilot.ghio.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurpilot.ghio.AppDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 日志正文查看页的 UI 状态
 *
 * UI state of the log-body viewer.
 *
 * @property lines 已加载的行，时间正序 / loaded lines in chronological order
 * @property hasMore false = 已经翻到文件头，「加载更早」不再出现 / false = the file head
 *   has been reached and "load earlier" disappears
 * @property loading 首次加载是否仍在进行 / whether the initial load is still running
 * @property loadingEarlier 是否正在往前翻页 / whether an earlier-page load is in flight
 * @property missing 文件不存在或读失败（多半刚被清理掉）/ the file is missing or failed
 *   to read (most likely just cleaned up)
 */
data class LogTailUiState(
    val lines: List<String> = emptyList(),
    /** false = 已经翻到文件头，「加载更早」不再出现 */
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val loadingEarlier: Boolean = false,
    /** 文件不存在或读失败（多半刚被清理掉） */
    val missing: Boolean = false,
)

/**
 * 尾部加载查看器的会话：打开读最后一块，「加载更早」往前续
 *
 * 启动器日志与 AzurPilot 日志 txt 共用；按绝对路径加载，路径解析归调用方
 *
 * The tail-loading viewer session: reads the last chunk on open and pages backward on
 * "load earlier".
 *
 * Shared by the launcher logs and AzurPilot daily-log txts; loading is by absolute path —
 * path resolution belongs to the caller.
 */
class LogTailViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(LogTailUiState())

    /** 页面 UI 状态 / Screen UI state. */
    val uiState: StateFlow<LogTailUiState> = _uiState.asStateFlow()

    /** 已加载的文件路径，用于幂等去重 / The loaded file path, for idempotence. */
    private var path: String? = null

    /** 下一页（更早内容）的读取起点 / The read start point for the next (earlier) page. */
    private var fromOffset: Long = 0

    private var earlierJob: Job? = null

    /**
     * 打开并加载日志尾部；幂等：重组重放同一个路径不重复读。IO 调度器上执行
     *
     * Opens and loads the log tail; idempotent — recomposition replaying the same path
     * does not re-read. Runs on the IO dispatcher.
     */
    fun load(filePath: String) {
        if (path == filePath) return
        path = filePath
        viewModelScope.launch {
            val chunk = withContext(AppDispatchers.IO) {
                val file = File(filePath)
                if (!file.isFile) return@withContext null
                runCatching { LogTailReader.readTail(file) }
                    .onFailure { Timber.w(it, "读日志失败：%s", filePath) }
                    .getOrNull()
            }
            _uiState.value = if (chunk == null) {
                LogTailUiState(loading = false, missing = true)
            } else {
                fromOffset = chunk.fromOffset
                LogTailUiState(
                    lines = chunk.text.toLines(),
                    hasMore = chunk.hasMore,
                    loading = false,
                )
            }
        }
    }

    /**
     * 往前加载更早的一页并插到列表头；仅当还有更多内容且没有在途加载时响应
     *
     * Loads the previous page and prepends it to the list; responds only when more
     * content exists and no load is in flight.
     */
    fun loadEarlier() {
        val filePath = path ?: return
        if (!_uiState.value.hasMore || earlierJob?.isActive == true) return
        earlierJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingEarlier = true) }
            val chunk = withContext(AppDispatchers.IO) {
                runCatching { LogTailReader.readChunk(File(filePath), fromOffset) }
                    .onFailure { Timber.w(it, "往前读日志失败：%s", filePath) }
                    .getOrNull()
            }
            if (chunk == null) {
                _uiState.update { it.copy(loadingEarlier = false) }
            } else {
                fromOffset = chunk.fromOffset
                val earlier = chunk.text.toLines()
                _uiState.update {
                    it.copy(
                        lines = earlier + it.lines,
                        hasMore = chunk.hasMore,
                        loadingEarlier = false,
                    )
                }
            }
        }
    }

    /** 尾部空行只是收尾换行符，不是内容 / Trailing empty lines are just the final newline, not content. */
    private fun String.toLines(): List<String> = lines().dropLastWhile { it.isEmpty() }
}
