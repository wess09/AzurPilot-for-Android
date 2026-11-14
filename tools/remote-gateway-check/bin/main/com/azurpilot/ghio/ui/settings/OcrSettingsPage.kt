package com.azurpilot.ghio.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.components.AppActionLabel
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.ocr.OcrDiagnosticsViewModel
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.AppFieldLabel
import com.azurpilot.ghio.ui.components.AppInfoRow
import com.azurpilot.ghio.ui.components.AppLabeledControlRow
import com.azurpilot.ghio.ui.components.AppSingleChoiceFlow
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppLinearProgressIndicator as LinearProgressIndicator
import com.azurpilot.ghio.ui.ksu.AppSwitch as Switch
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import org.koin.androidx.compose.koinViewModel

/**
 * 呈现 OCR 实际后端和本机测试；前台每五秒读取状态，耗时操作由 ViewModel 在 IO 执行。
 *
 * Presents actual OCR backends and local tests. Refreshes every five seconds while visible; the
 * ViewModel performs blocking work on IO. Test calls remain separate from AP business counts.
 */
@Composable
fun OcrSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OcrDiagnosticsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val reportLabel = stringResource(R.string.ocr_copy_report)
    LaunchedEffect(owner, viewModel) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                viewModel.refresh()
                delay(5_000)
            }
        }
    }
    val status = state.status
    val models = status?.get("model_status")?.jsonArray?.map { it.jsonObject }.orEmpty()
    SettingsSubPage(
        titleRes = R.string.ocr_title,
        onBack = onBack,
        modifier = modifier,
    ) {
        AppCard {
            AppLabeledControlRow(stringResource(R.string.ocr_hardware_acceleration), icon = Icons.Outlined.Memory) {
                Switch(
                    checked =
                        status?.get("hardware_acceleration_enabled")?.jsonPrimitive?.booleanOrNull
                            ?: true,
                    onCheckedChange = viewModel::setHardwareAcceleration,
                    enabled =
                        status != null && !state.testing && !state.refreshing && !state.updating,
                )
            }
            OcrHint(stringResource(R.string.ocr_hardware_acceleration_hint))
            if (state.updating) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        AppCard {
            AppInfoRow(stringResource(R.string.ocr_soc), status.text("soc").ifEmpty { "—" })
            AppInfoRow(
                stringResource(R.string.ocr_vendor),
                stringResource(
                    when (status.text("vendor")) {
                        "qualcomm" -> R.string.ocr_vendor_qualcomm
                        "mediatek" -> R.string.ocr_vendor_mediatek
                        "hiai" -> R.string.ocr_vendor_hiai
                        "xring_unavailable" -> R.string.ocr_vendor_xring
                        else -> R.string.ocr_vendor_other
                    }
                ),
            )
            AppInfoRow(
                stringResource(R.string.ocr_libraries),
                stringResource(
                    if (status.flag("npu_libraries_bundled")) R.string.ocr_ready
                    else R.string.ocr_unavailable
                ),
            )
            AppInfoRow(
                stringResource(R.string.ocr_npu_state),
                stringResource(
                    when (status.text("npu_state")) {
                        "verified" -> R.string.ocr_npu_verified
                        "user_disabled" -> R.string.ocr_user_disabled
                        "disabled" -> R.string.ocr_npu_disabled
                        "partially_disabled" -> R.string.ocr_npu_partial
                        "unavailable" -> R.string.ocr_unavailable
                        else -> R.string.ocr_npu_untested
                    }
                ),
            )
            AppInfoRow(
                stringResource(R.string.ocr_ap_runtime),
                stringResource(
                    if (state.runtimeReady) R.string.ocr_running else R.string.ocr_not_running
                ),
            )
            OcrHint(stringResource(R.string.ocr_status_hint))
            TextButton(
                onClick = viewModel::refresh,
                enabled = !state.testing && !state.refreshing && !state.updating,
            ) {
                AppActionLabel(AppSymbol.Refresh, stringResource(R.string.ocr_refresh))
            }
        }
        AppCard {
            AppSingleChoiceFlow(
                title = stringResource(R.string.ocr_test_model),
                options =
                    models
                        .filter { it.flag("testable") }
                        .map { it.text("model_sha256") to it.text("name").removeSuffix(".onnx") },
                selected = state.selectedHash,
                onSelect = viewModel::select,
                enabled = !state.testing && !state.updating,
            )
            OcrHint(stringResource(R.string.ocr_test_hint))
            if (!state.runtimeReady) OcrHint(stringResource(R.string.ocr_start_ap_hint))
            Button(
                onClick = viewModel::test,
                enabled =
                    !state.testing &&
                        !state.refreshing &&
                        !state.updating &&
                        state.selectedHash.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppActionLabel(
                    AppSymbol.Cpu,
                    stringResource(if (state.testing) R.string.ocr_testing else R.string.ocr_test),
                    busy = state.testing,
                )
            }
            if (state.testing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        state.modelTest?.let { result ->
            AppCard {
                AppFieldLabel(stringResource(R.string.ocr_model_test_passed))
                AppInfoRow(
                    stringResource(R.string.ocr_backend),
                    backendLabel(result.text("backend")),
                )
                AppInfoRow(stringResource(R.string.ocr_cold_time), milliseconds(result, "cold_ms"))
                AppInfoRow(
                    stringResource(R.string.ocr_steady_time),
                    milliseconds(result, "steady_ms"),
                )
                AppInfoRow(stringResource(R.string.ocr_cpu_time), milliseconds(result, "cpu_ms"))
                if (result.flag("cpu_execution_verified"))
                    OcrHint(stringResource(R.string.ocr_cpu_verified))
                AppInfoRow(
                    stringResource(R.string.ocr_score_difference),
                    result.text("max_abs_error"),
                )
                OcrHint(
                    stringResource(
                        if (result.flag("character_predictions_equal"))
                            R.string.ocr_precision_passed
                        else R.string.ocr_predictions_differ
                    )
                )
                OcrHint(stringResource(R.string.ocr_measurement_hint))
            }
        }
        state.apTest?.let { result ->
            AppCard {
                AppFieldLabel(stringResource(R.string.ocr_ap_test))
                when {
                    result.flag("runtime_not_started") ->
                        OcrHint(stringResource(R.string.ocr_start_ap_hint))
                    result.flag("ok") -> {
                        Text(stringResource(R.string.ocr_ap_test_passed))
                        AppInfoRow(
                            stringResource(R.string.ocr_backend),
                            backendLabel(result.text("backend")),
                        )
                        AppInfoRow(
                            stringResource(R.string.ocr_recognized_text),
                            result["texts"]
                                ?.jsonArray
                                ?.joinToString(" ") { it.jsonPrimitive.content }
                                .orEmpty()
                                .ifEmpty { "—" },
                        )
                        AppInfoRow(
                            stringResource(R.string.ocr_expected_text),
                            result.text("expected_text"),
                        )
                        AppInfoRow(
                            stringResource(R.string.ocr_ap_time),
                            milliseconds(result, "elapsed_ms"),
                        )
                        if (!result.flag("sample_text_matches")) {
                            Text(
                                stringResource(R.string.ocr_text_mismatch),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    else -> Text(result.text("error"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        models.forEach { model ->
            AppCard {
                AppFieldLabel(model.text("name"))
                AppInfoRow(
                    stringResource(R.string.ocr_backend),
                    backendLabel(model.text("backend")),
                )
                AppInfoRow(stringResource(R.string.ocr_ap_requests), model.text("ap_requests"))
                AppInfoRow(
                    stringResource(R.string.ocr_test_requests),
                    model.text("diagnostic_requests"),
                )
                val business = model["last_ap_call"]?.jsonObject
                AppInfoRow(
                    stringResource(R.string.ocr_ap_backend),
                    if (business != null) backendLabel(business.text("backend"))
                    else stringResource(R.string.ocr_ap_no_calls),
                )
                business?.get("shape")?.jsonArray?.let { shape ->
                    AppInfoRow(
                        stringResource(R.string.ocr_ap_shape),
                        shape.joinToString(" × ") { it.jsonPrimitive.content },
                    )
                }
                if (model.containsKey("last_ms")) {
                    AppInfoRow(
                        stringResource(R.string.ocr_last_time),
                        milliseconds(model, "last_ms"),
                    )
                }
                val reason =
                    when (model.text("cpu_reason")) {
                        "user_disabled" -> R.string.ocr_user_disabled
                        "detector",
                        "model_not_accelerated" -> R.string.ocr_cpu_detector
                        "dynamic_shape" -> R.string.ocr_cpu_shape
                        "npu_unavailable" -> R.string.ocr_cpu_unavailable
                        "npu_failed" -> R.string.ocr_cpu_failed
                        else -> null
                    }
                reason?.let { OcrHint(stringResource(it)) }
                model["error"]?.let {
                    Text(it.jsonPrimitive.content, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (status != null) {
            AppCard {
                TextButton(
                    onClick = {
                        context
                            .getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(reportLabel, viewModel.report()))
                    },
                    enabled = !state.testing,
                ) {
                    AppActionLabel(AppSymbol.Copy, stringResource(R.string.ocr_copy_report))
                }
            }
        }
    }
}

@Composable
private fun OcrHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun backendLabel(backend: String): String =
    stringResource(
        when (backend) {
            "litert_cpu",
            "onnx_cpu",
            "original_runtime_cpu" -> R.string.ocr_backend_cpu
            "litert_npu_gpu_softmax_with_cpu_fallback" -> R.string.ocr_backend_litert_gpu_softmax
            "litert_npu_with_cpu_fallback" -> R.string.ocr_backend_litert
            "hiai_npu" -> R.string.ocr_backend_hiai
            else -> R.string.ocr_backend_pending
        }
    )

@Composable
private fun milliseconds(value: JsonObject, key: String): String =
    stringResource(R.string.ocr_milliseconds, value[key]?.jsonPrimitive?.doubleOrNull ?: 0.0)

private fun JsonObject?.text(key: String): String = this?.get(key)?.jsonPrimitive?.content.orEmpty()

private fun JsonObject?.flag(key: String): Boolean =
    this?.get(key)?.jsonPrimitive?.booleanOrNull == true
