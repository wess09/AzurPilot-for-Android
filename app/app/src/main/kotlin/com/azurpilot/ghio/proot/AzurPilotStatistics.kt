package com.azurpilot.ghio.proot

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 统计曲线上的一个点；[source] 标注数据出处，可空
 *
 * One point on a statistics series; [source] optionally names where the value
 * came from.
 */
data class AzurPilotStatPoint(val time: String, val value: Double, val source: String?)

/**
 * 一条统计曲线：[key] 是图例标识，[label] 是显示名
 *
 * One statistics series; [key] identifies it and [label] is the display name.
 */
data class AzurPilotStatSeries(
    val key: String,
    val label: String,
    val points: List<AzurPilotStatPoint>,
    val icon: String? = null,
)

/**
 * 统计页的一张表；[defaultSortIndex]/[defaultSortDescending] 是网关建议的初始排序
 *
 * One table on the statistics page; the defaultSort pair is the gateway's
 * suggested initial sorting.
 */
data class AzurPilotStatTable(
    val title: String,
    val columns: List<String>,
    val rows: List<List<ApValue>>,
    val note: String,
    val defaultSortIndex: Int?,
    val defaultSortDescending: Boolean,
    val key: String = title,
)

/**
 * 统计页顶部的一张指标卡
 *
 * One metric card at the top of the statistics page.
 */
data class AzurPilotMetric(
    val label: String,
    val value: Double?,
    val unit: String,
    val icon: String?,
    val key: String = label,
)

/**
 * 统计查询的任务候选项
 *
 * A task candidate offered for statistics queries.
 */
data class AzurPilotTaskOption(val key: String, val label: String, val count: Int)

/**
 * `statistics.report` 的整份报表：指标卡、曲线、表格与备注一次带回
 *
 * The full `statistics.report` payload: metric cards, series, tables and notes
 * come back in one response.
 */
data class AzurPilotStatisticsReport(
    val instance: String,
    val category: String,
    val month: String,
    val metrics: List<AzurPilotMetric>,
    val series: List<AzurPilotStatSeries>,
    val tables: List<AzurPilotStatTable>,
    val notes: List<String>,
    val taskOptions: List<AzurPilotTaskOption>,
    val glossary: Map<String, String>,
)

/**
 * 统计分类；顺序与 WebUI 的分段控件一致
 *
 * Statistics categories; the order matches the WebUI segmented control.
 */
enum class AzurPilotStatCategory(val key: String) {
    Resources("resources"),
    Action("action"),
    Opsi("opsi"),
    Commission("commission"),
    Ships("ships"),
    Loot("loot"),
    Research("research"),
    Storage("storage"),
}

/**
 * `statistics.report` 的可选参数，界面控件直接绑到它
 *
 * Optional parameters for `statistics.report`; UI controls bind to it directly.
 */
data class AzurPilotStatsQuery(
    val category: AzurPilotStatCategory,
    val days: Int = 7,
    val month: String? = null,
    val period: String = "month",
    val series: Int = 9,
    val scope: String = "series",
    val task: String? = null,
)

/** 按网关约定保留采集端的本地时间。 / Preserves the producer's wall-clock time. */
private val statsTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)

/** 解析新版微秒时间戳和旧版时间文本。 / Decodes microsecond timestamps and legacy text. */
private fun statTime(value: Any?): String? = when (value) {
    is Number -> {
        val micros = value.toLong()
        val instant = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L))
        val base = LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(statsTimeFormat)
        val fraction = Math.floorMod(micros, 1_000_000L)
        if (fraction == 0L) base else "$base.${fraction.toString().padStart(6, '0')}"
    }
    is String -> value.takeIf { it.isNotBlank() }
    else -> null
}

/** 读取可选数组并跳过类型不符的元素。 / Reads an optional array and skips incompatible entries. */
internal inline fun <T : Any> JSONArray?.mapStats(transform: (Int) -> T?): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(transform)

/** 读取对象数组。 / Reads an array of objects. */
internal inline fun <T : Any> JSONObject.statObjects(key: String, transform: (JSONObject) -> T?): List<T> {
    val array = optJSONArray(key)
    return array.mapStats { index -> array?.optJSONObject(index)?.let(transform) }
}

/** 读取当前界面语言的名称对照。 / Reads the glossary for the current UI language. */
internal fun JSONObject.statGlossary(language: String): Map<String, String> {
    val glossary = optJSONObject("glossary") ?: return emptyMap()
    return buildMap {
        glossary.keys().forEach { term ->
            glossary.optJSONObject(term)?.optText(language)?.let { put(term, it) }
        }
    }
}

/** 解析逐点、共用时间轴和旧版统计报表。 / Decodes point, shared-axis and legacy reports. */
internal fun decodeStatisticsReport(data: JSONObject, language: String): AzurPilotStatisticsReport {
    val axis = data.optJSONArray("axis")
    val times = List(axis?.length() ?: 0) { statTime(axis?.opt(it)) }
    val series = data.statObjects("series") { item ->
        val values = item.optJSONArray("values")
        val sources = item.optJSONArray("sources")
        val points = if (values != null) {
            values.mapStats { index ->
                statPoint(times.getOrNull(index), values.opt(index), sources?.opt(index) as? String)
            }
        } else {
            item.statObjects("points") { point ->
                statPoint(
                    statTime(point.opt("t")) ?: statTime(point.opt("time")),
                    point.opt("v") ?: point.opt("value"),
                    point.optText("s") ?: point.optText("source"),
                )
            }
        }
        AzurPilotStatSeries(item.optString("key"), item.optString("label"), points, item.optText("icon"))
    }
    return AzurPilotStatisticsReport(
        instance = data.getString("instance"),
        category = data.getString("category"),
        month = data.optString("month"),
        metrics = data.statObjects("metrics") { item ->
            val label = item.optString("label")
            AzurPilotMetric(label, (item.opt("value") as? Number)?.toDouble(),
                item.optString("unit"), item.optText("icon"), item.optString("key", label))
        },
        series = series,
        tables = data.statObjects("tables") { item ->
            val columns = item.optJSONArray("columns")
            val rows = item.optJSONArray("rows")
            val sort = item.optJSONObject("defaultSort")
            val title = item.optString("title")
            AzurPilotStatTable(
                title = title,
                columns = columns.mapStats { columns?.opt(it) as? String },
                rows = rows.mapStats { rows?.optJSONArray(it)?.toValueList() },
                note = item.optString("note"),
                defaultSortIndex = sort?.optInt("index", -1)?.takeIf { it >= 0 },
                defaultSortDescending = sort?.optBoolean("descending") ?: false,
                key = item.optString("key", title),
            )
        },
        notes = data.optJSONArray("notes").let { notes -> notes.mapStats { notes?.opt(it) as? String } },
        taskOptions = data.statObjects("taskOptions") { item ->
            AzurPilotTaskOption(item.optString("key"), item.optString("label"), item.optInt("count"))
        },
        glossary = data.statGlossary(language),
    )
}

/** 跳过无效点，保留真实零值。 / Skips invalid points while retaining real zeroes. */
private fun statPoint(time: String?, raw: Any?, source: String?): AzurPilotStatPoint? {
    val value = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() }
    return if (time != null && value != null) AzurPilotStatPoint(time, value, source?.takeIf(String::isNotBlank)) else null
}
