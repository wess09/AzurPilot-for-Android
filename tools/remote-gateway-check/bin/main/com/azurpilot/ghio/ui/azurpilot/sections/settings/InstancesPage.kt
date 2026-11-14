package com.azurpilot.ghio.ui.azurpilot.sections.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.components.AppActionLabel
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunState
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppDialog as AlertDialog
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Android 只保留默认实例的覆盖导入入口，原多实例管理 UI 不再对用户开放。
 *
 * Imports settings into the one Android default instance without exposing multi-instance controls.
 */
@Composable
fun InstancesPage(repository: AzurPilotRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var candidate by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                candidate = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                            it.readText()
                        }
                    }.getOrNull()
                }
                if (candidate == null) {
                    repository.reportTransient(context.getString(R.string.ap_instances_import_read_failed))
                }
            }
        }
    }

    candidate?.let { content ->
        AlertDialog(
            onDismissRequest = { if (!busy) candidate = null },
            title = { Text(stringResource(R.string.ap_instances_import)) },
            text = { Text(stringResource(R.string.ap_import_overwrite_warning)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        candidate = null
                        scope.launch {
                            // 覆盖之前将当前配置保留在应用私有目录，避免导入失败丢失数据。
                            val backupOk = withContext(Dispatchers.IO) {
                                val original = repository.exportConfig(AzurPilotRunState.DEFAULT_CONFIG)
                                if (original == null) false else runCatching {
                                    val dir = File(context.filesDir, "config-backups").apply { mkdirs() }
                                    val file = File(dir, "ap-before-import-${System.currentTimeMillis()}.json")
                                    file.writeText((JSONObject.wrap(original.values) as JSONObject).toString(2))
                                }.isSuccess
                            }
                            if (!backupOk) {
                                busy = false
                                repository.reportTransient(context.getString(R.string.ap_import_backup_failed))
                            } else {
                                repository.overwriteDefaultConfig(content) { success, error ->
                                    scope.launch {
                                        busy = false
                                        repository.reportTransient(
                                            if (success) context.getString(R.string.ap_import_completed)
                                            else error ?: context.getString(R.string.ap_instances_import_read_failed),
                                        )
                                    }
                                }
                            }
                        }
                    },
                ) {
                    AppActionLabel(AppSymbol.Download, stringResource(R.string.ap_instances_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { candidate = null }, enabled = !busy) {
                    Text(stringResource(R.string.ap_cancel))
                }
            },
        )
    }

    ApSectionColumn {
        com.azurpilot.ghio.ui.ksu.AppSettingsGroup(
            title = stringResource(R.string.ap_instances_import),
        ) {
            item {
                com.azurpilot.ghio.ui.components.AppInfoRow(
                    label = stringResource(R.string.ap_settings_current_instance),
                    value = AzurPilotRunState.DEFAULT_CONFIG,
                )
            }
            item {
                com.azurpilot.ghio.ui.components.AppNavigationRow(
                    label = stringResource(R.string.ap_instances_import),
                    description = stringResource(R.string.ap_import_default_hint),
                    onClick = {
                        if (!busy) importer.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream")
                        )
                    },
                )
            }
        }
    }
}
