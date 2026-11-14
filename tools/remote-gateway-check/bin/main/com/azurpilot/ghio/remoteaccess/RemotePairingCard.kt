package com.azurpilot.ghio.remoteaccess

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.ksu.AppButton
import com.azurpilot.ghio.ui.ksu.AppIcon
import com.azurpilot.ghio.ui.ksu.AppIconButton
import com.azurpilot.ghio.ui.ksu.AppText
import com.azurpilot.ghio.ui.ksu.AppTextField
import com.azurpilot.ghio.ui.ksu.AppTonalButton
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 保存扫码、六码绑定和设备切换状态；相机返回后继续同一流程，不依赖本机 Runtime。
 *
 * Retains scanning, six-digit pairing and device switching across camera results, independently
 * of the local Runtime.
 */
@Composable
fun RemotePairingCard(
    onConnected: () -> Unit = {},
    repository: AzurPilotRepository = koinInject(),
    onLocalSelected: () -> Unit = {},
    settings: AppSettingsManager = koinInject(),
) {
    val context = LocalContext.current
    val store = remember(context) { RemotePairings(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val remoteClientMode by settings.remoteClientMode.collectAsStateWithLifecycle()
    val remoteName by repository.remoteName.collectAsStateWithLifecycle()
    val sourceId by repository.sourceId.collectAsStateWithLifecycle()
    val connected by repository.connected.collectAsStateWithLifecycle()
    var devices by remember { mutableStateOf<List<RemotePairing>>(emptyList()) }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    fun connectionFinished(ok: Boolean) {
        busy = false
        error = if (ok) null else R.string.remote_pair_failed
        if (ok) onConnected()
    }
    fun pair(value: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                val paired = store.pair(value)
                settings.setRemoteClientMode(true)
                devices = store.list()
                code = ""
                paired
            }.onSuccess { repository.connectTarget(it, ::connectionFinished) }
                .onFailure { error = pairingErrorResource(it); busy = false }
        }
    }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK)
            result.data?.getStringExtra(QrScanActivity.EXTRA_PAIRING_SECRET)?.let(::pair)
    }
    LaunchedEffect(store) {
        runCatching { devices = store.list() }.onFailure { error = R.string.remote_pair_failed }
    }
    RemotePairingContent(
        devices = devices,
        code = code,
        busy = busy,
        error = error,
        remoteMode = remoteClientMode || remoteName != null,
        currentId = sourceId,
        currentConnected = connected,
        onCodeChange = { code = it; error = null },
        onScan = {
            error = null
            scanner.launch(Intent(context, QrScanActivity::class.java))
        },
        onSubmit = { pair(code) },
        onLocal = {
            busy = true
            repository.connectTarget(null) { ok ->
                if (ok) scope.launch {
                    settings.setRemoteClientMode(false)
                    busy = false
                    onLocalSelected()
                } else {
                    busy = false
                    error = R.string.remote_pair_failed
                }
            }
        },
        onConnect = { device ->
            busy = true
            error = null
            scope.launch {
                settings.setRemoteClientMode(true)
                repository.connectTarget(device, ::connectionFinished)
            }
        },
        onRemove = { device ->
            scope.launch {
                runCatching { store.remove(device.deviceId); devices = store.list() }
                    .onFailure { error = R.string.remote_pair_failed }
            }
        },
    )
}

/**
 * 扫码为主要操作，六码输入为次要操作，已绑定设备另列；复用主题字阶与 12/20dp 间距。
 * 不展示重复说明，忙碌时禁用重复提交，错误保留在相关操作附近。
 *
 * Prioritizes scanning, offers code entry second, and groups saved devices separately using theme
 * typography and 12/20dp spacing. Avoids duplicate guidance and submissions, with errors near actions.
 */
@Composable
internal fun RemotePairingContent(
    devices: List<RemotePairing> = emptyList(),
    code: String = "",
    busy: Boolean = false,
    error: Int? = null,
    remoteMode: Boolean = false,
    currentId: String? = null,
    currentConnected: Boolean = false,
    onCodeChange: (String) -> Unit = {},
    onScan: () -> Unit = {},
    onSubmit: () -> Unit = {},
    onLocal: () -> Unit = {},
    onConnect: (RemotePairing) -> Unit = {},
    onRemove: (RemotePairing) -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val canSubmit = !busy && code.length == 6
    val submit = { if (canSubmit) { keyboard?.hide(); onSubmit() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xl)) {
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xl)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md)) {
                    Box(Modifier.size(48.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        AppIcon(Icons.Outlined.Devices, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    AppText(stringResource(R.string.remote_pair_title),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                AppText(stringResource(R.string.remote_pair_hint), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppButton(onClick = onScan, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    AppIcon(Icons.Outlined.QrCodeScanner, null, modifier = Modifier.size(AppTokens.IconSize.md))
                    Spacer(Modifier.width(AppTokens.Spacing.sm))
                    AppText(stringResource(R.string.remote_pair_scan))
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md)) {
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    AppText(stringResource(R.string.remote_pair_code_alternative),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md)) {
                    AppTextField(
                        value = code,
                        onValueChange = { onCodeChange(it.filter { digit -> digit in '0'..'9' }.take(6)) },
                        label = { AppText(stringResource(R.string.remote_pair_code)) },
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center, letterSpacing = 6.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        singleLine = true,
                        enabled = !busy,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppTonalButton(onClick = submit, enabled = canSubmit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        AppText(stringResource(if (busy) R.string.remote_pair_busy else R.string.remote_pair_connect))
                    }
                }
                error?.let {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer).padding(AppTokens.Spacing.md)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                        AppIcon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(AppTokens.IconSize.md))
                        AppText(stringResource(it), color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (devices.isNotEmpty()) AppCard(title = stringResource(R.string.remote_pair_saved_devices)) {
            devices.forEachIndexed { index, device ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val selected = currentId == device.deviceId
                val name = device.name.ifBlank { stringResource(R.string.remote_pair_device) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = !busy, role = Role.Button) { onConnect(device) }
                    .heightIn(min = 72.dp).padding(vertical = AppTokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md)) {
                    AppIcon(if (selected && currentConnected) Icons.Outlined.CheckCircle else Icons.Outlined.Devices,
                        null, tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppTokens.IconSize.md))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs)) {
                        AppText(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        AppText(stringResource(when {
                            selected && currentConnected -> R.string.remote_pair_connected
                            selected -> R.string.remote_pair_selected
                            else -> R.string.remote_pair_tap_connect
                        }), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AppIconButton(enabled = !busy && !selected, onClick = { onRemove(device) }) {
                        AppIcon(Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.remote_pair_remove_device, name))
                    }
                }
            }
        }
        if (remoteMode) TextButton(onLocal, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            AppText(stringResource(R.string.remote_pair_local), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 按稳定错误码提供本地化操作提示。 / Provides localized actions for stable registry errors. */
private fun pairingErrorResource(error: Throwable): Int = when ((error as? RemoteRegistryException)?.failure) {
    RemoteRegistryFailure.UNAVAILABLE_CODE -> R.string.remote_pair_code_unavailable
    RemoteRegistryFailure.DEVICE_OFFLINE -> R.string.remote_pair_device_offline
    RemoteRegistryFailure.RATE_LIMITED -> R.string.remote_pair_rate_limited
    RemoteRegistryFailure.API_MISMATCH -> R.string.remote_pair_api_mismatch
    else -> R.string.remote_pair_failed
}
