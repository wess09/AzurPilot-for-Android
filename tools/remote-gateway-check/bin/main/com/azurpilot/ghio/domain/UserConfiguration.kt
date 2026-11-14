package com.azurpilot.ghio.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 运行配置的稳定标识；包装 String 以免与普通字符串混用
 *
 * The stable identity of a run configuration; wraps a String so it cannot be
 * confused with an arbitrary one.
 */
@Serializable
@JvmInline
value class RunConfigurationId(val value: String)

/**
 * 主题偏好 / The theme preference.
 */
enum class ThemeMode { System, Light, Dark }

/**
 * 持久化聚合根：只存用户选择，不复制 PI；schemaVersion 在序列化层
 *
 * 本类是 JSON 配置仓（config/UserConfigurationStore）的持久化形态，与 Preferences
 * DataStore（settings/AppSettings）是两套独立栈：这里存的是领域状态（资源选择、
 * option 取值、运行配置），设置项（开关、主题之外的偏好）走 DataStore。
 * 字段演进必须兼容旧 JSON：新增字段带缺省值，不删不rename。
 *
 * The persisted aggregate root: stores only user choices, never a copy of the
 * PI; schemaVersion lives at the serialization layer.
 *
 * This class is the persisted form of the JSON config store
 * (config/UserConfigurationStore), a stack separate from the Preferences
 * DataStore (settings/AppSettings): domain state lives here (resource choice,
 * option values, run configurations), while preference toggles go through the
 * DataStore. Field evolution must stay compatible with old JSON: new fields
 * get default values, and none are removed or renamed.
 */
@Serializable
data class UserConfiguration(
    /** 首次引导是否已完成；false 时 UI 引导用户完成初始选择 / Whether first-run onboarding is done; the UI walks the user through initial choices while false. */
    val initialized: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    /** 当前选中的 resource 内部名；null = 未选 / The selected resource's internal name; null = none selected. */
    val activeResourceName: String? = null,
    /** `global_option[]` 的取值，键为 option 名 / Values for the `global_option[]`, keyed by option name. */
    val globalOptionValues: Map<String, OptionValue> = emptyMap(),
    /** controller 维度 option 取值：controller 名 → (option 名 → 值) / Controller-scoped option values: controller name → (option name → value). */
    val controllerOptionValues: Map<String, Map<String, OptionValue>> = emptyMap(),
    /** resource 维度 option 取值：resource 名 → (option 名 → 值)，切资源互不覆盖 / Resource-scoped option values: resource name → (option name → value); switching resources never overwrites each other. */
    val resourceOptionValues: Map<String, Map<String, OptionValue>> = emptyMap(),
    val configurations: List<RunConfiguration> = emptyList(),
    /** 激活中的运行配置 id；null = 无激活 / The active run configuration id; null = none active. */
    val activeConfigurationId: RunConfigurationId? = null,
    /** 已展示过 welcome 的指纹 / The fingerprint of the already-shown welcome. */
    val welcomeFingerprint: String? = null,
) {
    /** 按 id 查运行配置；id 为 null 或未命中返回 null / Finds a run configuration by id; null for a null id or a miss. */
    fun configuration(id: RunConfigurationId?): RunConfiguration? =
        id?.let { target -> configurations.firstOrNull { it.id == target } }
}

/**
 * 一条运行配置；名称可重复，定位一律用 [id] / One run configuration; names may
 * repeat, always locate by [id].
 */
@Serializable
data class RunConfiguration(
    val id: RunConfigurationId,
    val name: String,
    val tasks: List<ConfiguredTask> = emptyList(),
)

/**
 * 复制整条配置：保留任务设置，配置与任务实例换新身份
 *
 * Duplicates a whole configuration: task settings are kept while the
 * configuration and its task instances get fresh identities.
 */
fun RunConfiguration.duplicate(
    id: RunConfigurationId,
    name: String,
): RunConfiguration = RunConfiguration(
    id = id,
    name = name,
    tasks = tasks.map { it.copy(instanceId = newTaskInstanceId()) },
)

/**
 * 复制任务实例：新 instanceId，继承 taskName/启用/选项；customLabel 由调用方算好（含去重）传入，插在源后
 *
 * Duplicates a task instance: a new instanceId inheriting taskName /
 * enablement / options; [customLabel] is computed by the caller (deduplication
 * included) and the copy is inserted right after its source.
 */
