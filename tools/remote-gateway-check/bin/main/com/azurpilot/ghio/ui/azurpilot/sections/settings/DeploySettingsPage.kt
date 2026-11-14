package com.azurpilot.ghio.ui.azurpilot.sections.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.ApValue
import com.azurpilot.ghio.proot.AzurPilotDeployField
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.proot.sameValueAs
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApErrorState
import com.azurpilot.ghio.ui.azurpilot.apContentWidth
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.ksu.appSettingsGroup
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppSwitch as Switch
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextField as OutlinedTextField
import org.koin.compose.koinInject

/**
 * 部署设置（`config/deploy.yaml`）
 *
 * 字段弹窗确认后直接提交并回读配置；保存失败时保留草稿供重试。设置通常在重启后生效。
 *
 * The deploy-settings page (`config/deploy.yaml`).
 *
 * Confirming a field commits and reloads the persisted config. Failed saves retain the draft for
 * retry. Settings usually take effect after a restart.
 *
 * @param repository 网关仓库 / the gateway repository
 */
@Composable
fun DeploySettingsPage(repository: AzurPilotRepository, prootHost: ProotHost = koinInject()) {
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    val draft = remember { mutableStateMapOf<String, ApValue>() }

    val loadFailed = stringResource(R.string.ap_deploy_load_failed)
    val saveFailed = stringResource(R.string.ap_deploy_save_failed)
    val prootState by prootHost.state.collectAsStateWithLifecycle()
    val sessionBusy = prootState.phase in listOf(
        ProotPhase.PREPARING, ProotPhase.UPDATING, ProotPhase.STARTING,
    )

    fun save(values: Map<String, ApValue>) {
        saving = true
        error = null
        repository.patchDeploySettings(values) { ok, message ->
            saving = false
            if (ok) {
                values.keys.forEach { draft.remove(it) }
                saved = true
            } else error = message ?: saveFailed
        }
    }
    LaunchedEffect(Unit) {
        val settings = repository.loadDeploySettings()
        error = if (settings == null) loadFailed else null
        loading = false
    }

    val deploy by repository.deploySettings.collectAsStateWithLifecycle()
    // 旧 Runtime 的接口隐藏口令；回读部署文件才能正确回显已保存的值。
    val password by produceState<String?>(null, deploy, saving, prootState.phase) {
        if (!saving) value = prootHost.readWebUiPassword()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().apContentWidth(),
            contentPadding =
                PaddingValues(
                    start = AppTokens.Spacing.lg,
                    end = AppTokens.Spacing.lg,
                    top = AppTokens.Spacing.sm,
                    bottom = if (draft.isNotEmpty()) 96.dp else AppTokens.Spacing.xl,
                ),
        ) {
            if (loading) {
                item(key = "loading") {
                    AppCard(modifier = Modifier) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            ExpressiveLoadingIndicator()
                        }
                    }
                }
                return@LazyColumn
            }
            val data =
                deploy
                    ?: run {
                        item(key = "error") {
                            AppCard {
                                ApErrorState(error ?: stringResource(R.string.ap_waiting_data))
                            }
                        }
                        return@LazyColumn
                    }
            if (data.notice.isNotEmpty()) {
                item(key = "notice") {
                    AppCard {
                        Text(
                            text = data.notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (data.demo) {
                item(key = "demo") {
                    AppCard {
                        Text(
                            text = stringResource(R.string.ap_deploy_demo),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (error != null || saved) {
                item(key = "saveResult") {
                    AppCard {
                        if (error != null) ApErrorState(error!!)
                        if (saved) {
                            Text(stringResource(R.string.ap_field_saved))
                            Button(
                                enabled = !saving && !sessionBusy,
                                onClick = { showRestartConfirm = true },
                            ) { Text(stringResource(R.string.settings_runtime_restart)) }
                        }
                    }
                }
            }
            data.groups.forEach { group ->
                appSettingsGroup(
                    key = group.key,
                    values = group.fields,
                    title = { group.label },
                    itemKey = { it.key },
                    contentType = "deployField",
                ) { field ->
                    DeployFieldRow(
                        field = field,
                        draft = draft[field.key] ?: if (field.key == "Password") password else null,
                        enabled = !data.demo && !saving && !sessionBusy,
                        onDraft = {
                            draft[field.key] = it
                            save(mapOf(field.key to it))
                        },
                    )
                }
            }
        }

        // 有了待保存的改动才滑出来；直接出现会在底部「跳」一下
        AnimatedVisibility(
            visible = draft.isNotEmpty(),
            // 定位与 inset 挂在 AnimatedVisibility 上：它才是 Box 的直接子项，
            // 写在里面的 Surface 上等于让被包裹的那层去对齐外层的 Box，位移会跑偏
            modifier = Modifier.align(Alignment.BottomCenter).imePadding(),
            enter =
                slideInVertically(
                    animationSpec = androidx.compose.animation.core.spring(),
                    initialOffsetY = { it },
                ) + fadeIn(androidx.compose.animation.core.spring()),
            exit =
                slideOutVertically(
                    animationSpec = androidx.compose.animation.core.spring(),
                    targetOffsetY = { it },
                ) + fadeOut(androidx.compose.animation.core.spring()),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(AppTokens.Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.ap_deploy_pending, draft.size),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = !saving,
                        onClick = {
                            save(HashMap(draft))
                        },
                    ) {
                        Text(
                            stringResource(
                                if (saving) R.string.ap_deploy_saving else R.string.ap_deploy_save
                            )
                        )
                    }
                }
            }
        }
    }
    if (showRestartConfirm) {
        com.azurpilot.ghio.ui.ksu.AppDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text(stringResource(R.string.settings_runtime_restart_title)) },
            text = { Text(stringResource(R.string.settings_runtime_restart_message)) },
            confirmButton = {
                com.azurpilot.ghio.ui.ksu.AppTextButton(onClick = {
                    showRestartConfirm = false
                    saved = false
                    prootHost.restart()
                }) { Text(stringResource(R.string.settings_runtime_restart)) }
            },
            dismissButton = {
                com.azurpilot.ghio.ui.ksu.AppTextButton(onClick = { showRestartConfirm = false }) {
                    Text(stringResource(R.string.ap_cancel))
                }
            },
        )
    }
}

