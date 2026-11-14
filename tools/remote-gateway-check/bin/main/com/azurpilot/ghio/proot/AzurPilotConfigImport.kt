package com.azurpilot.ghio.proot

import org.json.JSONArray
import org.json.JSONObject

/**
 * 将配置导入转换为网关允许的批次，保留 Android 连接参数并跳过未变化的值。
 *
 * 在 IO 线程生成全部批次后再发送，避免格式错误发生在部分配置已写入之后。
 * 同一任务的参数组不拆开，以保留心情恢复等跨字段校验所需的完整输入。
 * 针对桌面端导出可能携带的日期微秒、越界数值及字符串空值/数字等常见不一致问题，
 * 依据 [AzurPilotSchema] 进行前置规整，防止网关因单字段校验失败而拒绝导入。
 *
 * Converts a config import into gateway-sized batches, preserving Android connection settings
 * and skipping unchanged values.
 *
 * Builds all batches on an IO thread before sending, so malformed input cannot fail after a
 * partial write. Each task's argument group stays together for validation of related fields,
 * such as emotion recovery settings. Mismatched timestamp microseconds, out-of-range numericals,
 * and string type inconsistencies exported by desktop instances are sanitized against the
 * [AzurPilotSchema] before dispatch.
 */
internal object AzurPilotConfigImport {
    /**
     * 与上游 `PatchParams.changes` 的数量上限一致。
     *
     * Matches the upstream `PatchParams.changes` limit.
     */
    private const val MAX_CHANGES = 200

    /**
     * 标准日期时间正则（带可选微秒及 T 分隔符）。
     *
     * Standard datetime regex with optional microseconds and 'T' separator.
     */
    private val DATETIME_PATTERN = Regex("""^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(?:\.\d+)?.*$""")

    /**
     * 规范化导入配置项的值，解决桌面端导出与校验端规则不一致导致的上游兼容性问题：
     * 1. 日期带微秒或 ISO 'T' 分隔符：截断为标准 `YYYY-MM-DD HH:mm:ss` 格式；
     * 2. 数值越界：根据 schema 的 validate [min, max] 区间进行收敛（如心情 -1 收敛为 0）；
     * 3. 类型不符：schema 定义为字符串但导出为 null 或数字时，规整为 "" 或对应字符串字面量；
     * 4. 数值/布尔类型不符：做合理的类型兜底转换。
     *
     * Sanitizes and normalizes an imported setting value against schema constraints:
     * 1. Datetime with microseconds or 'T' separator: truncated to `YYYY-MM-DD HH:mm:ss`;
     * 2. Numerical out-of-bounds: clamped to schema's validate [min, max] range (e.g. emotion -1 -> 0);
     * 3. Type mismatch: null or numeric values coerced to string for string-typed fields;
     * 4. Numeric/boolean mismatch: coerced gracefully.
     */
    fun sanitize(raw: Any?, field: AzurPilotField): Any? {
        val isNull = raw == null || raw == JSONObject.NULL

        // 1. 日期格式处理 (datetime)
        val isDatetime = field.type == "datetime" || field.validate == "datetime" ||
            (field.value is String && DATETIME_PATTERN.matches(field.value.trim()))
        if (isDatetime) {
            if (isNull) return (field.value as? String) ?: ""
            val str = raw.toString().trim()
            val match = DATETIME_PATTERN.matchEntire(str)
            return if (match != null) {
                val (date, time) = match.destructured
                "$date $time"
            } else {
                str
            }
        }

        // 2. 数值区间约束 (validate: [min, max])
        val range = field.range
        if (range != null && !isNull) {
            val num = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
            if (num != null) {
                val clamped = num.coerceIn(range.first, range.second)
                return when {
                    field.value is Int || raw is Int || (clamped % 1.0 == 0.0 && field.value !is Double && field.value !is Float) -> clamped.toInt()
                    field.value is Long || raw is Long -> clamped.toLong()
                    else -> clamped
                }
            }
        }

        // 3. 字符串类型规范化
        val expectsString = field.value is String ||
            (field.type in setOf("input", "textarea", "select") && !field.numeric && !field.booleanish)
        if (expectsString && raw !is JSONObject && raw !is JSONArray && raw !is Map<*, *> && raw !is List<*>) {
            if (isNull) return ""
            if (raw is Number || raw is Boolean) return raw.toString()
            return raw.toString()
        }

        // 4. 数字类型规范化
        if (field.numeric) {
            if (isNull) return field.value
            if (raw is String) {
                val trimmed = raw.trim()
                val parsed = if (field.value is Int) trimmed.toIntOrNull()
                else if (field.value is Long) trimmed.toLongOrNull()
                else trimmed.toDoubleOrNull()
                return parsed ?: field.value
            }
            if (raw is Number) {
                return if (field.value is Int) raw.toInt()
                else if (field.value is Long) raw.toLong()
                else raw
            }
        }

        // 5. 布尔类型规范化
        if (field.booleanish) {
            if (isNull) return field.value
            if (raw is String) {
                return when (raw.trim().lowercase()) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> field.value
                }
            }
            if (raw is Number) return raw.toInt() != 0
            if (raw is Boolean) return raw
        }