fun RunConfiguration.duplicateTask(taskInstanceId: String, customLabel: String): RunConfiguration {
    val tasks = tasks.toMutableList()
    val index = tasks.indexOfFirst { it.instanceId == taskInstanceId }
    if (index < 0) return this
    val source = tasks[index]
    tasks.add(index + 1, source.copy(instanceId = newTaskInstanceId(), customLabel = customLabel))
    return copy(tasks = tasks)
}

/**
 * 重命名任务：设显示别名；空白/null 清除回退定义 label。规范 taskName 不动，pipeline 不受影响
 *
 * Renames a task by setting the display alias; a blank or null [customLabel]
 * clears it back to the definition label. The canonical taskName is untouched,
 * so pipelines are unaffected.
 */
fun RunConfiguration.renameTask(taskInstanceId: String, customLabel: String?): RunConfiguration =
    copy(tasks = tasks.map {
        if (it.instanceId == taskInstanceId) {
            it.copy(customLabel = customLabel?.trim()?.takeUnless(String::isBlank))
        } else it
    })

/**
 * 算复制任务的展示名：剥掉源名末尾的「copySuffix」或「copySuffix N」得根名，再加 [copySuffix]；
 * 与 [existing] 撞名则追加「 2/3/…」。UI 传当前各任务的展示名，避免「(副本) (副本)」
 *
 * Computes the copy's display name: strips a trailing "copySuffix" or
 * "copySuffix N" from the source label to get the root, then appends
 * [copySuffix]; on collision with [existing] appends " 2/3/…". The UI passes
 * the current display names to avoid stacking "(copy) (copy)".
 */
fun uniqueCopyLabel(sourceLabel: String, copySuffix: String, existing: Collection<String>): String {
    val root = sourceLabel.replace(Regex(Regex.escape(copySuffix) + "( \\d+)?$"), "")
    val base = root + copySuffix
    if (base !in existing) return base
    var n = 2
    while ("$base $n" in existing) n++
    return "$base $n"
}

/**
 * 配置内的单任务条目；同一配置内可重复 taskName，定位用 instanceId
 *
 * One task entry inside a configuration; taskName may repeat within a
 * configuration, location is by instanceId.
 *
 * 旧数据缺 instanceId 时解码补默认值，首次写回后固定 / Legacy data without an
 * instanceId gets the default at decode time, then it is fixed after the
 * first write-back.
 */
@Serializable
data class ConfiguredTask(
    val taskName: String,
    val enabled: Boolean = true,
    val optionValues: Map<String, OptionValue> = emptyMap(),
    /** 显示别名；null = 用定义 label。重命名与「(副本)」后缀都写这里，规范 taskName 不动 / The display alias; null = the definition label. Both renames and the "(copy)" suffix are written here, leaving the canonical taskName untouched. */
    val customLabel: String? = null,
    val instanceId: String = newTaskInstanceId(),
)

/**
 * 生成任务实例 id：随机 UUID，全仓唯一即可，无业务格式 / Generates a task instance id: a
 * random UUID, uniqueness is all that is required — no business format.
 */
fun newTaskInstanceId(): String = java.util.UUID.randomUUID().toString()

/**
 * option 的持久化取值
 *
 * Unset = value map 无该键；MultipleCases(emptyList()) = 明确不选，二者不可混用
 *
 * The persisted value of an option.
 *
 * Unset = the key is absent from the value map; MultipleCases(emptyList()) =
 * explicitly nothing selected. The two are not interchangeable.
 */
@Serializable
sealed interface OptionValue {

    /** Select/Switch 的单选值：选中 case 名 / The single choice of Select/Switch: the selected case name. */
    @Serializable
    @SerialName("single")
    data class SingleCase(val case: String) : OptionValue

    /** Checkbox 的多选值：勾选的 case 名列表 / The multi-choice of Checkbox: the list of checked case names. */
    @Serializable
    @SerialName("multiple")
    data class MultipleCases(val cases: List<String>) : OptionValue

    /** Input 的字段值：字段名 → 输入串 / The Input values: field name → entered string. */
    @Serializable
    @SerialName("inputs")
    data class Inputs(val values: Map<String, String>) : OptionValue
}
