package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotStatCategory
import com.azurpilot.ghio.proot.AzurPilotStatSeries
import com.azurpilot.ghio.proot.AzurPilotStatTable
import com.azurpilot.ghio.proot.AzurPilotStatisticsReport
import com.azurpilot.ghio.proot.AzurPilotStatsQuery
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApEmptyState
import com.azurpilot.ghio.ui.azurpilot.ApErrorState
import com.azurpilot.ghio.ui.azurpilot.ApMetricCard
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.azurpilot.prettyCell
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.ksu.AppFilterChip as FilterChip
import com.azurpilot.ghio.ui.ksu.AppHorizontalDivider as HorizontalDivider
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppText as Text

/** 「近 N 天」的候选档位 / The choices offered for the "last N days" filter. */
private val DAY_CHOICES = listOf(1, 7, 30, 90, 365)

/** 委托 / 舰船分类的聚合粒度候选 / The aggregation-period candidates for the commission / ships categories. */
private val PERIOD_CHOICES = listOf("day", "week", "month")

/**
 * 统计：7 个分类，口径与 WebUI 一致（同一套 `statistics.report` 参数）
 *
 * 图表用 Canvas 自绘：项目里没有图表库，而这里只需要折线——为一条折线引入 ECharts 级别的 依赖不划算。表格给横向滚动，列多的时候不挤成一团。
 *
 * The Statistics section: 7 categories with the same accounting as the WebUI (the same
 * `statistics.report` parameters).
 *
 * Charts are hand-drawn on a Canvas: the project has no chart library and only needs line charts —
 * pulling in an ECharts-scale dependency for one polyline is not worth it. Tables scroll
 * horizontally so many columns never squeeze into an unreadable crush.
 *
 * @param repository 网关仓库 / the gateway repository
 */