        // 6. 其它情况：如果为空且 schema 有默认标量值，兜底
        if (isNull && field.value is String) return ""
        if (isNull) return JSONObject.NULL

        return raw
    }

    /**
     * 对导入的 JSON 配置做全量规整，将不合规的日期微秒、越界数值及类型不匹配字段收敛回 schema 要求。
     *
     * Sanitizes a whole JSON config string against schema definitions, normalizing
     * timestamp microseconds, out-of-range numericals, and mismatched string types.
     */
    fun sanitizeContent(content: String, schema: AzurPilotSchema): String {
        val root = JSONObject(content.trimStart().removePrefix("\uFEFF"))
        val values = root.optJSONObject("values") ?: root
        values.keys().forEach { task ->
            val groups = values.optJSONObject(task) ?: return@forEach
            groups.keys().forEach groupLoop@{ group ->
                if (task == "Alas" && group == "Emulator") return@groupLoop
                val fields = groups.optJSONObject(group) ?: return@groupLoop
                fields.keys().forEach fieldLoop@{ argument ->
                    val field = schema.field(task, group, argument) ?: return@fieldLoop
                    if (field.readOnly || field.hidden) return@fieldLoop
                    val sanitized = sanitize(fields.opt(argument), field)
                    fields.put(argument, sanitized.toJsonCompatible())
                }
            }
        }
        return root.toString(2)
    }

    /**
     * 返回按参数组划分的批次；兼容配置没有变化时返回空列表。
     *
     * Returns batches partitioned by argument group, or an empty list for an unchanged config.
     *
     * @throws IllegalArgumentException 没有兼容参数或单组超过协议上限。
     *   / No compatible fields exist, or one group exceeds the protocol limit.
     */
    fun batches(
        content: String,
        schema: AzurPilotSchema,
        current: ApConfigValues,
    ): List<JSONArray> {
        val root = JSONObject(content.trimStart().removePrefix("\uFEFF"))
        val values = root.optJSONObject("values") ?: root
        val batches = mutableListOf<JSONArray>()
        var batch = JSONArray()
        var compatible = false
        values.keys().forEach { task ->
            val groups = values.optJSONObject(task) ?: return@forEach
            groups.keys().forEach groupLoop@{ group ->
                if (task == "Alas" && group == "Emulator") return@groupLoop
                val fields = groups.optJSONObject(group) ?: return@groupLoop
                val groupChanges = mutableListOf<JSONObject>()
                fields.keys().forEach fieldLoop@{ argument ->
                    val field = schema.field(task, group, argument) ?: return@fieldLoop
                    if (field.readOnly || field.hidden) return@fieldLoop
                    compatible = true
                    val rawValue = fields.opt(argument)
                    val sanitized = sanitize(rawValue, field)
                    if (sanitized.asApValue().sameValueAs(current[task]?.get(group)?.get(argument))) {
                        return@fieldLoop
                    }
                    groupChanges += JSONObject()
                        .put("path", "$task.$group.$argument")
                        .put("value", sanitized.toJsonCompatible())
                }
                require(groupChanges.size <= MAX_CHANGES) {
                    "Configuration group exceeds the import limit: $task.$group"
                }
                if (batch.length() + groupChanges.size > MAX_CHANGES) {
                    batches += batch
                    batch = JSONArray()
                }
                groupChanges.forEach { batch.put(it) }
            }
        }
        require(compatible) { "No compatible settings found" }
        if (batch.length() > 0) batches += batch
        return batches
    }
}
