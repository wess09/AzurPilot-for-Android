package com.azurpilot.ghio.ocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * 保存 OCR 诊断页快照；模型测试与 AP 接线测试分别报告，业务调用数不包含测试。
 *
 * Holds OCR diagnostics snapshots, reporting model and AP integration tests separately.
 * Business request counts exclude diagnostic calls.
 * @property status 宿主共享引擎状态。 / Shared host engine status.
 * @property selectedHash 所选内置模型。 / Selected bundled model.
 * @property refreshing 正在读取状态。 / Whether status is being read.
 * @property testing 正在执行测试。 / Whether a test is running.
 * @property updating 正在保存和应用硬件加速开关。 / Whether the acceleration flag is being applied.
 * @property runtimeReady AP 已运行，可执行接线测试。 / AP is running and can be tested.
 * @property modelTest 模型测试结果。 / Model test result.
 * @property apTest AP 接线测试结果。 / AP integration result.
 * @property error 状态或测试错误。 / Status or test error.
 */
data class OcrDiagnosticsState(
    val status: JsonObject? = null,
    val selectedHash: String = "",
    val refreshing: Boolean = false,
    val testing: Boolean = false,
    val updating: Boolean = false,
    val runtimeReady: Boolean = false,
    val modelTest: JsonObject? = null,
    val apTest: JsonObject? = null,
    val error: String? = null,
)

/**
 * 在 IO 协程读取共享 OCR 引擎、测试认证接口，并调用已运行的 AP 识别器。
 *
 * 测试在后台线程执行，状态刷新不会与测试并行争用模型锁。
 *
 * Reads the shared OCR engine, tests its authenticated API, and invokes the running AP
 * recognizer on IO coroutines. Blocking tests run on workers; refreshes do not compete with
 * tests for the model lock.
 */
class OcrDiagnosticsViewModel(private val server: OcrServer, private val host: ProotHost) : ViewModel() {
    private val state = MutableStateFlow(OcrDiagnosticsState())
    private val reportFormat = Json { prettyPrint = true }
    private var refreshJob: Job? = null

    /** 页面可观察的不可变快照。 / Immutable observable page snapshot. */
    val uiState = state.asStateFlow()

    /** 从 IO 读取状态，不初始化推理会话。 / Reads status on IO without initializing sessions. */
    fun refresh() {
        if (state.value.testing || state.value.updating || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch(AppDispatchers.IO) {
            state.update { it.copy(refreshing = true) }
            try {
                readStatus()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state.update { it.copy(error = error.message?.take(500)) }
            } catch (error: LinkageError) {
                state.update { it.copy(error = error.message?.take(500)) }
            } finally {
                state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** 选择模型并清除上一模型的结果。 / Selects a model and clears the previous result. */
    fun select(hash: String) {
        if (!state.value.testing && !state.value.updating) state.update {
            it.copy(selectedHash = hash, modelTest = null, apTest = null, error = null)
        }
    }

    /**
     * 串行测试模型及 AP；CPU 成功与 NPU 成功分别显示。
     *
     * Tests model and AP serially, distinguishing CPU and NPU.
     */
    fun test() {
        val hash = state.value.selectedHash
        if (state.value.testing || state.value.refreshing || state.value.updating || hash.isEmpty()) return
        state.update { it.copy(testing = true, modelTest = null, apTest = null, error = null) }
        viewModelScope.launch(AppDispatchers.IO) {
            try {
                val model = server.test(hash)
                state.update { it.copy(modelTest = model) }
                val integration = if (host.state.value.phase == ProotPhase.RUNNING) {
                    host.testOcrIntegration(hash)
                } else buildJsonObject {
                    put("ok", false)
                    put("runtime_not_started", true)
                }
                state.update { it.copy(apTest = integration) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state.update { it.copy(error = error.message?.take(500)) }
            } catch (error: LinkageError) {
                state.update { it.copy(error = error.message?.take(500)) }
            } finally {
                runCatching { readStatus() }
                state.update { it.copy(testing = false) }
            }
        }
    }

    /**
     * 保存并应用硬件加速选择；界面等待共享引擎确认后再更新开关。
     *
     * Persists and applies the acceleration choice, updating the switch after the shared
     * engine acknowledges it. Clears previous test results when the backend choice changes.
     */
    fun setHardwareAcceleration(enabled: Boolean) {
        if (state.value.testing || state.value.refreshing || state.value.updating) return
        state.update { it.copy(updating = true, error = null) }
        viewModelScope.launch(AppDispatchers.IO) {
            try {
                readStatus(server.setHardwareAcceleration(enabled))
                state.update { it.copy(modelTest = null, apTest = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                state.update { it.copy(error = error.message?.take(500)) }
            } catch (error: LinkageError) {
                state.update { it.copy(error = error.message?.take(500)) }
            } finally {
                state.update { it.copy(updating = false) }
            }
        }
    }

    /** 返回可复制的无口令诊断报告。 / Returns a copyable diagnostic report without tokens. */
    fun report(): String = buildJsonObject {
        state.value.status?.let { put("status", it) }
        state.value.modelTest?.let { put("model_test", it) }
        state.value.apTest?.let { put("ap_test", it) }
        state.value.error?.let { put("error", it) }
    }.let { reportFormat.encodeToString(JsonObject.serializer(), it) }

    private fun readStatus(snapshot: JsonObject = server.status()) {
        val candidates = snapshot.getValue("model_status").jsonArray.map { it.jsonObject }
            .filter { it["testable"]?.jsonPrimitive?.booleanOrNull == true }
        state.update {
            it.copy(status = snapshot, runtimeReady = host.state.value.phase == ProotPhase.RUNNING,
                selectedHash = it.selectedHash.ifEmpty {
                    (candidates.firstOrNull { entry -> entry["name"]?.jsonPrimitive?.content?.startsWith("alocr-en") == true }
                        ?: candidates.firstOrNull())?.get("model_sha256")?.jsonPrimitive?.content.orEmpty()
                })
        }
    }
}