@Composable
fun StatisticsSection(repository: AzurPilotRepository) {
    // rememberSaveable：重建后停留在同一分类与口径（运行连续性）
    var category by rememberSaveable { mutableStateOf(AzurPilotStatCategory.Resources) }
    var days by rememberSaveable { mutableStateOf(7) }
    var period by rememberSaveable { mutableStateOf("month") }
    var scope by rememberSaveable { mutableStateOf("series") }
    var series by rememberSaveable { mutableStateOf(0) }
    var task by rememberSaveable { mutableStateOf<String?>(null) }

    var report by remember { mutableStateOf<AzurPilotStatisticsReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val selected by repository.selectedInstance.collectAsStateWithLifecycle()
    val tick by repository.statisticsTick.collectAsStateWithLifecycle()

    val query =
        remember(category, days, period, scope, series, task) {
            AzurPilotStatsQuery(
                category = category,
                days = days,
                period = period,
                scope = scope,
                series = series,
                task = task,
            )
        }

    LaunchedEffect(query, selected, tick) {
        if (selected == null) {
            report = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        repository
            .statisticsReport(query)
            .onSuccess { report = it }
            .onFailure {
                report = null
                error = it.message
            }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        com.azurpilot.ghio.ui.ksu.AppSettingsGroup(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                com.azurpilot.ghio.ui.ksu.AppDropdownPreference(
                    title = stringResource(R.string.ap_section_stats),
                    items = AzurPilotStatCategory.entries.map { stringResource(statCategoryLabel(it)) },
                    selectedIndex = category.ordinal,
                    onSelectedIndexChange = { category = AzurPilotStatCategory.entries[it] },
                )
            }
        }

        ApSectionColumn {
            CategoryControls(
                category = category,
                days = days,
                onDays = { days = it },
                period = period,
                onPeriod = { period = it },
                scope = scope,
                onScope = { scope = it },
                series = series,
                onSeries = { series = it },
                task = task,
                taskOptions = report?.taskOptions.orEmpty(),
                onTask = { task = it },
                onRefresh =
                    if (category == AzurPilotStatCategory.Loot) {
                        { repository.refreshLoot() }
                    } else {
                        null
                    },
            )

            // 加载 / 出错 / 有内容 是三种互斥状态，硬切会让人分不清「刚才是没数据还是没加载完」
            AnimatedContent(
                targetState =
                    when {
                        selected == null -> StatsStage.NoInstance
                        loading && report == null -> StatsStage.Loading
                        report == null -> StatsStage.Error
                        else -> StatsStage.Content
                    },
                transitionSpec = {
                    fadeIn(androidx.compose.animation.core.spring()) togetherWith
                        fadeOut(androidx.compose.animation.core.spring())
                },
                label = "statsStage",
            ) { stage ->
                when (stage) {
                    StatsStage.NoInstance ->
                        AppCard {
                            Text(stringResource(R.string.ap_stats_no_instance))
                        }

                    StatsStage.Loading ->
                        AppCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                ExpressiveLoadingIndicator()
                            }
                        }

                    StatsStage.Error ->
                        AppCard {
                            ApErrorState(error ?: stringResource(R.string.ap_waiting_data))
                        }

                    StatsStage.Content -> {
                        val data = report!!
                        val charted = data.series.filter { it.points.isNotEmpty() }
                        // 指标、曲线、表格全空时要说清「这一类还没有数据」：
                        // 留一片空白会让人以为是界面坏了，而实际上是任务还没跑过
                        if (data.metrics.isEmpty() && charted.isEmpty() && data.tables.isEmpty()) {
                            AppCard {
                                ApEmptyState(
                                    icon = Icons.Filled.BarChart,
                                    title = stringResource(R.string.ap_stats_no_data),
                                    hint = stringResource(R.string.ap_stats_no_data_hint),
                                )
                            }
                        }
                        if (data.metrics.isNotEmpty()) {
                            MetricsGrid(data)
                        }
                        if (charted.isNotEmpty()) {
                            AppCard(
                                title = stringResource(R.string.ap_stats_trend),
                                modifier = Modifier,
                            ) {
                                ApLineChart(charted)
                            }
                        }
                        data.tables.forEachIndexed { index, table ->
                            StatTableCard(table, Modifier)
                        }
                        data.notes.forEach { note ->
                            AppCard {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 统计区的四种互斥状态；用枚举而不是散落的布尔组合，转场才能只盯一个值
 *
 * The four mutually exclusive stages of the statistics area; an enum instead of scattered booleans
 * so the transition can key on a single value.
 */
private enum class StatsStage {
    NoInstance,
    Loading,
    Error,
    Content,
}

/** 指标摘要：两列数字卡 / The metrics summary: a two-column grid of metric cards. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricsGrid(report: AzurPilotStatisticsReport) {
    AppCard(
        title = stringResource(R.string.ap_stats_summary),
        modifier = Modifier,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            maxItemsInEachRow = 2,
        ) {
            report.metrics.forEach { metric ->
                ApMetricCard(
                    label = metric.label,
                    value = metric.value?.let { formatNumber(it) } ?: "—",
                    unit = metric.unit,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 每个分类各自的控制项；没有可调项的分类就不显示这一行
 *
 * The per-category controls; a category with nothing to tune shows no row at all.
 *
 * @param category 当前分类 / the current category
 * @param days 当前「近 N 天」 / the current "last N days" choice
 * @param onDays 改选天数 / picks a day count
 * @param period 当前聚合粒度 / the current aggregation period
 * @param onPeriod 改选粒度 / picks a period
 * @param scope 当前口径（科研：系列 / 消耗品） / the current scope (research: series / consumable)
 * @param onScope 改选口径 / picks a scope
 * @param series 当前选择的研究期数 / the current research period selection
 * @param onSeries 改选期数 / picks a research period
 * @param task 当前筛选的任务；null 表示全部 / the task filter; null means all
 * @param taskOptions 任务筛选候选（战利品分类） / the task-filter candidates (loot category)
 * @param onTask 改选任务 / picks a task
 * @param onRefresh 手动刷新入口；仅战利品分类提供 / the manual refresh entry, offered only for the loot category
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryControls(
    category: AzurPilotStatCategory,
    days: Int,
    onDays: (Int) -> Unit,
    period: String,
    onPeriod: (String) -> Unit,
    scope: String,
    onScope: (String) -> Unit,
    series: Int,
    onSeries: (Int) -> Unit,
    task: String?,
    taskOptions: List<com.azurpilot.ghio.proot.AzurPilotTaskOption>,
    onTask: (String?) -> Unit,
    onRefresh: (() -> Unit)?,
) {
    val showsDays =
        category == AzurPilotStatCategory.Resources || category == AzurPilotStatCategory.Action
    val showsPeriod =
        category == AzurPilotStatCategory.Commission || category == AzurPilotStatCategory.Ships
    val showsScope = category == AzurPilotStatCategory.Research
    val showsTask = category == AzurPilotStatCategory.Loot && taskOptions.isNotEmpty()
    if (!showsDays && !showsPeriod && !showsScope && !showsTask && onRefresh == null) return

    AppCard(modifier = Modifier) {
        if (showsDays) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                DAY_CHOICES.forEach { choice ->
                    FilterChip(
                        selected = days == choice,
                        onClick = { onDays(choice) },
                        label = { Text(stringResource(R.string.ap_stats_days, choice)) },
                    )
                }
            }
        }
        if (showsPeriod) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                PERIOD_CHOICES.forEach { choice ->
                    FilterChip(
                        selected = period == choice,
                        onClick = { onPeriod(choice) },
                        label = {
                            Text(
                                stringResource(
                                    when (choice) {
                                        "day" -> R.string.ap_stats_period_day
                                        "week" -> R.string.ap_stats_period_week
                                        else -> R.string.ap_stats_period_month
                                    }
                                )
                            )
                        },
                    )
                }
            }
        }
        if (showsScope) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                FilterChip(
                    selected = scope == "series",
                    onClick = { onScope("series") },
                    label = { Text(stringResource(R.string.ap_stats_scope_series)) },
                )
                FilterChip(
                    selected = scope == "consumable",
                    onClick = { onScope("consumable") },
                    label = { Text(stringResource(R.string.ap_stats_scope_consumable)) },
                )
            }
            // 期数是 0..20 的整数，0 表示「最新记录的那一期」
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
            ) {
                (0..9).forEach { index ->
                    FilterChip(
                        selected = series == index,
                        onClick = { onSeries(index) },
                        label = {
                            Text(
                                if (index == 0) {
                                    stringResource(R.string.ap_stats_series_latest)
                                } else {
                                    stringResource(R.string.ap_stats_series_n, index)
                                }
                            )
                        },
                    )
                }
            }
        }
        if (showsTask) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                FilterChip(
                    selected = task == null,
                    onClick = { onTask(null) },
                    label = { Text(stringResource(R.string.ap_stats_task_all)) },
                )
                taskOptions
                    .filter { it.count > 0 }
                    .forEach { option ->
                        FilterChip(
                            selected = task == option.key,
                            onClick = { onTask(if (task == option.key) null else option.key) },
                            label = { Text("${option.label} (${option.count})") },
                        )
                    }
            }
        }
        if (onRefresh != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ap_stats_refresh_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.ap_stats_refresh),
                    )
                }
            }
        }
    }
}

/**
 * 折线图：多条序列共用一套纵轴，颜色按固定次序取（同一份数据每次画出来一致）
 *
 * The line chart: all series share one vertical axis, and colors come from a fixed order so the
 * same data always renders identically.
 *
 * @param series 要画的序列，空点序列已被过滤 / the series to draw; empty ones are filtered out by the caller
 */
@Composable
private fun ApLineChart(series: List<AzurPilotStatSeries>) {
    val colors = chartPalette()
    val valid =
        remember(series) {
            series.map { line -> line.points.filter { it.value.isFinite() } }
        }
    val values = remember(valid) { valid.flatten().map { it.value } }
    if (values.isEmpty()) return

    // 横坐标依据真实时间戳，而非各系列自己的数据下标。
    val allTimes = remember(valid) { valid.flatten().map { it.time }.distinct().sorted() }
    val numericTime =
        remember(allTimes) {
            allTimes.map { parseChartTime(it) }
        }
    val hasActualTime = numericTime.all { it != null }
    val timeMap =
        remember(allTimes, numericTime, hasActualTime) {
            if (hasActualTime) {
                allTimes.zip(numericTime.filterNotNull()).toMap()
            } else emptyMap()
        }
    val timeStart = numericTime.filterNotNull().minOrNull() ?: 0.0
    val timeEnd = numericTime.filterNotNull().maxOrNull() ?: 0.0
    val minValue = values.minOrNull() ?: 0.0
    val maxValue = values.maxOrNull() ?: 0.0
    val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val timeIndex = remember(allTimes) { allTimes.withIndex().associate { it.value to it.index } }

    Column(verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
        Spacer(
            modifier =
                Modifier.fillMaxWidth().height(180.dp).drawWithCache {
                    fun xAt(time: String): Float {
                        val ratio =
                            if (hasActualTime && timeEnd > timeStart) {
                                ((timeMap[time] ?: timeStart) - timeStart) / (timeEnd - timeStart)
                            } else if (allTimes.size > 1) {
                                (timeIndex[time] ?: 0).toDouble() / (allTimes.size - 1)
                            } else 0.5
                        return size.width * ratio.coerceIn(0.0, 1.0).toFloat()
                    }
                    fun yAt(value: Double): Float =
                        size.height * (1f - ((value - minValue) / span).toFloat().coerceIn(0f, 1f))

                    // 滚动和入场动画会重复绘制同一图表，曲线仅在数据或尺寸变化时重新分配。
                    val points = valid.map { line ->
                        line.sortedBy { xAt(it.time) }.map { Offset(xAt(it.time), yAt(it.value)) }
                    }
                    val paths = points.map { line ->
                        Path().apply {
                            line.forEachIndexed { index, point ->
                                if (index == 0) moveTo(point.x, point.y)
                                else lineTo(point.x, point.y)
                            }
                        }
                    }
                    val stroke = Stroke(width = 3f, cap = StrokeCap.Round)
                    onDrawBehind {
                        points.forEachIndexed { index, line ->
                            val color = colors[index % colors.size]
                            if (line.size == 1) drawCircle(color, radius = 4f, center = line[0])
                            else if (line.isNotEmpty())
                                drawPath(paths[index], color, style = stroke)
                        }
                    }
                }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = allTimes.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = allTimes.lastOrNull().orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
            Text(
                text = formatNumber(minValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatNumber(maxValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRowChips(series = series, colors = colors)
    }
}

/** 尝试解析统计后端的常见时间格式；不支持的格式回退到有序分类轴。 */
private fun parseChartTime(value: String): Double? {
    val raw = value.trim()
    return raw.toDoubleOrNull()
        ?: runCatching { java.time.Instant.parse(raw).toEpochMilli().toDouble() }.getOrNull()
        ?: runCatching {
            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli().toDouble()
        }.getOrNull()
        ?: runCatching {
            java.time.LocalDateTime.parse(raw.replace(' ', 'T'))
                .toEpochSecond(java.time.ZoneOffset.UTC).toDouble() * 1000.0
        }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(raw).toEpochDay().toDouble() * 86400000.0 }.getOrNull()
        ?: runCatching {
            java.time.YearMonth.parse(raw).atDay(1).toEpochDay().toDouble() * 86400000.0
        }.getOrNull()
}

/** 图例：色点 + 序列名，与曲线取色一致 / The chart legend: color dot + series name, matching the line colors. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(series: List<AzurPilotStatSeries>, colors: List<Color>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
    ) {
        series.forEachIndexed { index, line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(AppTokens.Indicator.dot)
                            .background(
                                colors[index % colors.size],
                                androidx.compose.foundation.shape.CircleShape,
                            )
                )
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = AppTokens.Spacing.xs),
                )
            }
        }
    }
}

/** 曲线取色的固定次序，按序列下标循环使用 / The fixed color order for chart lines, cycled by series index. */
@Composable
private fun chartPalette(): List<Color> =
    listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
    )

/**
 * 明细表：横向滚动 + 竖向滚动
 *
 * 列数由服务端定（最多 9 列），设备屏幕宽度放不下，横滚比压成小字可读。
 *
 * A detail table: horizontal plus vertical scrolling.
 *
 * The column count is decided by the server (up to 9); a device screen width cannot fit that, and
 * horizontal scrolling reads better than shrinking the type.
 *
 * @param table 表格数据 / the table data
 * @param modifier 应用于卡片的修饰符 / the modifier applied to the card
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatTableCard(table: AzurPilotStatTable, modifier: Modifier = Modifier) {
    AppCard(title = table.title, modifier = modifier) {
        if (table.rows.isEmpty()) {
            Text(
                text = stringResource(R.string.ap_stats_no_rows),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val horizontal = rememberScrollState()
            val vertical = rememberScrollState()
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(vertical)
                        .horizontalScroll(horizontal)
            ) {
                Row(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    table.columns.forEach { column ->
                        Text(
                            text = column,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(CELL_WIDTH).padding(AppTokens.Spacing.xs),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                table.rows.forEach { row ->
                    Row {
                        table.columns.indices.forEach { index ->
                            Text(
                                text = row.getOrNull(index).prettyCell(),
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(CELL_WIDTH).padding(AppTokens.Spacing.xs),
                            )
                        }
                    }
                }
            }
        }
        if (table.note.isNotEmpty()) {
            Text(
                text = table.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单元格固定宽度：列对齐靠它，不靠内容测量 / The fixed cell width: columns align by it, not by content measurement. */
private val CELL_WIDTH = 108.dp

/** 分类 → 标签资源 / Maps a category to its tab-label resource. */
private fun statCategoryLabel(category: AzurPilotStatCategory): Int =
    when (category) {
        AzurPilotStatCategory.Resources -> R.string.ap_stats_cat_resources
        AzurPilotStatCategory.Action -> R.string.ap_stats_cat_action
        AzurPilotStatCategory.Opsi -> R.string.ap_stats_cat_opsi
        AzurPilotStatCategory.Commission -> R.string.ap_stats_cat_commission
        AzurPilotStatCategory.Ships -> R.string.ap_stats_cat_ships
        AzurPilotStatCategory.Loot -> R.string.ap_stats_cat_loot
        AzurPilotStatCategory.Research -> R.string.ap_stats_cat_research
    }

/**
 * 统计数字统一不给小数尾巴；小数只在真的存在时保留一位
 *
 * Statistics numbers never carry a dangling decimal tail; a fraction keeps one decimal only when it
 * actually exists.
 */
internal fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format("%.1f", value)
    }
