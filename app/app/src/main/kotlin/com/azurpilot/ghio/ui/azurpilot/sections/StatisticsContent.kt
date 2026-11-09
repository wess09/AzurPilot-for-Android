@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.ApValue
import com.azurpilot.ghio.proot.AzurPilotMetric
import com.azurpilot.ghio.proot.AzurPilotStatSeries
import com.azurpilot.ghio.proot.AzurPilotStatTable
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.prettyCell
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.ksu.AppDropdownPreference
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton
import com.azurpilot.ghio.ui.ksu.AppTextField
import java.text.NumberFormat
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 用 WebUI 图标和数值组织收获卡片。 / Presents rewards with WebUI icons and values. */
@Composable
internal fun StatisticsMetrics(metrics: List<AzurPilotMetric>, assets: StatisticsAssets?, translate: (String) -> String) {
    AppCard(title = stringResource(R.string.ap_stats_summary)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            maxItemsInEachRow = 2,
        ) {
            metrics.forEach { metric ->
                Column(
                    Modifier.weight(1f).fillMaxRowHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                        .padding(AppTokens.Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
                ) {
                    StatisticsIcon(assets, metric.label, metric.icon, metricSymbol(metric.key))
                    Text(translate(metric.label), style = MaterialTheme.typography.labelMedium)
                    Text(
                        listOfNotNull(metric.value?.let(::formatNumber) ?: "—", translate(metric.unit).takeIf(String::isNotBlank))
                            .joinToString(" "),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/** 按资源显示曲线与原始记录，避免不同量纲互相压扁。 / Shows a resource trend and its raw records on a readable scale. */
@Composable
internal fun StatisticsTrend(series: List<AzurPilotStatSeries>, assets: StatisticsAssets?, translate: (String) -> String) {
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    val line = series.firstOrNull { it.key == selection } ?: series.firstOrNull { it.points.isNotEmpty() } ?: series.first()
    val points = line.points
    AppCard(title = stringResource(R.string.ap_stats_trend)) {
        AppDropdownPreference(
            title = stringResource(R.string.ap_stats_metric),
            items = series.map { translate(it.label) },
            selectedIndex = series.indexOf(line),
            onSelectedIndexChange = { selection = series[it].key },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
            StatisticsIcon(assets, line.label, line.icon)
            Text(translate(line.label), style = MaterialTheme.typography.titleSmall)
        }
        if (points.isEmpty()) {
            Text(stringResource(R.string.ap_stats_no_rows))
        } else {
            val bounds = remember(line) { points.minOf { it.value } to points.maxOf { it.value } }
            val latest = points.last().value
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.ap_stats_latest, formatNumber(latest)), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.ap_stats_change, formatNumber(latest - points.first().value)), style = MaterialTheme.typography.labelMedium)
            }
            val lineColor = MaterialTheme.colorScheme.primary
            val gridColor = MaterialTheme.colorScheme.outlineVariant
            val time = remember(line) {
                points.map {
                    runCatching { LocalDateTime.parse(it.time.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()
                }
            }
            Canvas(
                Modifier.fillMaxWidth().height(160.dp).drawWithCache {
                    val min = bounds.first
                    val spread = bounds.second - min
                    val first = time.firstOrNull()
                    val last = time.lastOrNull()
                    val duration = if (first != null && last != null) java.time.Duration.between(first, last).toNanos() else 0L
                    val coordinates = points.mapIndexed { index, point ->
                        val current = time[index]
                        val x = if (first != null && current != null && duration > 0) {
                            java.time.Duration.between(first, current).toNanos().toDouble() / duration
                        } else if (points.size > 1) index.toDouble() / points.lastIndex else 0.5
                        val y = if (spread > 0) (point.value - min) / spread else 0.5
                        Offset(size.width * x.toFloat(), size.height * (0.95f - 0.9f * y.toFloat()))
                    }
                    val path = Path().apply {
                        coordinates.forEachIndexed { index, point ->
                            if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                    }
                    onDrawBehind {
                        for (step in 1..3) {
                            val y = size.height * step / 4
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y))
                        }
                        if (coordinates.size == 1) drawCircle(lineColor, 4.dp.toPx(), coordinates.single())
                        else drawPath(path, lineColor, style = Stroke(2.dp.toPx()))
                    }
                }
            ) {}
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(points.first().time.take(16), style = MaterialTheme.typography.labelSmall)
                Text(points.last().time.take(16), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                stringResource(R.string.ap_stats_bounds, formatNumber(bounds.first), formatNumber(bounds.second)),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    if (points.isNotEmpty()) {
        val title = stringResource(R.string.ap_stats_raw)
        val columns = listOf(stringResource(R.string.ap_stats_time), translate(line.label), stringResource(R.string.ap_stats_source))
        val table = remember(line, title, columns) {
            AzurPilotStatTable(title, columns, points.asReversed().map { listOf(it.time, it.value, it.source) },
                "", 0, true, line.key)
        }
        androidx.compose.runtime.key(line.key) { StatisticsTable(table, assets, translate) }
    }
}

/** 按 WebUI 默认规则排序，支持搜索与分页。 / Uses WebUI default sorting, with search and pagination. */
@Composable
internal fun StatisticsTable(
    table: AzurPilotStatTable,
    assets: StatisticsAssets?,
    translate: (String) -> String,
    paginate: Boolean = true,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(table.defaultSortIndex ?: -1) }
    var descending by rememberSaveable { mutableStateOf(table.defaultSortDescending) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val rows = remember(table.rows, sort, descending, search, translate) {
        val filtered = if (search.isBlank()) table.rows else table.rows.filter { row ->
            row.any { value -> value != null && translate(value.toString()).contains(search.trim(), ignoreCase = true) }
        }
        if (sort !in table.columns.indices) filtered else filtered.sortedWith { left, right ->
            compareStatCells(left.getOrNull(sort), right.getOrNull(sort), descending)
        }
    }
    val pageCount = if (paginate) maxOf(1, (rows.size + 24) / 25) else 1
    val current = page.coerceIn(0, pageCount - 1)
    val shown = if (paginate) rows.drop(current * 25).take(25) else rows
    AppCard(title = translate(table.title)) {
        if (paginate && table.rows.size > 1) {
            AppTextField(search, { search = it; page = 0 }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ap_stats_search)) }, singleLine = true)
        }
        if (shown.isEmpty()) {
            Text(stringResource(R.string.ap_stats_no_rows))
        } else {
            val widths = remember(table.columns) {
                table.columns.map { column ->
                    when (column) {
                        "图标" -> 56.dp
                        "时间", "记录时间", "获得时间", "Time", "時刻", "時間", "Start time", "End time" -> 164.dp
                        else -> 112.dp
                    }
                }
            }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    table.columns.forEachIndexed { index, column ->
                        val sortable = if (paginate) {
                            Modifier.clickable(role = Role.Button) {
                                descending = sort == index && !descending
                                sort = index
                                page = 0
                            }
                        } else Modifier
                        Row(
                            Modifier.width(widths[index]).heightIn(min = 44.dp)
                                .then(sortable).padding(AppTokens.Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs),
                        ) {
                            StatisticsIcon(assets, column)
                            Text(translate(column) + if (sort == index) if (descending) " ↓" else " ↑" else "",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                shown.forEachIndexed { rowIndex, row ->
                    Row(
                        if (rowIndex % 2 == 1) Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                        else Modifier,
                    ) {
                        val hasTemplate = row.any { it is String && isStatisticsTemplate(it) }
                        table.columns.indices.forEach { index ->
                            val value = row.getOrNull(index)
                            Row(
                                Modifier.width(widths[index]).padding(AppTokens.Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs),
                            ) {
                                if (value is String && isStatisticsTemplate(value)) {
                                    StatisticsIcon(assets, "", value)
                                } else {
                                    if (value is String && !hasTemplate) StatisticsIcon(assets, value)
                                    Text(
                                        when (value) {
                                            is String -> translate(value)
                                            is Number -> formatNumber(value)
                                            else -> value.prettyCell()
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (pageCount > 1) StatisticsPagination(current, pageCount, onPage = { page = it })
        if (table.note.isNotBlank()) Text(translate(table.note), style = MaterialTheme.typography.bodySmall)
    }
}

/** 始终把缺失值排在最后。 / Always keeps missing values at the end. */
private fun compareStatCells(left: ApValue, right: ApValue, descending: Boolean): Int {
    if (left == null) return if (right == null) 0 else 1
    if (right == null) return -1
    val a = (left as? Number)?.toDouble() ?: left.toString().toDoubleOrNull()
    val b = (right as? Number)?.toDouble() ?: right.toString().toDoubleOrNull()
    val order = if (a != null && b != null) a.compareTo(b) else left.toString().compareTo(right.toString(), ignoreCase = true)
    return if (descending) -order else order
}

/** 适配报表与流水的分页控件。 / Pagination controls for tables and transactions. */
@Composable
internal fun StatisticsPagination(current: Int, count: Int, enabled: Boolean = true, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        AppTextButton(enabled = enabled && current > 0, onClick = { onPage(current - 1) }) {
            Text(stringResource(R.string.ap_stats_previous))
        }
        Text("${current + 1} / $count", style = MaterialTheme.typography.labelMedium)
        AppTextButton(enabled = enabled && current + 1 < count, onClick = { onPage(current + 1) }) {
            Text(stringResource(R.string.ap_stats_next))
        }
    }
}

/** 按当前地区格式保留报表中的有效小数。 / Formats significant decimals in the user's locale. */
internal fun formatNumber(value: Number): String = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = 6
}.format(value)
