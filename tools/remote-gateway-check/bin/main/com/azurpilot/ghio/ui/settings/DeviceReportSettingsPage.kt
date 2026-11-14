package com.azurpilot.ghio.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.components.AppActionLabel
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.report.DeviceReportUiState
import com.azurpilot.ghio.report.DeviceReportViewModel
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.AppInfoRow
import com.azurpilot.ghio.ui.components.AppCheckboxPreference
import com.azurpilot.ghio.ui.components.AppSingleChoiceFlow
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppDialog as AlertDialog
import com.azurpilot.ghio.ui.ksu.AppLinearProgressIndicator as LinearProgressIndicator
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import org.koin.androidx.compose.koinViewModel

/**
 * 在 Material 3 设置二级页预览公开机型参数，并要求用户同意公开后才发送。
 *
 * Previews publishable model parameters in a Material 3 settings page and requires consent to
 * publication before submitting. Business state belongs to the ViewModel.
 */
@Composable
fun DeviceReportSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DeviceReportViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DeviceReportSettingsContent(
        state,
        viewModel::selectCompatibility,
        viewModel::submit,
        onBack,
        modifier,
    )
}

/**
 * 呈现预览、错误和提交结果；成功弹窗可关闭，结果链接留在页面上。
 *
 * Presents the preview, errors and submission result. The success dialog is dismissible, while the
 * result link remains on the page.
 */
@Composable
internal fun DeviceReportSettingsContent(
    state: DeviceReportUiState,
    onCompatibilitySelect: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var consent by rememberSaveable { mutableStateOf(false) }
    var acknowledgedCommentId by rememberSaveable { mutableLongStateOf(0L) }
    val result = state.result
    val resultMessage = result?.let {
        stringResource(
            if (it.duplicate) R.string.device_report_duplicate else R.string.device_report_success
        )
    }
    if (result != null && acknowledgedCommentId != result.commentId) {
        AlertDialog(
            onDismissRequest = { acknowledgedCommentId = result.commentId },
            title = { Text(stringResource(R.string.device_report_complete_title)) },
            text = { Text(resultMessage.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        acknowledgedCommentId = result.commentId
                        uriHandler.openUri(result.commentUrl)
                    }
                ) {
                    AppActionLabel(AppSymbol.Link, stringResource(R.string.device_report_open_issue))
                }
            },
            dismissButton = {
                TextButton(onClick = { acknowledgedCommentId = result.commentId }) {
                    Text(stringResource(R.string.device_report_done))
                }
            },
        )
    }
    SettingsSubPage(
        titleRes = R.string.device_report_title,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (result != null) {
            AppCard {
                Text(resultMessage.orEmpty(), style = MaterialTheme.typography.titleSmall)
                Button(
                    onClick = { uriHandler.openUri(result.commentUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppActionLabel(AppSymbol.Link, stringResource(R.string.device_report_open_issue))
                }
            }
        }
        AppCard {
            Text(
                stringResource(R.string.device_report_privacy),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val report = state.report
        if (report != null) {
            AppCard {
                AppInfoRow(
                    stringResource(R.string.device_report_marketing_name),
                    report.marketingName,
                )
                AppInfoRow(
                    stringResource(R.string.device_report_rom),
                    (report.romName + " " + report.romVersion).trim(),
                )
                AppInfoRow(
                    stringResource(R.string.device_report_build_incremental),
                    report.buildIncremental,
                )
                AppInfoRow(
                    stringResource(R.string.device_report_build_display),
                    report.buildDisplay,
                )
                AppInfoRow(stringResource(R.string.device_report_manufacturer), report.manufacturer)
                AppInfoRow(stringResource(R.string.device_report_brand), report.brand)
                AppInfoRow(stringResource(R.string.device_report_model), report.model)
                AppInfoRow(stringResource(R.string.device_report_device), report.device)
                AppInfoRow(stringResource(R.string.device_report_product), report.product)
                AppInfoRow(stringResource(R.string.device_report_hardware), report.hardware)
                AppInfoRow(
                    stringResource(R.string.device_report_soc),
                    (report.socManufacturer + " " + report.socModel).trim().ifEmpty {
                        stringResource(R.string.device_report_unknown)
                    },
                )
                AppInfoRow(
                    stringResource(R.string.device_report_android),
                    stringResource(
                        R.string.device_report_android_value,
                        report.androidRelease,
                        report.sdkInt,
                    ),
                )
                AppInfoRow(
                    stringResource(R.string.device_report_patch),
                    report.securityPatch.ifEmpty { stringResource(R.string.device_report_unknown) },
                )
                AppInfoRow(
                    stringResource(R.string.device_report_abis),
                    report.supportedAbis.joinToString(),
                )
                AppInfoRow(
                    stringResource(R.string.device_report_screen),
                    stringResource(
                        R.string.device_report_screen_value,
                        report.screenWidth,
                        report.screenHeight,
                        report.densityDpi,
                        report.refreshRate,
                    ),
                )
                AppInfoRow(
                    stringResource(R.string.device_report_memory),
                    stringResource(R.string.device_report_memory_value, report.memoryGiB),
                )
                AppInfoRow(
                    stringResource(R.string.settings_version),
                    "${report.appVersion} (${report.appVersionCode})",
                )
                AppInfoRow(
                    stringResource(R.string.device_report_backend),
                    if (report.backend == "ROOT") "Root" else "Shizuku",
                )
                AppInfoRow(
                    stringResource(R.string.device_report_run_mode),
                    stringResource(
                        if (report.runMode == "FOREGROUND") R.string.device_report_foreground
                        else R.string.device_report_background
                    ),
                )
            }
            if (!state.sending && state.result == null) {
                AppCard {
                    Text(
                        stringResource(R.string.device_report_compatibility),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.device_report_test_required),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    AppSingleChoiceFlow(
                        options =
                            listOf(
                                "working" to stringResource(R.string.device_report_working),
                                "not_working" to stringResource(R.string.device_report_not_working),
                            ),
                        selected = report.compatibility,
                        onSelect = onCompatibilitySelect,
                    )
                    state.errorRes?.let { error ->
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                    }
                    AppCheckboxPreference(
                        title = stringResource(R.string.device_report_consent),
                        checked = consent,
                        onCheckedChange = { consent = it },
                    )
                    Button(
                        onClick = onSubmit,
                        enabled =
                            consent && report.compatibility in setOf("working", "not_working"),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AppActionLabel(AppSymbol.Upload, stringResource(R.string.device_report_submit))
                    }
                }
            }
        }
        if (state.sending || (report == null && state.errorRes == null)) {
            AppCard {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(
                        if (state.sending) R.string.device_report_sending
                        else R.string.device_report_collecting
                    )
                )
            }
        }
        if (report == null) {
            state.errorRes?.let { error ->
                AppCard { Text(stringResource(error), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
