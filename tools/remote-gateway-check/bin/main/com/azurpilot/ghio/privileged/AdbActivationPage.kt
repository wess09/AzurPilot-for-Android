package com.azurpilot.ghio.privileged

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.AppCardSurface
import com.azurpilot.ghio.ui.components.AppNavigationRow
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.ksu.AppButton
import com.azurpilot.ghio.ui.ksu.AppIcon
import com.azurpilot.ghio.ui.ksu.AppPageScaffold
import com.azurpilot.ghio.ui.ksu.AppSettingsGroup
import com.azurpilot.ghio.ui.ksu.AppText
import com.azurpilot.ghio.ui.ksu.AppTextField
import com.azurpilot.ghio.ui.ksu.AppTonalButton
import com.azurpilot.ghio.ui.ksu.material.SegmentedListItem

/**
 * 显示两种主题的启动状态与渐进式配对操作；主线程持有表单，配对码不参与状态保存。
 *
 * Displays activation and progressive pairing controls in both themes. Forms belong to the main
 * thread; the pairing code is excluded from saved state. Callbacks own network and system actions.
 */
@Composable
internal fun AdbActivationPage(
    status: EmbeddedAdbManager.Status,
    onActivate: (Int?) -> Unit,
    onPair: (Int, String) -> Unit,
    onNotificationPairing: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val busy = status == EmbeddedAdbManager.Status.Working
    val ready = status == EmbeddedAdbManager.Status.Ready
    var pairingExpanded by rememberSaveable { mutableStateOf(!ready) }
    var manualPairingExpanded by rememberSaveable { mutableStateOf(false) }
    var connectionExpanded by rememberSaveable { mutableStateOf(false) }
    var pairingPort by rememberSaveable { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    LaunchedEffect(ready) {
        if (ready) {
            pairingExpanded = false
            manualPairingExpanded = false
            connectionExpanded = false
            code = ""
            focus.clearFocus()
        }
    }

    AppPageScaffold(title = stringResource(R.string.adb_title), onBack = onBack) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ActivationStatusCard(status, onActivate = { onActivate(null) }, onBack = onBack)

                if (Build.VERSION.SDK_INT >= 30) {
                    AppCardSurface(Modifier.fillMaxWidth()) {
                        Column {
                            ActivationDisclosure(
                                title = stringResource(R.string.adb_pairing_title),
                                summary = stringResource(
                                    if (ready) R.string.adb_pairing_ready_summary
                                    else R.string.adb_pairing_summary,
                                ),
                                icon = Icons.Outlined.Link,
                                expanded = pairingExpanded,
                                onClick = { pairingExpanded = !pairingExpanded },
                            )
                            if (pairingExpanded) {
                                Column(
                                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        PairingStep(1, stringResource(R.string.adb_pair_step_settings))
                                        PairingStep(2, stringResource(R.string.adb_pair_step_dialog))
                                        PairingStep(3, stringResource(R.string.adb_pair_step_notification))
                                    }
                                    AppTonalButton(
                                        onClick = onNotificationPairing,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    ) { AppText(stringResource(R.string.adb_pair_notification)) }
                                    ActivationDisclosure(
                                        title = stringResource(R.string.adb_manual_pair_title),
                                        summary = null,
                                        expanded = manualPairingExpanded,
                                        onClick = {
                                            manualPairingExpanded = !manualPairingExpanded
                                            // 收起表单时清除一次性配对码，避免再次展开误用过期值。
                                            code = ""
                                            focus.clearFocus()
                                        },
                                        modifier = Modifier,
                                        compact = true,
                                    )
                                    if (manualPairingExpanded) {
                                        AppText(
                                            stringResource(R.string.adb_manual_pair_guide),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        AppTextField(
                                            value = pairingPort,
                                            onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                                            labelText = stringResource(R.string.adb_pair_port),
                                            modifier = Modifier.fillMaxWidth(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            enabled = !busy,
                                        )
                                        AppTextField(
                                            value = code,
                                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                                            labelText = stringResource(R.string.adb_pair_code),
                                            modifier = Modifier.fillMaxWidth(),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                            visualTransformation = PasswordVisualTransformation(),
                                            singleLine = true,
                                            enabled = !busy,
                                        )
                                        AppTonalButton(
                                            onClick = {
                                                val pairingCode = code
                                                code = ""
                                                focus.clearFocus()
                                                onPair(pairingPort.toInt(), pairingCode)
                                            },
                                            enabled = !busy && pairingPort.toIntOrNull() in 1..65535
                                                && code.length == 6,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        ) { AppText(stringResource(R.string.adb_pair_start)) }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    AppCard {
                        AppText(
                            stringResource(R.string.adb_legacy_guide),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                AppSettingsGroup {
                    item {
                        AppNavigationRow(
                            label = stringResource(R.string.adb_open_settings),
                            onClick = onOpenSettings,
                        )
                    }
                    item(visible = ready) {
                        AppNavigationRow(
                            label = stringResource(R.string.adb_reconnect),
                            description = stringResource(R.string.adb_reconnect_summary),
                            onClick = { onActivate(null) },
                        )
                    }
                }

                AppCardSurface(Modifier.fillMaxWidth()) {
                    Column {
                        ActivationDisclosure(
                            title = stringResource(R.string.adb_manual_connect_title),
                            summary = stringResource(R.string.adb_manual_connect_summary),
                            icon = Icons.Outlined.SettingsEthernet,
                            expanded = connectionExpanded,
                            onClick = {
                                connectionExpanded = !connectionExpanded
                                focus.clearFocus()
                            },
                        )
                        if (connectionExpanded) {
                            Column(
                                Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                AppText(
                                    stringResource(R.string.adb_manual_connect_guide),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                AppTextField(
                                    value = connectPort,
                                    onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                                    labelText = stringResource(R.string.adb_connect_port),
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    enabled = !busy,
                                )
                                AppTonalButton(
                                    onClick = {
                                        focus.clearFocus()
                                        onActivate(connectPort.toInt())
                                    },
                                    enabled = !busy && connectPort.toIntOrNull() in 1..65535,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { AppText(stringResource(R.string.adb_connect)) }
                            }
                        }
                    }
                }

                AppText(
                    stringResource(R.string.adb_network_note),
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 显示连接结果和唯一主操作；状态变化通过无障碍服务温和播报。
 *
 * Displays connection status and one primary action, announcing changes politely to accessibility.
 */
@Composable
private fun ActivationStatusCard(
    status: EmbeddedAdbManager.Status,
    onActivate: () -> Unit,
    onBack: () -> Unit,
) {
    val ready = status == EmbeddedAdbManager.Status.Ready
    val busy = status == EmbeddedAdbManager.Status.Working
    val failed = status == EmbeddedAdbManager.Status.Failed
    val colors = MaterialTheme.colorScheme
    AppCard(contentPadding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(52.dp).background(
                        if (failed) colors.errorContainer else colors.primaryContainer,
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) ExpressiveLoadingIndicator(Modifier.size(28.dp))
                    else AppIcon(
                        when {
                            ready -> Icons.Outlined.CheckCircle
                            failed -> Icons.Outlined.ErrorOutline
                            else -> Icons.Outlined.PowerSettingsNew
                        },
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (failed) colors.onErrorContainer else colors.onPrimaryContainer,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        stringResource(when (status) {
                            EmbeddedAdbManager.Status.Idle -> R.string.adb_status_idle
                            EmbeddedAdbManager.Status.Working -> R.string.adb_status_working
                            EmbeddedAdbManager.Status.Ready -> R.string.adb_status_ready
                            EmbeddedAdbManager.Status.Failed -> R.string.adb_status_failed
                        }),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    AppText(
                        stringResource(R.string.adb_status_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            AppText(
                stringResource(when (status) {
                    EmbeddedAdbManager.Status.Idle -> R.string.adb_idle
                    EmbeddedAdbManager.Status.Working -> R.string.adb_working
                    EmbeddedAdbManager.Status.Ready -> R.string.adb_ready
                    EmbeddedAdbManager.Status.Failed -> R.string.adb_failed
                }),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            AppButton(
                onClick = if (ready) onBack else onActivate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                AppText(stringResource(when {
                    ready -> R.string.adb_return
                    busy -> R.string.adb_status_working
                    else -> R.string.adb_start
                }))
            }
        }
    }
}

/**
 * 使用主题原生列表行展开辅助内容，公开收起状态供无障碍服务读取。
 *
 * Uses native themed list rows to disclose auxiliary content with an accessible expansion state.
 */
@Composable
private fun ActivationDisclosure(
    title: String,
    summary: String?,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    val state = stringResource(if (expanded) R.string.adb_expanded else R.string.adb_collapsed)
    val rowModifier = modifier.semantics { stateDescription = state }
    val trailing: @Composable () -> Unit = {
        AppIcon(
            if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.basic.BasicComponent(
            title = title,
            summary = summary,
            startAction = icon?.let { { AppIcon(it, null, tint = MaterialTheme.colorScheme.primary) } },
            endActions = { trailing() },
            onClick = onClick,
            role = Role.Button,
            modifier = rowModifier,
            insideMargin = PaddingValues(horizontal = if (compact) 0.dp else 20.dp, vertical = 16.dp),
        )
    } else {
        SegmentedListItem(
            modifier = rowModifier,
            onClick = onClick,
            headlineContent = { AppText(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = summary?.let { { AppText(it, style = MaterialTheme.typography.bodyMedium) } },
            leadingContent = icon?.let { { AppIcon(it, null, tint = MaterialTheme.colorScheme.primary) } },
            trailingContent = trailing,
        )
    }
}

/**
 * 用紧凑编号拆开首次配对步骤，长文本自然换行。
 *
 * Separates first-time pairing steps with compact numbers and naturally wrapping text.
 */
@Composable
private fun PairingStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppText(
                number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        AppText(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
