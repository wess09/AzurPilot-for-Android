@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotFlowEntry
import com.azurpilot.ghio.proot.AzurPilotFlowQuery
import com.azurpilot.ghio.proot.AzurPilotFlowReport
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotStatTable
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApErrorState
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.ksu.AppButton
import com.azurpilot.ghio.ui.ksu.AppDropdownPreference
import com.azurpilot.ghio.ui.ksu.AppSwitch
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton
import com.azurpilot.ghio.ui.ksu.AppTextField
import com.azurpilot.ghio.ui.ksu.AppTonalButton
import java.time.Duration
import java.time.LocalDateTime
import kotlinx.coroutines.launch

private const val flowPageSize = 25

/** 显示库存、流向和可筛选流水，沿用现有卡片、间距与按钮。 / Presents inventory, flow and filterable transactions with the existing card, spacing and button tokens. */
@Composable
internal fun ResourceManagementSection(
    repository: AzurPilotRepository,
    instance: String,
    connected: Boolean,
    assets: StatisticsAssets?,
    language: String,
) {
    val source by repository.sourceId.collectAsStateWithLifecycle()
    val schema by repository.schema.collectAsStateWithLifecycle()
    val ranges = listOf(1, 7, 30)
    var range by rememberSaveable { mutableIntStateOf(1) }
    var start by rememberSaveable { mutableStateOf<String?>(null) }
    var end by rememberSaveable { mutableStateOf<String?>(null) }
    var resource by rememberSaveable { mutableStateOf<String?>("Oil") }
    var task by rememberSaveable { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    val base = AzurPilotFlowQuery(ranges.getOrElse(range) { 7 }, start, end, resource, task, limit = flowPageSize)
    var snapshot by remember(base) { mutableStateOf<AzurPilotFlowReport?>(null) }
    val query = if (page == 0) base else base.copy(
        start = snapshot?.start, end = snapshot?.end, throughId = snapshot?.throughId, offset = page * flowPageSize,
    )
    var options by remember { mutableStateOf<AzurPilotFlowReport?>(null) }
    var data by remember(base) { mutableStateOf<AzurPilotFlowReport?>(null) }
    var loadedPage by remember(base) { mutableIntStateOf(0) }
    var loading by remember(query, reload) { mutableStateOf(true) }
    var error by remember(query, reload) { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.ap_waiting_data)
    fun refresh() { page = 0; reload++ }
    LaunchedEffect(query, reload, connected) {
        if (!connected) return@LaunchedEffect
        suspend fun load() {
            loading = true
            repository.resourceFlows(instance, query)
                .onSuccess {
                    if (query.offset == 0) snapshot = it
                    data = it
                    loadedPage = query.offset / flowPageSize
                    options = it
                    error = null
                }
                .onFailure { error = it.message ?: failed }
            loading = false
        }
        if (query.offset == 0) repository.statisticsTick.collect { load() } else load()
    }
    val report = data
    val glossary = options?.glossary.orEmpty()
    val translate: (String) -> String = remember(assets, language, glossary) {
        { assets?.translate(it, language, glossary) ?: glossary[it] ?: it }
    }
    val labels = remember(options?.resources) { options?.resources.orEmpty().associate { it.key to it.label } }
    val resourceName: (String) -> String = remember(labels, translate) { { translate(labels[it] ?: it) } }
    val unknownTask = stringResource(R.string.ap_flow_unattributed)
    val oilControl = stringResource(R.string.ap_flow_oil)
    val taskName: (String) -> String = remember(schema, unknownTask, oilControl) {
        { when (it) {
            "Unattributed" -> unknownTask
            "OilControl" -> oilControl
            else -> schema?.taskTitle(it) ?: it
        } }
    }
    val confirmed = stringResource(R.string.ap_flow_confirmed)
    val observed = stringResource(R.string.ap_flow_observed)
    val recognition = stringResource(R.string.ap_flow_recognition)
    val adjustment = stringResource(R.string.ap_flow_adjustment)
    val evidenceName: (String) -> String = remember(confirmed, observed, recognition, adjustment) {
        { when (it) {
            "confirmed" -> confirmed
            "observed" -> observed
            "recognition" -> recognition
            "adjustment" -> adjustment
            else -> it
        } }
    }
    val detailColumns = listOf(
        stringResource(R.string.ap_stats_time), stringResource(R.string.ap_flow_resource),
        stringResource(R.string.ap_stats_task), stringResource(R.string.ap_flow_operation),
        stringResource(R.string.ap_flow_net), stringResource(R.string.ap_flow_evidence),
    )
    fun entryRow(entry: AzurPilotFlowEntry, name: String) = listOf(
        entry.time.replace('T', ' '), name, taskName(entry.task),
        translate(entry.operation), entry.amount, evidenceName(entry.evidence),
    )

    ApSectionColumn {
        AppCard {
            Text(stringResource(R.string.ap_flow_hint), style = MaterialTheme.typography.bodySmall)
            AppDropdownPreference(
                title = stringResource(R.string.ap_stats_range),
                items = ranges.map { stringResource(R.string.ap_stats_days, it) } + stringResource(R.string.ap_flow_custom),
                selectedIndex = range,
                onSelectedIndexChange = {
                    range = it
                    if (it != ranges.size) { start = null; end = null; page = 0 }
                },
            )
            if (range == ranges.size) FlowDateRange(start, end) { from, to -> start = from; end = to; page = 0 }
            val resources = options?.resources.orEmpty()
            AppDropdownPreference(
                title = stringResource(R.string.ap_flow_resource),
                items = listOf(stringResource(R.string.ap_flow_all_resources)) + resources.map { resourceName(it.key) },
                selectedIndex = resources.indexOfFirst { it.key == resource } + 1,
                enabled = resources.isNotEmpty(),
                onSelectedIndexChange = { resource = resources.getOrNull(it - 1)?.key; task = null; page = 0 },
            )
            val tasks = options?.tasks.orEmpty()
            AppDropdownPreference(
                title = stringResource(R.string.ap_stats_task),
                items = listOf(stringResource(R.string.ap_flow_all_tasks)) + tasks.map(taskName),
                selectedIndex = tasks.indexOf(task) + 1,
                enabled = tasks.isNotEmpty(),
                onSelectedIndexChange = { task = tasks.getOrNull(it - 1); page = 0 },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                AppTonalButton(enabled = !loading, onClick = ::refresh) { Text(stringResource(R.string.ap_refresh)) }
                if (report != null && report.total > 0) {
                    StatisticsExport("$instance-resource-flows.csv") {
                        buildString {
                            append('\uFEFF')
                            csvRow(detailColumns)
                            var offset = 0
                            while (offset < report.total) {
                                check(repository.sourceId.value == source && repository.selectedInstance.value == instance)
                                val next = repository.resourceFlows(instance, base.copy(
                                    start = report.start, end = report.end, throughId = report.throughId, offset = offset, limit = 1000,
                                )).getOrThrow()
                                if (next.entries.isEmpty()) break
                                next.entries.forEach { csvRow(entryRow(it, resourceName(it.resource))) }
                                offset += next.entries.size
                            }
                        }
                    }
                }
            }
        }
        error?.let { message -> AppCard { ApErrorState(message, ::refresh) } }
        if (report == null && loading) StatisticsLoading()
        if (report != null) {
            OilControl(repository, instance, report, ::refresh)
            ResourceFlowGraph(report, assets, resourceName, taskName) { selectedResource, selectedTask ->
                resource = selectedResource; task = selectedTask; page = 0
            }
            val inventoryTitle = stringResource(R.string.ap_flow_inventory)
            val inventoryColumns = listOf(
                stringResource(R.string.ap_flow_resource), stringResource(R.string.ap_flow_current),
                stringResource(R.string.ap_flow_income), stringResource(R.string.ap_flow_expense),
                stringResource(R.string.ap_flow_net), adjustment,
            )
            val inventory = remember(report.resources, inventoryColumns, resourceName) {
                AzurPilotStatTable(inventoryTitle, inventoryColumns, report.resources.map {
                    listOf(it.label, it.current, it.income, it.expense, it.income - it.expense, it.adjustment)
                }, "", null, false)
            }
            StatisticsTable(inventory, assets, translate)
            val details = stringResource(R.string.ap_flow_details, report.total)
            val accounting = stringResource(R.string.ap_flow_accounting)
            val table = remember(report.entries, details, detailColumns, translate, resourceName, taskName, evidenceName) {
                AzurPilotStatTable(details, detailColumns, report.entries.map {
                    entryRow(it, labels[it.resource] ?: it.resource)
                }, accounting, null, false)
            }
            key(loadedPage) { StatisticsTable(table, assets, translate, paginate = false) }
            if (report.total > flowPageSize) {
                StatisticsPagination(loadedPage, (report.total + flowPageSize - 1) / flowPageSize, !loading) { page = it }
            }
        }
    }
}

/** 验证与 WebUI 相同的本地时间区间。 / Validates the same wall-clock range as the WebUI. */
@Composable
private fun FlowDateRange(start: String?, end: String?, apply: (String, String) -> Unit) {
    var from by rememberSaveable { mutableStateOf(start?.replace('T', ' ') ?: "") }
    var to by rememberSaveable { mutableStateOf(end?.replace('T', ' ') ?: "") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    val format = "YYYY-MM-DD HH:MM"
    AppTextField(from, { from = it; invalid = false }, modifier = Modifier.fillMaxWidth(),
        labelText = stringResource(R.string.ap_flow_start), placeholderText = format, singleLine = true)
    AppTextField(to, { to = it; invalid = false }, modifier = Modifier.fillMaxWidth(),
        labelText = stringResource(R.string.ap_flow_end), placeholderText = format, singleLine = true)
    AppTonalButton(onClick = {
        val dates = runCatching {
            LocalDateTime.parse(from.trim().replace(' ', 'T')) to LocalDateTime.parse(to.trim().replace(' ', 'T'))
        }.getOrNull()
        if (dates != null && dates.first < dates.second && Duration.between(dates.first, dates.second) <= Duration.ofDays(366)) {
            invalid = false
            apply(dates.first.toString(), dates.second.toString())
        } else invalid = true
    }) { Text(stringResource(R.string.ap_flow_apply)) }
    if (invalid) Text(stringResource(R.string.ap_flow_invalid), color = MaterialTheme.colorScheme.error)
}

/** 保存油量控制的开关与目标。 / Saves the oil-control toggle and target. */
@Composable
private fun OilControl(repository: AzurPilotRepository, instance: String, report: AzurPilotFlowReport, reload: () -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(report.oilEnabled) }
    var target by rememberSaveable { mutableStateOf(report.oilTarget.toString()) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saved = stringResource(R.string.ap_field_saved)
    val failed = stringResource(R.string.ap_deploy_save_failed)
    LaunchedEffect(report.oilEnabled, report.oilTarget) {
        if (!dirty) {
            enabled = report.oilEnabled
            target = report.oilTarget.toString()
        }
    }
    AppCard(title = stringResource(R.string.ap_flow_oil)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.ap_flow_oil_enable), modifier = Modifier.weight(1f))
            AppSwitch(enabled, { enabled = it; dirty = true; message = null }, enabled = !saving)
        }
        AppTextField(target, { target = it; dirty = true; message = null }, modifier = Modifier.fillMaxWidth(),
            labelText = stringResource(R.string.ap_flow_oil_target), enabled = !saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        val value = target.toIntOrNull()
        if (value == null || value !in 1000..24999) {
            Text(stringResource(R.string.ap_flow_oil_invalid), color = MaterialTheme.colorScheme.error)
        }
        AppButton(enabled = dirty && !saving && value != null && value in 1000..24999, onClick = {
            if (value != null) scope.launch {
                saving = true
                repository.saveOilControl(instance, enabled, value)
                    .onSuccess { message = saved; dirty = false; reload() }
                    .onFailure { message = it.message ?: failed }
                saving = false
            }
        }) { Text(stringResource(R.string.ap_deploy_save)) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** 用任务收支和库存差额画平衡流向。 / Draws balanced flows from task totals and stock differences. */
@Composable
private fun ResourceFlowGraph(
    report: AzurPilotFlowReport,
    assets: StatisticsAssets?,
    resourceName: (String) -> String,
    taskName: (String) -> String,
    onPick: (String, String?) -> Unit,
) {
    val keys = remember(report.flows) { report.flows.map { it.resource }.distinct() }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val resource = picked?.takeIf { it in keys } ?: keys.firstOrNull()
    AppCard(title = stringResource(R.string.ap_flow_sankey)) {
        if (resource == null) {
            Text(stringResource(R.string.ap_stats_no_rows))
        } else {
            if (keys.size > 1) {
                AppDropdownPreference(
                    title = stringResource(R.string.ap_flow_resource),
                    items = keys.map(resourceName),
                    selectedIndex = keys.indexOf(resource),
                    onSelectedIndexChange = { picked = keys[it] },
                )
            }
            val incoming = stringResource(R.string.ap_flow_income)
            val outgoing = stringResource(R.string.ap_flow_expense)
            val stock = stringResource(R.string.ap_flow_stock)
            val balance = stringResource(R.string.ap_flow_balance)
            val tasks = remember(report.flows, resource) { report.flows.filter { it.resource == resource }.groupBy { it.task } }
            val income = tasks.mapValues { (_, rows) -> rows.sumOf { it.income } }.filterValues { it > 0 }
            val expense = tasks.mapValues { (_, rows) -> rows.sumOf { it.expense } }.filterValues { it > 0 }
            val inTotal = income.values.sum()
            val outTotal = expense.values.sum()
            val total = maxOf(inTotal, outTotal)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(incoming, style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatisticsIcon(assets, resource)
                    Text(resourceName(resource), style = MaterialTheme.typography.labelMedium)
                }
                Text(outgoing, style = MaterialTheme.typography.labelSmall)
            }
            val inColor = MaterialTheme.colorScheme.primary
            val outColor = MaterialTheme.colorScheme.tertiary
            if (total > 0) Canvas(Modifier.fillMaxWidth().height(112.dp)) {
                val bar = 6.dp.toPx()
                val center = size.width / 2
                val usable = size.height * 0.84f
                fun ribbons(amounts: List<Long>, from: Float, to: Float, centerAtRight: Boolean) {
                    val color = if (centerAtRight) inColor else outColor
                    val gap = (size.height - usable) / maxOf(1, amounts.size - 1)
                    var stacked = (size.height - usable) / 2
                    var separated = if (amounts.size == 1) stacked else 0f
                    amounts.forEachIndexed { index, amount ->
                        val height = usable * (amount.toDouble() / total).toFloat()
                        val left = if (centerAtRight) separated else stacked
                        val right = if (centerAtRight) stacked else separated
                        val mid = (from + to) / 2
                        drawPath(Path().apply {
                            moveTo(from, left)
                            cubicTo(mid, left, mid, right, to, right)
                            lineTo(to, right + height)
                            cubicTo(mid, right + height, mid, left + height, from, left + height)
                            close()
                        }, color.copy(alpha = if (index % 2 == 0) 0.36f else 0.22f))
                        val edge = if (centerAtRight) 0f else size.width - bar
                        drawRect(color, Offset(edge, separated), Size(bar, height))
                        stacked += height
                        separated += height + gap
                    }
                }
                ribbons(income.values.toList() + listOfNotNull((outTotal - inTotal).takeIf { it > 0 }), bar, center, true)
                ribbons(expense.values.toList() + listOfNotNull((inTotal - outTotal).takeIf { it > 0 }), center, size.width - bar, false)
                drawRect(inColor, Offset(center - bar / 2, (size.height - usable) / 2), Size(bar, usable))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                Column(Modifier.weight(1f)) {
                    income.forEach { (task, amount) ->
                        AppTextButton(onClick = { onPick(resource, task) }) { Text("${taskName(task)} +${formatNumber(amount.toDouble())}") }
                    }
                    if (outTotal > inTotal) Text("$stock · ${formatNumber((outTotal - inTotal).toDouble())}")
                }
                Column(Modifier.weight(1f)) {
                    expense.forEach { (task, amount) ->
                        AppTextButton(onClick = { onPick(resource, task) }) { Text("${taskName(task)} −${formatNumber(amount.toDouble())}") }
                    }
                    if (inTotal > outTotal) Text("$balance · ${formatNumber((inTotal - outTotal).toDouble())}")
                }
            }
        }
    }
}
