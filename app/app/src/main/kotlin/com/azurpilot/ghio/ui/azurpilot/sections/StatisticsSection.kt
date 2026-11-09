@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotStatCategory
import com.azurpilot.ghio.proot.AzurPilotStatisticsReport
import com.azurpilot.ghio.proot.AzurPilotStatsQuery
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApEmptyState
import com.azurpilot.ghio.ui.azurpilot.ApErrorState
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.ksu.AppButton
import com.azurpilot.ghio.ui.ksu.AppDropdownPreference
import com.azurpilot.ghio.ui.ksu.AppSettingsGroup
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTonalButton
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 展示八类原生报表和资源管理。 / Shows eight native reports and resource management. */
@Composable
fun StatisticsSection(repository: AzurPilotRepository) {
    val instance by repository.selectedInstance.collectAsStateWithLifecycle()
    val source by repository.sourceId.collectAsStateWithLifecycle()
    val connected by repository.connected.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val locale = LocalConfiguration.current.locales[0]
    val language = when (locale.language) {
        "en" -> "en-US"
        "ja" -> "ja-JP"
        "zh" -> if (locale.country == "TW" || locale.script == "Hant") "zh-TW" else "zh-CN"
        else -> "zh-CN"
    }
    val assets by produceState<StatisticsAssets?>(null, context) {
        value = withContext(AppDispatchers.IO) {
            runCatching { StatisticsAssets(context.assets.open("statistics.zip")) }.getOrNull()
        }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val categories = AzurPilotStatCategory.entries
    Column(Modifier.fillMaxSize()) {
        AppSettingsGroup(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                AppDropdownPreference(
                    title = stringResource(R.string.ap_section_stats),
                    items = categories.map { stringResource(it.labelRes) } + stringResource(R.string.ap_stats_management),
                    selectedIndex = page,
                    onSelectedIndexChange = { page = it },
                )
            }
        }
        val selected = instance
        key(source, selected, language) {
            when {
                selected == null -> ApSectionColumn { AppCard { Text(stringResource(R.string.ap_stats_no_instance)) } }
                page == categories.size -> ResourceManagementSection(repository, selected, connected, assets, language)
                else -> StatisticsReportPage(repository, selected, categories[page], connected, assets, language)
            }
        }
    }
}

