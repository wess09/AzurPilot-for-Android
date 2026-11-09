package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotStatisticsReport
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTonalButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 通过 Android 文件选择器保存 CSV。 / Saves CSV through Android's file picker. */
@Composable
internal fun StatisticsExport(filename: String, buildCsv: suspend () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(suspend () -> String)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val write = pending
        pending = null
        if (uri == null || write == null) {
            exporting = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val data = withContext(AppDispatchers.Default) { write() }
                withContext(AppDispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter(Charsets.UTF_8).use {
                        it.write(data)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
            } finally {
                exporting = false
            }
        }
    }
    AppTonalButton(enabled = !exporting, onClick = {
        error = false
        pending = buildCsv
        exporting = true
        picker.launch(filename)
    }) { Text(stringResource(if (exporting) R.string.common_loading else R.string.ap_stats_export)) }
    if (error) Text(stringResource(R.string.ap_stats_export_failed))
}

/** 转义引号与换行，保留数字精度。 / Escapes quotes and newlines while retaining numeric precision. */
internal fun StringBuilder.csvRow(values: Iterable<Any?>) {
    appendLine(values.joinToString(",") { value ->
        val text = value?.toString().orEmpty()
        if (text.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\""+text.replace("\"", "\"\"")+"\""
        } else text
    })
}

/** 导出报表的指标、原始序列、表格和备注。 / Exports report metrics, raw series, tables and notes. */
internal fun statisticsCsv(report: AzurPilotStatisticsReport, translate: (String) -> String): String = buildString {
    append('\uFEFF')
    csvRow(listOf(translate("指标"), translate("数值"), translate("单位")))
    report.metrics.forEach { csvRow(listOf(translate(it.label), it.value, translate(it.unit))) }
    report.series.forEach { series ->
        appendLine()
        csvRow(listOf(translate(series.label)))
        csvRow(listOf(translate("时间"), translate("数值"), translate("来源")))
        series.points.forEach { csvRow(listOf(it.time, it.value, it.source?.let(translate))) }
    }
    report.tables.forEach { table ->
        appendLine()
        csvRow(listOf(translate(table.title)))
        csvRow(table.columns.map(translate))
        table.rows.forEach { csvRow(it.map { value -> if (value is String) translate(value) else value }) }
    }
    report.notes.forEach { csvRow(listOf(translate(it))) }
}
