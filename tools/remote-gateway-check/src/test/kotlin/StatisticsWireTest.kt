package com.azurpilot.ghio.proot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONObject

/** 检查新版与旧版统计数据都保留时间、缺失值和来源。 / Checks time, missing values and sources across statistics wire formats. */
class StatisticsWireTest {
    @Test fun sharedAxisPreservesColumnOffsetsAndWallClock() {
        val report = decodeStatisticsReport(JSONObject("""
            {"instance":"ap","category":"storage","axis":[0,1500001,2000000],
             "series":[{"key":"chips","label":"心智单元","icon":"storage:commission_items/Chip",
                        "values":[0,null,42],"sources":["scan","", "verified"]}],
             "tables":[{"title":"仓库物品","key":"storage_items","columns":["数量"],"rows":[[null],[0]],
                        "defaultSort":{"index":0,"descending":true}}],
             "glossary":{"心智单元":{"en-US":"Cognitive Chips","ja-JP":"認識覚醒用チップ"}}}
        """), "en-US")
        val series = report.series.single()
        assertEquals("storage:commission_items/Chip", series.icon)
        assertEquals(listOf(
            AzurPilotStatPoint("1970-01-01 00:00:00", 0.0, "scan"),
            AzurPilotStatPoint("1970-01-01 00:00:02", 42.0, "verified"),
        ), series.points)
        assertEquals("storage_items", report.tables.single().key)
        assertEquals(0, report.tables.single().defaultSortIndex)
        assertEquals(true, report.tables.single().defaultSortDescending)
        assertNull(report.tables.single().rows[0][0])
        assertEquals("Cognitive Chips", report.glossary["心智单元"])
    }

    @Test fun pointFormatSupportsBothGatewayVersions() {
        val report = decodeStatisticsReport(JSONObject("""
            {"instance":"ap","category":"action","series":[{"key":"ap","label":"行动力",
             "points":[{"t":1500001,"v":4.125,"s":"observed"},
                       {"time":"1970-01-02 00:00:00","value":1,"source":"legacy"},
                       {"t":2000000,"v":null}]}]}
        """), "zh-CN")
        assertEquals(listOf(
            AzurPilotStatPoint("1970-01-01 00:00:01.500001", 4.125, "observed"),
            AzurPilotStatPoint("1970-01-02 00:00:00", 1.0, "legacy"),
        ), report.series.single().points)
    }

    @Test fun flowReportDistinguishesUnknownInventoryFromZero() {
        val report = decodeResourceFlows(JSONObject("""
            {"start":"2026-11-01 00:00:00","end":"2026-11-02 00:00:00","throughId":52,"total":30,
             "resources":[{"key":"Oil","label":"石油","current":null,"income":100,"expense":25,"adjustment":-2},
                          {"key":"Cube","label":"心智魔方","current":0,"income":0,"expense":0,"adjustment":0}],
             "flows":[{"resource":"Oil","task":"Commission","income":100,"expense":0}],
             "entries":[{"ts":"2026-11-01 03:00:00","resource":"Oil","task":"Campaign","operation":"出击",
                         "amount":-25,"evidence":"confirmed"}],
             "oilControl":{"enable":false,"target":18000}}
        """), "zh-CN")
        assertNull(report.resources[0].current)
        assertEquals(0L, report.resources[1].current)
        assertEquals(-25L, report.entries.single().amount)
        assertEquals(52L, report.throughId)
        assertEquals(false, report.oilEnabled)
        assertEquals(18000, report.oilTarget)
    }
}
