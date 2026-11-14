package com.azurpilot.ghio.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurpilot.ghio.AppDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 错误现场详情页的 UI 状态
 *
 * UI state of the error-detail screen.
 *
 * @property logTxt 现场正文（可能不存在：AzurPilot 写现场失败时只有截图）/ the scene
 *   body (may be absent: when AzurPilot fails to write the scene, only screenshots remain)
 * @property images 现场截图，按文件名排（同一现场多张时名字自带序号）/ scene screenshots
 *   sorted by file name (multiple shots of one scene carry ordinal suffixes)
 * @property loading 首次加载是否仍在进行 / whether the initial load is still running
 * @property missing 现场目录不存在 / the scene directory does not exist
 */
data class AzurPilotErrorDetailUiState(
    /** 现场正文（可能不存在：AzurPilot 写现场失败时只有截图） */
    val logTxt: File? = null,
    /** 现场截图，按文件名排（同一现场多张时名字自带序号） */
    val images: List<File> = emptyList(),
    val loading: Boolean = true,
    val missing: Boolean = false,
)

/**
 * 一个 AzurPilot 错误现场（`error/<毫秒时间戳>/`）的内容：log.txt + PNG 截图
 *
 * 加载按目录名幂等：同一 dirName 重复调用直接跳过；目录不存在时置 [AzurPilotErrorDetailUiState.missing]
 * 而不是报错——现场可能已被清理策略删除
 *
 * Presents the contents of one AzurPilot error scene (`error/<millis timestamp>/`):
 * log.txt plus PNG screenshots.
 *
 * Loading is idempotent per directory name: repeat calls with the same dirName are
 * skipped; a missing directory sets [AzurPilotErrorDetailUiState.missing] instead of
 * failing — the scene may already have been removed by the retention policy.
 */
class AzurPilotErrorDetailViewModel(
    private val source: AzurPilotLogSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AzurPilotErrorDetailUiState())

    /** 页面 UI 状态 / Screen UI state. */
    val uiState: StateFlow<AzurPilotErrorDetailUiState> = _uiState.asStateFlow()

    /** 已加载的现场目录名，用于幂等去重 / The loaded scene directory name, for idempotence. */
    private var loaded: String? = null

    /**
     * 加载指定错误现场；IO 调度器上执行
     *
     * Loads the given error scene; runs on the IO dispatcher.
     *
     * @param dirName 现场目录名（毫秒时间戳），同时是路由参数 / the scene directory
     *   name (millis timestamp), doubles as the route argument
     */
    fun load(dirName: String) {
        if (loaded == dirName) return
        loaded = dirName
        viewModelScope.launch {
            val state = withContext(AppDispatchers.IO) {
                val dir = source.errorDir(dirName)
                    ?: return@withContext AzurPilotErrorDetailUiState(loading = false, missing = true)
                val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
                AzurPilotErrorDetailUiState(
                    logTxt = files.firstOrNull { it.name == "log.txt" },
                    images = files.filter { it.name.endsWith(".png") }.sortedBy { it.name },
                    loading = false,
                )
            }
            _uiState.value = state
        }
    }
}
