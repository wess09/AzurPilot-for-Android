package com.azurpilot.ghio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.ksu.AppDialog
import com.azurpilot.ghio.ui.ksu.AppSwitch
import com.azurpilot.ghio.ui.ksu.AppText
import com.azurpilot.ghio.ui.ksu.AppTextButton
import com.azurpilot.ghio.ui.ksu.AppTextField
import com.azurpilot.ghio.update.MirrorChyan
import com.azurpilot.ghio.update.MirrorChyanNotice
import java.io.IOException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 设置与首次部署共用的加速入口。已保存的 CDK 不会重新填入输入框。
 *
 * Shared acceleration entry for settings and first-time setup. Stored CDKs are never prefilled.
 */
@Composable
fun MirrorChyanSettingsButton() {
    var open by remember { mutableStateOf(false) }
    AppTextButton(onClick = { open = true }) {
        AppText(stringResource(R.string.mirrorchyan_title))
    }
    if (open) MirrorChyanDialog(onDismiss = { open = false })
}

@Composable
private fun MirrorChyanDialog(onDismiss: () -> Unit, mirror: MirrorChyan = koinInject()) {
    val settings by mirror.settings.collectAsStateWithLifecycle()
    val notice by mirror.notice.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    // 不使用 rememberSaveable，避免明文密钥进入 Activity 状态。
    var draft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    fun save(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        saveFailed = false
        scope.launch {
            try {
                action()
            } catch (_: IOException) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }

    AppDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { AppText(stringResource(R.string.mirrorchyan_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                AppText(stringResource(R.string.mirrorchyan_description))
                AppLabeledControlRow(label = stringResource(R.string.mirrorchyan_enabled)) {
                    AppSwitch(settings.enabled, { value -> save { mirror.setEnabled(value) } }, enabled = !saving)
                }
                AppTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    labelText = stringResource(R.string.mirrorchyan_cdk),
                    placeholderText = if (settings.hasCdk) "••••••••" else "",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                val statusId = if (saveFailed) R.string.mirrorchyan_save_failed else notice?.messageId()
                if (statusId != null) {
                    AppText(
                        stringResource(statusId),
                        color = if (saveFailed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (settings.hasCdk) {
                    AppTextButton(enabled = !saving, onClick = {
                        save {
                            mirror.saveCdk("")
                            draft = ""
                        }
                    }) {
                        AppText(stringResource(R.string.mirrorchyan_remove))
                    }
                }
                AppTextButton(onClick = {
                    uriHandler.openUri("https://mirrorchyan.com?source=azurpilot_android_settings")
                }) {
                    AppText(stringResource(R.string.mirrorchyan_get_cdk))
                }
            }
        },
        confirmButton = {
            AppTextButton(
                enabled = !saving && draft.isNotBlank(),
                onClick = {
                    save {
                        mirror.saveCdk(draft)
                        draft = ""
                    }
                },
            ) {
                AppText(stringResource(R.string.settings_mirror_custom_save))
            }
        },
        dismissButton = {
            AppTextButton(enabled = !saving, onClick = onDismiss) {
                AppText(stringResource(R.string.common_close))
            }
        },
    )
}

private fun MirrorChyanNotice.messageId(): Int = when (this) {
    MirrorChyanNotice.USED -> R.string.mirrorchyan_used
    MirrorChyanNotice.NOT_SYNCED -> R.string.mirrorchyan_not_synced
    MirrorChyanNotice.UNAVAILABLE -> R.string.mirrorchyan_unavailable
    MirrorChyanNotice.FAILED -> R.string.mirrorchyan_failed
    MirrorChyanNotice.EXPIRED -> R.string.mirrorchyan_expired
    MirrorChyanNotice.INVALID -> R.string.mirrorchyan_invalid
    MirrorChyanNotice.QUOTA -> R.string.mirrorchyan_quota
    MirrorChyanNotice.WRONG_RESOURCE -> R.string.mirrorchyan_wrong_resource
    MirrorChyanNotice.BLOCKED -> R.string.mirrorchyan_blocked
    MirrorChyanNotice.TEST_KEY -> R.string.mirrorchyan_test_key
}