/**
 * 单个部署字段行，控件按字段类型挑（开关 / 下拉 / 密码 / 整数 / 文本）
 *
 * One deploy-field row; the control is picked by the field's type (switch / dropdown / password /
 * integer / text).
 *
 * @param field 字段定义 / the field definition
 * @param draft 草稿值；null 表示尚未改动，显示回落到 [AzurPilotDeployField.value] — ApValue 本身就是可空类型，不要再叠一层 `?` /
 *   the draft value; null means unmodified and falls back to [AzurPilotDeployField.value] — ApValue
 *   is already nullable, do not stack another `?` on it
 * @param enabled 演示模式下整体禁用 / false disables the row (demo mode)
 * @param onDraft 写入草稿 / writes into the draft
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeployFieldRow(
    field: AzurPilotDeployField,
    draft: ApValue,
    enabled: Boolean,
    onDraft: (ApValue) -> Unit,
) {
    val current = draft ?: field.value
    when {
        field.type == "bool" ->
            com.azurpilot.ghio.ui.components.AppSwitchPreference(
                title = field.label,
                summary = field.help.takeIf { it.isNotBlank() },
                checked = current as? Boolean ?: false,
                enabled = enabled,
                onCheckedChange = onDraft,
            )

        field.options.isNotEmpty() -> {
            val options =
                if (field.options.any { it.sameValueAs(current) }) field.options
                else listOf(current) + field.options
            com.azurpilot.ghio.ui.ksu.AppDropdownPreference(
                title = field.label,
                summary = field.help.takeIf { it.isNotBlank() },
                items = options.map(::optionText),
                selectedIndex = options.indexOfFirst { it.sameValueAs(current) }.coerceAtLeast(0),
                enabled = enabled,
                onSelectedIndexChange = { onDraft(options[it]) },
            )
        }

        else ->
            com.azurpilot.ghio.ui.components.AppEditablePreference(
                title = field.label,
                value =
                    if (field.type == "int") (current as? Number)?.toLong()?.toString().orEmpty()
                    else current as? String ?: "",
                enabled = enabled,
                summary = if (field.key == "WebuiHost") stringResource(R.string.ap_deploy_host_help)
                    else null,
                // 上游 Password 是 nullable_string，不能只靠 type == password 判断。
                password = field.key == "Password" || field.type == "password",
                keyboardType =
                    if (field.type == "int") KeyboardType.Number else KeyboardType.Text,
                onSave = { text ->
                    if (field.type == "int") {
                        val number = text.toIntOrNull()
                        if (number != null && (field.key != "WebuiPort" || number in 1..65535)) {
                            onDraft(number)
                            true
                        } else false
                    } else if (field.key == "WebuiHost" &&
                        text !in setOf("127.0.0.1", "localhost", "0.0.0.0", "::1", "::")) {
                        false
                    } else {
                        onDraft(text)
                        true
                    }
                },
            )
    }
}

/**
 * 选项的显示文本：空串给占位符，其余按字符串渲染 / The display text of an option: an empty string becomes a placeholder,
 * everything else renders as a string.
 */
private fun optionText(value: ApValue): String =
    when (value) {
        null -> ""
        is String -> value.ifEmpty { "—" }
        else -> value.toString()
    }
