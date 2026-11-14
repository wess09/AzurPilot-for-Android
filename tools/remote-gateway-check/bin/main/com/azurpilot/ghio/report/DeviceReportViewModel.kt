package com.azurpilot.ghio.report

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurpilot.ghio.R
import com.azurpilot.ghio.privileged.PermissionGateway
import com.azurpilot.ghio.settings.AppSettingsGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 保存机型预览与发送进度，跨界面重建保留状态。
 *
 * Stores preview and submission progress across UI recreation.
 *
 * @property report 已去敏快照，未加载时为空。 / Sanitized snapshot, null before loading.
 * @property sending 是否正在发送。 / Whether a submission is in progress.
 * @property result 成功评论，未成功时为空。 / Successful comment result, null before success.
 * @property errorRes 本地错误文案资源。 / Local error message resource.
 */
data class DeviceReportUiState(
    val report: DeviceReport? = null,
    val sending: Boolean = false,
    val result: DeviceReportResult? = null,
    val errorRes: Int? = null,
)

/**
 * 在主线程管理提交会话，只有用户明确确认后才发送，IO 工作交给客户端。
 *
 * Manages the reporting session on main, submitting only after user confirmation.
 * The client handles IO work.
 */
class DeviceReportViewModel(
    context: Context,
    private val client: DeviceReportClient,
    permissionGateway: PermissionGateway,
    settings: AppSettingsGateway,
) : ViewModel() {
    private val state = MutableStateFlow(DeviceReportUiState())

    /** 供页面订阅的会话状态。 / Session state observed by the page. */
    val uiState = state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    DeviceReport.collect(
                        context,
                        backend = permissionGateway.state.value.configuredBackend.name,
                        runMode = settings.runMode.value.name,
                    )
                }
                state.update { it.copy(report = report) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update { it.copy(errorRes = R.string.device_report_collect_error) }
            }
        }
    }

    /** 更新用户声明的使用结果。 / Updates the user-declared compatibility result. */
    fun selectCompatibility(value: String) {
        if (state.value.sending || state.value.result != null ||
            value !in setOf("working", "not_working")
        ) return
        state.update { it.copy(report = it.report?.copy(compatibility = value), errorRes = null) }
    }

    /** 提交当前快照并保留结果；重复点击不会并发发送。 / Submits the snapshot without concurrent repeats. */
    fun submit() {
        val report = state.value.report ?: return
        if (state.value.sending || state.value.result != null) return
        if (report.compatibility !in setOf("working", "not_working")) {
            state.update { it.copy(errorRes = R.string.device_report_test_required) }
            return
        }
        state.update { it.copy(sending = true, errorRes = null) }
        viewModelScope.launch {
            try {
                val result = client.submit(report)
                state.update { it.copy(sending = false, result = result) }
            } catch (e: CancellationException) {
                state.update { it.copy(sending = false) }
                throw e
            } catch (e: DeviceReportException) {
                state.update { it.copy(sending = false, errorRes = e.messageRes) }
            } catch (_: Exception) {
                state.update { it.copy(sending = false, errorRes = R.string.device_report_send_error) }
            }
        }
    }
}
