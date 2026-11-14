package com.azurpilot.ghio.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.components.AppActionLabel
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.provision.ProvisionState
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.MirrorSourcePicker
import com.azurpilot.ghio.ui.components.AnimatedTransferIcon
import com.azurpilot.ghio.ui.components.TransferStage
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppLinearProgressIndicator as LinearProgressIndicator
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 渲染本机 rootfs 部署页；远程入口始终可用，无需等待部署。
 *
 * 本机下载由主页主动发起，支持换源、重试及返回主页，远程连接适用于所有构建。
 *
 * Renders local rootfs provisioning with an independent remote entry available immediately.
 *
 * Home starts local downloads explicitly, with mirrors, retry and return-home actions in all builds.
 *
 * @param state 部署进度；[ProvisionState.Ready] 时本页不再渲染 / provisioning progress; nothing renders once
 *   [ProvisionState.Ready]
 * @param onSkip 返回主页回调；null 时隐藏。 / Return-home callback; hidden when null.
 * @param mirrorId 当前下载源 / current download source
 * @param onMirrorChange 换源回调，切源即重下 / source-change callback; switching restarts the download
 */
@Composable
fun ProvisionScreen(
    state: ProvisionState,
    onRetry: () -> Unit,
    onSkip: (() -> Unit)?,
    mirrorId: String,
    onMirrorChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenRemote: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(AppTokens.Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val transferStage = when (state) {
                is ProvisionState.Downloading -> TransferStage.DOWNLOAD
                is ProvisionState.Extracting -> TransferStage.EXTRACT
                ProvisionState.Checking -> TransferStage.VERIFY
                else -> null
            }
            if (transferStage != null) {
                AnimatedTransferIcon(transferStage)
                Spacer(Modifier.height(AppTokens.Spacing.lg))
            }
            Text(
                text = stringResource(R.string.provision_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(AppTokens.Spacing.md))
            Text(
                text = stringResource(R.string.provision_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppTokens.Spacing.xl))

            if (onOpenRemote != null) {
                Button(onClick = onOpenRemote) { Text(stringResource(R.string.remote_pair_title)) }
                Text(stringResource(R.string.remote_client_hint), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(AppTokens.Spacing.lg))
            }

            when (state) {
                ProvisionState.Checking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    StatusLine(stringResource(R.string.provision_checking))
                }

                is ProvisionState.Extracting -> {
                    val progress =
                        if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes
                        else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StatusLine(
                        stringResource(
                            R.string.provision_extracting,
                            (progress * 100).toInt(),
                            state.doneBytes / 1_000_000,
                            state.totalBytes / 1_000_000,
                        )
                    )
                }

                is ProvisionState.Downloading -> {
                    val progress =
                        if (state.totalBytes > 0) state.doneBytes.toFloat() / state.totalBytes
                        else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StatusLine(
                        stringResource(R.string.provision_downloading, (progress * 100).toInt())
                    )
                }

                is ProvisionState.LowDisk -> {
                    StatusLine(
                        stringResource(
                            R.string.provision_low_disk,
                            state.freeBytes / 1_000_000,
                        ),
                        isError = true,
                    )
                    RetryButton(onRetry)
                }

                ProvisionState.NotBundled -> {
                    StatusLine(
                        stringResource(R.string.provision_not_bundled),
                        isError = true,
                    )
                    RetryButton(onRetry)
                }

                is ProvisionState.Failed -> {
                    StatusLine(
                        stringResource(R.string.provision_failed, state.reason),
                        isError = true,
                    )
                    RetryButton(onRetry)
                }

                ProvisionState.NotInstalled, ProvisionState.Ready -> Unit
            }

            if (onSkip != null) {
                Spacer(Modifier.height(AppTokens.Spacing.sm))
                Button(onClick = onSkip) {
                    AppActionLabel(AppSymbol.Home, stringResource(R.string.provision_back_home))
                }
            }

            // 磁盘不足与下载源无关，别在那里堆选项
            if (state !is ProvisionState.LowDisk) {
                Spacer(Modifier.height(AppTokens.Spacing.xl))
                Text(
                    text = stringResource(R.string.provision_source),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(AppTokens.Spacing.sm))
                MirrorSourcePicker(
                    selected = mirrorId,
                    onSelect = onMirrorChange,
                    arrangement = Alignment.CenterHorizontally,
                )
                Spacer(Modifier.height(AppTokens.Spacing.sm))
                Text(
                    text = stringResource(R.string.provision_source_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, isError: Boolean = false) {
    Spacer(Modifier.height(AppTokens.Spacing.md))
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RetryButton(onRetry: () -> Unit) {
    Spacer(Modifier.height(AppTokens.Spacing.lg))
    Button(onClick = onRetry) {
        AppActionLabel(AppSymbol.Refresh, stringResource(R.string.provision_retry))
    }
}
