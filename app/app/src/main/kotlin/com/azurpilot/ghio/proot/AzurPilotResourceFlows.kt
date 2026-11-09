package com.azurpilot.ghio.proot

import org.json.JSONObject

/** 资源流水的日期、筛选和分页参数。 / Date, filter and pagination parameters for resource transactions. */
data class AzurPilotFlowQuery(
    val days: Int = 7,
    val start: String? = null,
    val end: String? = null,
    val resource: String? = "Oil",
    val task: String? = null,
    val offset: Int = 0,
    val limit: Int = 100,
    val throughId: Long? = null,
)

/** 库存读数与区间收支。 / Inventory reading and interval totals. */
data class AzurPilotInventoryBalance(
    val key: String,
    val label: String,
    val group: String,
    val current: Long?,
    val income: Long,
    val expense: Long,
    val adjustment: Long,
)

/** 按资源和任务汇总的流向。 / Flow totals grouped by resource and task. */
data class AzurPilotFlowTotal(val resource: String, val task: String, val income: Long, val expense: Long)

/** 一条资源流水及其记录来源。 / One resource transaction and its evidence. */
data class AzurPilotFlowEntry(
    val time: String,
    val resource: String,
    val task: String,
    val operation: String,
    val amount: Long,
    val evidence: String,
)

/** 资源管理的库存、流向、流水和油量设置。 / Inventory, flows, transactions and oil settings. */
data class AzurPilotFlowReport(
    val start: String,
    val end: String,
    val throughId: Long,
    val total: Int,
    val resources: List<AzurPilotInventoryBalance>,
    val tasks: List<String>,
    val flows: List<AzurPilotFlowTotal>,
    val entries: List<AzurPilotFlowEntry>,
    val oilEnabled: Boolean,
    val oilTarget: Int,
    val glossary: Map<String, String>,
)

/** 解析资源管理接口，保留未知库存为 null。 / Decodes resource flows and keeps unknown balances null. */
internal fun decodeResourceFlows(data: JSONObject, language: String): AzurPilotFlowReport {
    val control = data.optJSONObject("oilControl")
    val tasks = data.optJSONArray("tasks")
    return AzurPilotFlowReport(
        start = data.getString("start"),
        end = data.getString("end"),
        throughId = data.optLong("throughId"),
        total = data.getInt("total"),
        resources = data.statObjects("resources") { item ->
            AzurPilotInventoryBalance(
                item.getString("key"), item.getString("label"), item.optString("group"),
                (item.opt("current") as? Number)?.toLong(),
                item.getLong("income"), item.getLong("expense"), item.getLong("adjustment"),
            )
        },
        tasks = tasks.mapStats { tasks?.opt(it) as? String },
        flows = data.statObjects("flows") {
            AzurPilotFlowTotal(it.getString("resource"), it.getString("task"), it.getLong("income"), it.getLong("expense"))
        },
        entries = data.statObjects("entries") {
            AzurPilotFlowEntry(it.getString("ts"), it.getString("resource"), it.getString("task"),
                it.getString("operation"), it.getLong("amount"), it.getString("evidence"))
        },
        oilEnabled = control?.optBoolean("enable", true) ?: true,
        oilTarget = control?.optInt("target", 24000) ?: 24000,
        glossary = data.statGlossary(language),
    )
}
