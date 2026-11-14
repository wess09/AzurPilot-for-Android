package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApEmptyState
import com.azurpilot.ghio.ui.azurpilot.apContentWidth
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.ksu.AppFilterChip as FilterChip
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider as HorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextField as OutlinedTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 级别筛选的候选；`Critical` 与 `Error` 合成一档，视觉上都是「要处理的」
 *
 * The level-filter candidates; `Critical` and `Error` merge into one tier — visually both mean
 * "needs attention".
 */
private val LOG_LEVELS = listOf("DEBUG", "INFO", "WARNING", "ERROR")

/**
 * 日志：实时增量来自 `logs` 主题，级别过滤与搜索都在本地做
 *
 * 服务端的环形缓冲只有 400 条，客户端留到 1000 条——重连后 `logs.get` 会把窗口补齐， 所以本地这一份是「服务端窗口 + 本次会话已见」的并集，不会因为网关裁剪而丢已读内容。
 *
 * The Logs section: live increments come from the `logs` topic; level filtering and search happen
 * locally.
 *
 * The server's ring buffer holds only 400 entries while the client keeps up to 1000 — after a
 * reconnect `logs.get` back-fills the window, so the local copy is the union of "the server's
 * window + what this session has seen" and never loses read content to gateway trimming.
 *
 * @param repository 网关仓库 / the gateway repository
 */
@Composable
fun LogsSection(repository: AzurPilotRepository) {
    val logs by repository.logs.collectAsStateWithLifecycle()
    // rememberSaveable：分屏/深色切换会重建 Activity，筛选条件与游标不该丢（运行连续性）
    var level by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var floorId by rememberSaveable { mutableLongStateOf(Long.MIN_VALUE) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            uri ->
            if (uri != null) {
                val payload =
                    logs
                        .filter { it.id > floorId }
                        .filter { level == null || it.level == level }
                        .filter { query.isBlank() || it.text.contains(query, ignoreCase = true) }
                        .joinToString("\n") { it.text }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use {
                                it.write(payload.toByteArray())
                            }
                        }
                    }
                }
            }
        }

    // 跟随滚动时每帧都会重组；过滤 1000 条的开销只在输入真正变化时付一次
    val filtered =
        remember(logs, level, query, floorId) {
            logs.filter { entry ->
                entry.id > floorId &&
                    (level == null || entry.level == level) &&
                    (query.isBlank() || entry.text.contains(query, ignoreCase = true))
            }
        }
    val ordered =
        remember(filtered, ascending) { if (ascending) filtered else filtered.asReversed() }
    // 横幅的判定要用**源顺序**的上下邻居：倒序显示时视觉上的邻居恰好相反
    val bannerIds =
        remember(filtered) {
            filtered
                .mapIndexedNotNull { index, entry ->
                    if (
                        isLogBanner(
                            filtered.getOrNull(index - 1),
                            entry,
                            filtered.getOrNull(index + 1),
                        )
                    ) {
                        entry.id
                    } else {
                        null
                    }
                }
                .toSet()
        }

    Column(modifier = Modifier.fillMaxSize().apContentWidth()) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = AppTokens.Spacing.lg, vertical = AppTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(AppTokens.IconSize.md),
                    )
                },
                placeholderText = stringResource(R.string.ap_logs_search),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        // 五个级别筛选项在窄屏放不下；横滑比换行或缩字号都更稳
                        .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
            ) {
                FilterChip(
                    selected = level == null,
                    onClick = { level = null },
                    label = { Text(stringResource(R.string.ap_logs_level_all)) },
                )
                LOG_LEVELS.forEach { name ->
                    FilterChip(
                        selected = level == name,
                        onClick = { level = if (level == name) null else name },
                        label = { Text(name) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ap_logs_count, filtered.size, logs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 跟随：只看最新时不打断阅读；停跟随是为了翻历史时不被新行顶走
                IconButton(onClick = { follow = !follow }) {
                    Icon(
                        imageVector =
                            if (follow) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                        contentDescription =
                            stringResource(
                                if (follow) R.string.ap_logs_follow_off
                                else R.string.ap_logs_follow_on
                            ),
                        tint =
                            if (follow) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                IconButton(onClick = { ascending = !ascending }) {
                    Icon(
                        imageVector = Icons.Filled.SwapVert,
                        contentDescription =
                            stringResource(
                                if (ascending) R.string.ap_logs_order_desc
                                else R.string.ap_logs_order_asc
                            ),
                    )
                }
                IconButton(
                    // 只清本地视图：服务端的日志文件不动，从这里也删不到它
                    onClick = { floorId = logs.lastOrNull()?.id ?: floorId }
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = stringResource(R.string.ap_logs_clear),
                    )
                }
                IconButton(
                    onClick = { exporter.launch("azurpilot-log-${System.currentTimeMillis()}.txt") }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = stringResource(R.string.ap_logs_export),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // 空态与列表之间交叉淡入；列表状态提到分支外面，换态时不会丢滚动位置。
        // 必须吃 weight：不带它 Crossfade 会按父列的完整高度量列表，视口比剩余空间
        // 高出一截搜索栏，列表末尾几行永远沉在屏幕底下滚不上来
        val listState = rememberLazyListState()
        Crossfade(
            modifier = Modifier.weight(1f),
            targetState = ordered.isEmpty(),
            animationSpec = androidx.compose.animation.core.spring(),
            label = "logsEmpty",
        ) { empty ->
            if (empty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (logs.isEmpty()) {
                        ApEmptyState(
                            icon = Icons.Filled.Terminal,
                            title = stringResource(R.string.ap_logs_empty),
                            hint = stringResource(R.string.ap_logs_empty_hint),
                        )
                    } else {
                        ApEmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.ap_logs_no_matches),
                            hint = stringResource(R.string.ap_logs_no_matches_hint),
                        )
                    }
                }
            } else {
                LaunchedEffect(ordered.size, follow, ascending) {
                    // 只在「跟随 + 正序」时自动滚到底；反序时最新就在顶部，不用动
                    if (follow && ascending && ordered.isNotEmpty()) {
                        listState.scrollToItem(ordered.lastIndex)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            horizontal = AppTokens.Spacing.lg,
                            vertical = AppTokens.Spacing.sm,
                        ),
                ) {
                    items(ordered, key = { it.id }) { entry ->
                        ApLogRow(
                            entry = entry,
                            centered = entry.id in bannerIds,
                            search = query,
                            // 高频日志不执行逐行位移动画，避免插入时列表反复跳动。
                            modifier = Modifier,
                        )
                    }
                }
            }
        }
    }
}
