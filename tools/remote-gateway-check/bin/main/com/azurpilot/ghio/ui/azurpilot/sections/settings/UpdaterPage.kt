package com.azurpilot.ghio.ui.azurpilot.sections.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.components.AppActionLabel
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.proot.AzurPilotCommit
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApKeyValueRow
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.azurpilot.ApStatusPill
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.components.AnimatedTransferIcon
import com.azurpilot.ghio.ui.components.TransferStage
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider as HorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppOutlinedButton as OutlinedButton
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton

/** 提交列表的分页大小 / The commit-list page size. */
private const val COMMIT_PAGE = 50

/**
 * 运行时更新
 *
 * Android 版的运行时**由宿主整包更新**（`managedByAndroid`），网关侧的 git 热更是有意关闭的，
 * 所以这里在那种情况下要把动作藏起来并说清原因——留一个按不动的按钮比没有按钮更糟。
 *
 * The runtime-updater page.
 *
 * On the Android build the runtime is **updated wholesale by the host** (`managedByAndroid`) and
 * the gateway-side git hot-update is deliberately disabled, so in that case the actions are hidden
 * with the reason spelled out — a button that does nothing is worse than no button.
 *
 * @param repository 网关仓库 / the gateway repository
 */
@Composable
fun UpdaterPage(repository: AzurPilotRepository) {
    val updater by repository.updater.collectAsStateWithLifecycle()
    var commits by remember { mutableStateOf<List<AzurPilotCommit>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var offset by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { repository.startUpdaterPolling() }
    DisposableEffect(Unit) {
        onDispose { repository.stopUpdaterPolling() }
    }

    LaunchedEffect(offset, updater?.localHead) {
        repository.updaterCommits(offset, COMMIT_PAGE)?.let {
            commits = it.entries
            total = it.total
        }
    }

    ApSectionColumn {
        AppCard(
            title = stringResource(R.string.ap_updater_status),
            modifier = Modifier,
        ) {
            val data = updater
            if (data == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    ExpressiveLoadingIndicator()
                }
                return@AppCard
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                ApStatusPill(
                    text = data.state,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (data.busy) {
                    AnimatedTransferIcon(
                        stage = TransferStage.APPLY,
                        modifier = Modifier.size(AppTokens.IconSize.md),
                    )
                }
            }
            ApKeyValueRow(
                stringResource(R.string.ap_updater_local),
                data.localHead?.take(12) ?: "—",
            )
            ApKeyValueRow(
                stringResource(R.string.ap_updater_upstream),
                data.upstreamHead?.take(12) ?: "—",
            )
            ApKeyValueRow(stringResource(R.string.ap_updater_branch), data.branch)
            ApKeyValueRow(
                label = stringResource(R.string.ap_updater_divergence),
                value = stringResource(R.string.ap_updater_ahead_behind, data.ahead, data.behind),
            )
            if (data.error.isNotEmpty()) {
                Text(
                    text = data.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (data.managedByAndroid) {
                Text(
                    text = stringResource(R.string.ap_updater_managed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                ) {
                    OutlinedButton(
                        enabled = !data.busy,
                        onClick = { repository.updaterAction("fetch") },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(AppTokens.IconSize.sm),
                        )
                        Text(
                            text = stringResource(R.string.ap_updater_fetch),
                            modifier = Modifier.padding(start = AppTokens.Spacing.xs),
                        )
                    }
                    Button(
                        enabled = data.canApply,
                        onClick = { repository.updaterAction("apply") },
                    ) {
                        AppActionLabel(AppSymbol.Download, stringResource(R.string.ap_updater_apply))
                    }
                    if (data.canCancel) {
                        TextButton(onClick = { repository.updaterAction("cancel") }) {
                            Text(stringResource(R.string.ap_updater_cancel))
                        }
                    }
                }
            }
        }

        if (commits.isNotEmpty()) {
            AppCard(
                title = stringResource(R.string.ap_updater_commits, total),
                modifier = Modifier,
            ) {
                commits.forEachIndexed { index, commit ->
                    if (index > 0)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CommitRow(commit)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                ) {
                    TextButton(
                        enabled = offset > 0,
                        onClick = { offset = (offset - COMMIT_PAGE).coerceAtLeast(0) },
                    ) {
                        Text(stringResource(R.string.ap_page_prev))
                    }
                    TextButton(
                        enabled = offset + COMMIT_PAGE < total,
                        onClick = { offset += COMMIT_PAGE },
                    ) {
                        Text(stringResource(R.string.ap_page_next))
                    }
                }
            }
        }
    }
}

/** 单条提交：短 SHA + 首行信息 + 作者与日期 / One commit: short SHA + first message line + author and date. */
@Composable
private fun CommitRow(commit: AzurPilotCommit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = commit.sha.take(7),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = commit.message.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "${commit.author} · ${commit.date}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
