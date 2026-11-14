package com.azurpilot.ghio.ui.azurpilot.sections.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotCat
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApEmptyState
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.azurpilot.ApStatusPill
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.ksu.AppDialog as AlertDialog
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import kotlinx.coroutines.delay

/**
 * 指挥喵评分
 *
 * 报告是**机器级**的（`log/meowfficer_score.json`），跟哪个实例无关，所以 `instance` 参数 只用于校验。页面可见期间每 5
 * 秒刷一次：评分任务跑起来之后，结果会自己出现。
 *
 * The Meowfficer (commander cat) scoring page.
 *
 * The report is **machine-level** (`log/meowfficer_score.json`), independent of any instance, so
 * the `instance` parameter is only for validation. It refreshes every 5 seconds while the page is
 * visible: once the scoring task runs, the result shows up on its own.
 *
 * @param repository 网关仓库 / the gateway repository
 */
@Composable
fun MeowfficerPage(repository: AzurPilotRepository) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var cats by remember { mutableStateOf<List<AzurPilotCat>>(emptyList()) }
    var generatedAt by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        while (true) {
            repository
                .loadMeowfficerReport()
                .onSuccess {
                    cats = it.cats
                    generatedAt = it.generatedAt
                    error = null
                }
                .onFailure {
                    // 还没跑过评分任务时这是常态，不当成错误页
                    cats = emptyList()
                    error = it.message
                }
            loading = false
            delay(POLL_MS)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ap_meowfficer_clear_title)) },
            text = { Text(stringResource(R.string.ap_meowfficer_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.clearMeowfficerReport()
                        cats = emptyList()
                        confirmClear = false
                    }
                ) {
                    Text(stringResource(R.string.ap_meowfficer_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.ap_cancel))
                }
            },
        )
    }

    ApSectionColumn {
        AppCard(
            title = stringResource(R.string.ap_title_meowfficer),
            modifier = Modifier,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                Text(
                    text =
                        if (generatedAt.isNotEmpty()) {
                            stringResource(R.string.ap_meowfficer_generated, generatedAt)
                        } else {
                            stringResource(R.string.ap_meowfficer_not_generated)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { runCatching { uriHandler.openUri(MEOWFFICER_REPORT_URL) } }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.ap_open_in_browser),
                    )
                }
                IconButton(onClick = { confirmClear = true }) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = stringResource(R.string.ap_meowfficer_clear),
                    )
                }
            }
        }

        when {
            loading && cats.isEmpty() ->
                AppCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ExpressiveLoadingIndicator()
                    }
                }

            cats.isEmpty() ->
                AppCard {
                    ApEmptyState(
                        icon = Icons.Filled.Pets,
                        title = stringResource(R.string.ap_meowfficer_not_generated),
                        hint = error ?: stringResource(R.string.ap_meowfficer_hint),
                    )
                }

            else -> cats.forEachIndexed { index, cat -> CatCard(cat, Modifier) }
        }
    }
}

/**
 * 一只指挥喵的评分卡：等级/品阶/结论徽章 + 结论与养成建议
 *
 * One commander cat's score card: level/tier/verdict badges plus the verdict and training advice.
 *
 * @param cat 评分数据 / the scoring data
 * @param modifier 应用于卡片的修饰符 / the modifier applied to the card
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatCard(cat: AzurPilotCat, modifier: Modifier = Modifier) {
    AppCard(title = cat.cat, modifier = modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
        ) {
            cat.level?.let {
                ApStatusPill(
                    text = stringResource(R.string.ap_meowfficer_level, it),
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            cat.tier?.let {
                ApStatusPill(
                    text = it,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            cat.verdict?.let {
                ApStatusPill(
                    text = it,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        cat.headline?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        cat.adviceReason?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        cat.costText?.let { ApMeowRow(stringResource(R.string.ap_meowfficer_cost), it) }
        cat.pointsSpent?.let {
            ApMeowRow(stringResource(R.string.ap_meowfficer_points), it.toString())
        }
        if (cat.targets.isNotEmpty()) {
            ApMeowRow(
                stringResource(R.string.ap_meowfficer_targets),
                cat.targets.joinToString("、"),
            )
        }
        if (cat.talents.isNotEmpty()) {
            // 拼接要在 composable 之外做：joinToString 的 lambda 不是 composable 上下文
            val inferredMark = stringResource(R.string.ap_meowfficer_inferred)
            val talents =
                cat.talents.joinToString("、") { talent ->
                    val level = talent.level?.let { "Lv$it" }.orEmpty()
                    val inferred = if (talent.inferred) inferredMark else ""
                    "${talent.name}$level$inferred"
                }
            Text(
                text = talents,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        cat.tags.forEach { tag ->
            Text(
                text = "#$tag",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「标签: 值」行，固定 4:6 分栏 / A "label: value" row with a fixed 4:6 split. */
@Composable
private fun ApMeowRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/**
 * 评分报告是网关提供的独立 HTML，走设备本机浏览器看（不是 WebUI）
 *
 * The score report is a standalone HTML served by the gateway, viewed in the device's local browser
 * (not the WebUI).
 */
private val MEOWFFICER_REPORT_URL: String
    get() = "${com.azurpilot.ghio.proot.RuntimeConnection.endpoint.httpBase}/reports/meowfficer_score"

/** 页面可见期间的轮询间隔（毫秒） / The polling interval while the page is visible, in milliseconds. */
private const val POLL_MS = 5_000L