/** 报表以趋势和收获为主，操作和筛选使用现有按钮、卡片与间距。 / Prioritizes trends and rewards using existing buttons, cards and spacing. */
@Composable
private fun StatisticsReportPage(
    repository: AzurPilotRepository,
    instance: String,
    category: AzurPilotStatCategory,
    connected: Boolean,
    assets: StatisticsAssets?,
    language: String,
) {
    var days by rememberSaveable { mutableIntStateOf(7) }
    var period by rememberSaveable { mutableStateOf("month") }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var research by rememberSaveable { mutableIntStateOf(9) }
    var task by rememberSaveable { mutableStateOf<String?>(null) }
    val trend = category == AzurPilotStatCategory.Resources || category == AzurPilotStatCategory.Storage
    val hasPeriod = trend || category in listOf(AzurPilotStatCategory.Commission, AzurPilotStatCategory.Loot, AzurPilotStatCategory.Research)
    val hasMonth = category == AzurPilotStatCategory.Action || category == AzurPilotStatCategory.Opsi || (hasPeriod && period == "month")
    val query = AzurPilotStatsQuery(
        category = category,
        days = if (trend && period == "day") days else 7,
        month = month.takeIf { hasMonth },
        period = if (hasPeriod) period else "month",
        series = if (category == AzurPilotStatCategory.Research && research != 0) research else 9,
        scope = if (category == AzurPilotStatCategory.Research && research == 0) "consumable" else "series",
        task = task.takeIf { category == AzurPilotStatCategory.Loot },
    )
    var report by remember(query) { mutableStateOf<AzurPilotStatisticsReport?>(null) }
    var taskOptions by remember { mutableStateOf(emptyList<com.azurpilot.ghio.proot.AzurPilotTaskOption>()) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    var loading by remember(query) { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var action by remember(category) { mutableStateOf(false) }
    var actionMessage by remember(category) { mutableStateOf<String?>(null) }
    val categoryScope = key(category) { rememberCoroutineScope() }
    val storageStarted = stringResource(R.string.ap_stats_storage_started)
    val failed = stringResource(R.string.ap_waiting_data)
    LaunchedEffect(query, reload, connected) {
        if (!connected) return@LaunchedEffect
        repository.statisticsTick.collect {
            loading = true
            repository.statisticsReport(instance, query)
                .onSuccess {
                    report = it
                    if (category == AzurPilotStatCategory.Loot) taskOptions = it.taskOptions
                    error = null
                }
                .onFailure { error = it.message ?: failed }
            loading = false
        }
    }
    val data = report
    val glossary = data?.glossary.orEmpty()
    val translate: (String) -> String = remember(assets, language, glossary) {
        { assets?.translate(it, language, glossary) ?: glossary[it] ?: it }
    }

    key(category) {
        ApSectionColumn {
            AppCard {
                if (hasPeriod) {
                    AppDropdownPreference(
                        title = stringResource(R.string.ap_stats_period),
                        items = listOf(
                            if (trend) stringResource(R.string.ap_stats_days, days) else stringResource(R.string.ap_stats_period_day),
                            stringResource(R.string.ap_stats_period_week),
                            stringResource(R.string.ap_stats_selected_month),
                        ),
                        selectedIndex = periods.indexOf(period),
                        onSelectedIndexChange = { period = periods[it] },
                    )
                }
                if (trend && period == "day") {
                    val choices = listOf(1, 7, 30, 90, 365)
                    AppDropdownPreference(
                        title = stringResource(R.string.ap_stats_range),
                        items = choices.map { stringResource(R.string.ap_stats_days, it) },
                        selectedIndex = choices.indexOf(days),
                        onSelectedIndexChange = { days = choices[it] },
                    )
                }
                if (hasMonth) StatisticsMonthPicker(YearMonth.parse(month)) { month = it.toString() }
                if (category == AzurPilotStatCategory.Research) {
                    AppDropdownPreference(
                        title = stringResource(R.string.ap_stats_scope_series),
                        items = (1..9).map { stringResource(R.string.ap_stats_series_n, it) } +
                            stringResource(R.string.ap_stats_scope_consumable),
                        selectedIndex = if (research == 0) 9 else research - 1,
                        onSelectedIndexChange = { research = if (it == 9) 0 else it + 1 },
                    )
                }
                if (category == AzurPilotStatCategory.Loot) {
                    AppDropdownPreference(
                        title = stringResource(R.string.ap_stats_task),
                        items = listOf(stringResource(R.string.ap_stats_task_all)) + taskOptions.map {
                            if (it.count == 0) translate(it.label) else "${translate(it.label)} (${it.count})"
                        },
                        selectedIndex = taskOptions.indexOfFirst { it.key == task } + 1,
                        onSelectedIndexChange = { task = taskOptions.getOrNull(it - 1)?.key },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                    if (category == AzurPilotStatCategory.Storage) {
                        AppButton(enabled = connected && !action, onClick = {
                            categoryScope.launch {
                                action = true
                                actionMessage = null
                                repository.runStorageStatistics(instance)
                                    .onSuccess { actionMessage = storageStarted }
                                    .onFailure { actionMessage = it.message ?: failed }
                                action = false
                            }
                        }) { Text(stringResource(R.string.ap_stats_run_storage)) }
                    }
                    AppTonalButton(enabled = connected && !action && !loading, onClick = {
                        if (category == AzurPilotStatCategory.Loot) {
                            categoryScope.launch {
                                action = true
                                actionMessage = null
                                repository.refreshLoot(instance)
                                    .onSuccess { reload++ }
                                    .onFailure { actionMessage = it.message ?: failed }
                                action = false
                            }
                        } else {
                            reload++
                        }
                    }) { Text(stringResource(R.string.ap_refresh)) }
                    if (data != null) {
                        StatisticsExport("${instance}-${data.category}-${data.month}.csv") {
                            statisticsCsv(data, translate)
                        }
                    }
                }
                actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            error?.let { message -> AppCard { ApErrorState(message) { reload++ } } }
            when {
                data != null -> StatisticsReportContent(data, assets, translate)
                loading -> StatisticsLoading()
            }
        }
    }
}

private val periods = listOf("day", "week", "month")

/** 以月份和年份筛选报表，避免依赖日期中的日。 / Filters by month and year without an irrelevant day. */
@Composable
private fun StatisticsMonthPicker(value: YearMonth, onChange: (YearMonth) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val years = (maxOf(YearMonth.now().year, value.year) downTo 2020).toList()
    AppDropdownPreference(
        title = stringResource(R.string.ap_stats_year),
        items = years.map(Int::toString),
        selectedIndex = years.indexOf(value.year),
        onSelectedIndexChange = { onChange(value.withYear(years[it])) },
    )
    AppDropdownPreference(
        title = stringResource(R.string.ap_stats_month),
        items = Month.entries.map { it.getDisplayName(TextStyle.FULL_STANDALONE, locale) },
        selectedIndex = value.monthValue - 1,
        onSelectedIndexChange = { onChange(value.withMonth(it + 1)) },
    )
}

/** 显示加载状态。 / Shows the loading state. */
@Composable
internal fun StatisticsLoading() {
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            ExpressiveLoadingIndicator()
        }
    }
}

/** 显示该分类的指标、趋势、明细与备注。 / Shows metrics, trends, details and notes for the category. */
@Composable
private fun StatisticsReportContent(data: AzurPilotStatisticsReport, assets: StatisticsAssets?, translate: (String) -> String) {
    if (data.metrics.isEmpty() && data.series.none { it.points.isNotEmpty() } && data.tables.none { it.rows.isNotEmpty() }) {
        AppCard {
            ApEmptyState(Icons.Filled.BarChart, stringResource(R.string.ap_stats_no_data),
                stringResource(R.string.ap_stats_no_data_hint))
        }
    }
    if (data.metrics.isNotEmpty()) StatisticsMetrics(data.metrics, assets, translate)
    if (data.series.isNotEmpty()) StatisticsTrend(data.series, assets, translate)
    data.tables.forEach { table ->
        key(table.key) { StatisticsTable(table, assets, translate) }
    }
    val notes = data.notes.filter { note -> data.tables.none { it.note.contains(note) } }
    if (notes.isNotEmpty()) {
        AppCard {
            notes.forEach { Text(translate(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** 分类标签与 WebUI 的统计导航一致。 / Matches labels in the WebUI statistics navigation. */
private val AzurPilotStatCategory.labelRes: Int
    get() = when (this) {
        AzurPilotStatCategory.Resources -> R.string.ap_stats_cat_resources
        AzurPilotStatCategory.Action -> R.string.ap_stats_cat_action
        AzurPilotStatCategory.Opsi -> R.string.ap_stats_cat_opsi
        AzurPilotStatCategory.Commission -> R.string.ap_stats_cat_commission
        AzurPilotStatCategory.Ships -> R.string.ap_stats_cat_ships
        AzurPilotStatCategory.Loot -> R.string.ap_stats_cat_loot
        AzurPilotStatCategory.Research -> R.string.ap_stats_cat_research
        AzurPilotStatCategory.Storage -> R.string.ap_stats_cat_storage
    }
