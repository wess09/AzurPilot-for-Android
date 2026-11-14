package com.azurpilot.ghio.ui.oobe

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Engineering
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.privileged.AdbActivationActivity
import com.azurpilot.ghio.privileged.RemoteAccessState
import com.azurpilot.ghio.privileged.PermissionManager
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppText as Text
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import timber.log.Timber

/**
 * 呈现 OOBE 的特权运行环境与 ADB 权限配置
 *
 * 引导用户选择提权后端（Shizuku 或 Root），探测当前授权状态，
 * 支持一键授权或跳转至内置无线调试配对页面。
 *
 * Presents OOBE Privileged environment and ADB permission setup.
 *
 * Guides user to select a privilege backend (Shizuku or Root), probes
 * authorization status, and supports one-tap grant or navigating to
 * embedded wireless debugging activation.
 */
@Composable
fun OobeStepPrivileged(
    modifier: Modifier = Modifier,
    manager: AppSettingsManager = koinInject(),
    permissionManager: PermissionManager = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessState by permissionManager.state.collectAsStateWithLifecycle()
    val isGranting by permissionManager.isGranting.collectAsStateWithLifecycle()

    val currentBackend = accessState.configuredBackend
    var grantFailed by rememberSaveable(currentBackend) { mutableStateOf(false) }
    val requestAccess: () -> Unit = {
        scope.launch {
            grantFailed = false
            grantFailed = !runCatching { permissionManager.requestRemoteAccess() }
                .onFailure { Timber.w(it, "OOBE privilege request failed") }
                .getOrDefault(false)
        }
    }

    OobePrivilegedContent(
        accessState = accessState,
        isGranting = isGranting,
        grantFailed = grantFailed,
        onSelectBackend = { backend -> scope.launch { manager.setStartupBackend(backend) } },
        onRequestAccess = requestAccess,
        onOpenActivation = {
            context.startActivity(Intent(context, AdbActivationActivity::class.java))
        },
        modifier = modifier,
    )
}

/**
 * 在主线程呈现权限快照；所有授权与跳转交由宿主执行。
 *
 * Presents the permission snapshot on the main thread, delegating grants and navigation
 * to the host.
 */
@Composable
internal fun OobePrivilegedContent(
    accessState: RemoteAccessState,
    isGranting: Boolean,
    grantFailed: Boolean,
    onSelectBackend: (RemoteBackend) -> Unit,
    onRequestAccess: () -> Unit,
    onOpenActivation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentBackend = accessState.configuredBackend
    val isGranted = accessState.isGranted(currentBackend)
    val backendOptions = listOf(
        RemoteBackend.SHIZUKU to stringResource(R.string.oobe_priv_mode_shizuku),
        RemoteBackend.ROOT to stringResource(R.string.oobe_priv_mode_root),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.lg),
    ) {
        OobePageHeader(
            title = stringResource(R.string.oobe_priv_title),
            subtitle = stringResource(R.string.oobe_priv_subtitle),
            icon = Icons.Outlined.Key,
            glyph = OobeGlyph.PRIVILEGE,
        )

        OobeCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppTokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
            ) {
                OobeChoiceGroup(
                    title = stringResource(R.string.oobe_priv_mode_title),
                    options = backendOptions,
                    selected = currentBackend,
                    enabled = !isGranting,
                    onSelect = { backend ->
                        if (backend != currentBackend) {
                            onSelectBackend(backend)
                        }
                    },
                )
            }
        }

        OobeCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppTokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusIcon = if (isGranted) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber
                    val statusColor = if (isGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    val statusText = stringResource(accessState.oobeStatusRes())

                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(AppTokens.IconSize.md),
                    )
                    Text(
                        text = statusText,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleSmall,
                        color = statusColor,
                    )
                }

                if (!isGranted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
                    ) {
                        if (currentBackend == RemoteBackend.SHIZUKU) {
                            if (accessState.shizukuAvailable) {
                                Button(
                                    onClick = onRequestAccess,
                                    enabled = !isGranting,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Key,
                                        contentDescription = null,
                                        modifier = Modifier.size(AppTokens.IconSize.sm),
                                    )
                                    Spacer(Modifier.width(AppTokens.Spacing.sm))
                                    Text(stringResource(R.string.oobe_priv_action_auth))
                                }
                            } else {
                                Button(
                                    onClick = onOpenActivation,
                                    enabled = !isGranting,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.SettingsEthernet,
                                        contentDescription = null,
                                        modifier = Modifier.size(AppTokens.IconSize.sm),
                                    )
                                    Spacer(Modifier.width(AppTokens.Spacing.sm))
                                    Text(stringResource(R.string.oobe_priv_action_pair))
                                }
                            }
                        } else {
                            Button(
                                onClick = onRequestAccess,
                                enabled = !isGranting,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Engineering,
                                    contentDescription = null,
                                    modifier = Modifier.size(AppTokens.IconSize.sm),
                                )
                                Spacer(Modifier.width(AppTokens.Spacing.sm))
                                Text(stringResource(R.string.oobe_priv_action_root))
                            }
                        }
                    }

                    if (isGranting || grantFailed) {
                        Text(
                            text = stringResource(if (isGranting) R.string.oobe_priv_requesting
                                else R.string.oobe_priv_grant_failed),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (grantFailed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = stringResource(R.string.oobe_priv_skip_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        OobeCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppTokens.Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AppTokens.IconSize.md),
                )
                Text(
                    text = stringResource(R.string.oobe_priv_delegate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
