package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.ApValue
import com.azurpilot.ghio.proot.AzurPilotField
import com.azurpilot.ghio.proot.AzurPilotSchema
import com.azurpilot.ghio.proot.prettyText
import com.azurpilot.ghio.proot.sameValueAs
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.components.AppLabeledControlRow
import com.azurpilot.ghio.ui.ksu.AppCheckbox as Checkbox
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppSwitch as Switch
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import com.azurpilot.ghio.ui.ksu.AppTextField as OutlinedTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 一个参数的保存状态，用来在行尾给一行小字
 *
 * A single argument's save status, shown as a small line at the row's end.
 */
enum class ApFieldStatus {
    Idle,
    Saving,
    Saved,
    Failed,
}

/**
 * 参数值的文本形式；嵌套结构给 pretty JSON，避免显示成 `{a=1}` 那种 toString
 *
 * The text form of an argument value; nested structures get pretty JSON instead of a `{a=1}`-style
 * toString.
 */
fun fieldTextOf(value: ApValue): String = value.prettyText()

/**
 * 本地校验结果：[payload] 为 null 表示这次输入还不能提交
 *
 * [display] 是输入框该显示的文本；null 表示维持用户输入。 清空数字/时间字段回落默认值时，网关存的是默认值而输入框如果还空着， 看起来就像「没保存上」——WebUI 用
 * `text: String(fallback)` 解决，这里同理。
 *
 * The outcome of local validation: a null [payload] means the input is not submittable yet.
 *
 * [display] is what the field should show; null keeps the user's input. When clearing a
 * numeric/datetime field falls back to the default, the gateway stores the default while the box
 * would stay empty — looking like the save never happened. The WebUI solves it with `text:
 * String(fallback)`; ditto here.
 *
 * @property payload 可提交的值；null 表示本地校验未通过 / the submittable value; null when local validation failed
 * @property display 输入框该显示的文本；null 维持用户输入 / the text the field should show; null keeps the user's
 *   input
 * @property error 预留的错误文案 / the reserved error text
 */
data class PreparedField(val payload: ApValue, val display: String?, val error: String?)

/**
 * 把输入框里的文本转成能提交的值
 *
 * 与网关的校验规则对齐（类型、整数性、区间、正则、日期格式），这样**本地就能拦下**明显不合法 的输入，不必等一次往返再显示红字。
 *
 * Converts a field's text into a submittable value.
 *
 * Aligned with the gateway's validation rules (type, integrality, range, regex, date format) so
 * obviously invalid input is **rejected locally** instead of waiting a round trip to show red text.
 *
 * @param field 参数定义 / the argument definition
 * @param text 输入框文本 / the field's text
 * @return 校验与转换结果 / the validation and conversion result
 */
fun prepareFieldValue(field: AzurPilotField, text: String): PreparedField {
    val trimmed = text.trim()
    // preserve_empty 的字段空值本身有意义，照提交；其余空值回落默认值
    // （调度时间「立即运行」正是靠这条：清空 → 提交默认的过去时间 → 调度器视为待运行）
    if (trimmed.isEmpty() && !field.preserveEmpty) {
        val fallback = field.value
        if ((field.numeric || field.type == "datetime") && fallback != null) {
            return PreparedField(fallback, fieldTextOf(fallback), null)
        }
        if (!field.numeric) return PreparedField(text, null, null)
        return PreparedField(null, null, null)
    }
    if (field.type == "datetime") {
        if (trimmed.isNotEmpty() && !DATETIME.matches(trimmed)) {
            return PreparedField(null, null, null)
        }
        return PreparedField(text, null, null)
    }
    if (!field.numeric) {
        field.pattern?.let { pattern ->
            if (trimmed.isNotEmpty() && !Regex(pattern).matches(trimmed)) {
                return PreparedField(null, null, null)
            }
        }
        return PreparedField(text, null, null)
    }
    val number = trimmed.toDoubleOrNull() ?: return PreparedField(null, null, null)
    if (field.value is Int && number != number.toLong().toDouble())
        return PreparedField(null, null, null)
    field.range?.let { (min, max) ->
        if (number < min || number > max) return PreparedField(null, null, null)
    }
    // 默认值是整数的参数，网关要求 type(value) is int
    val payload: ApValue = if (field.value is Int) number.toLong().toInt() else number
    return PreparedField(payload, null, null)
}

/** 日期时间字段接受的格式 / The format accepted by datetime fields. */
private val DATETIME = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

/**
 * 一个参数行
 *
 * 控件按 schema 的定义挑：布尔→开关，有候选→下拉，多选→勾选组，其余→文本。 开关与下拉这类**离散选择**改完立刻提交；文本类**防抖后提交**——设备上逐字符发请求既费流量，
 * 也会让中间态（`1`、`1.`）撞上服务端校验。
 *
 * One argument row.
 *
 * The control is picked by the schema's definition: boolean → switch, options → dropdown,
 * multiselect → checkbox group, everything else → text. **Discrete choices** like switches and
 * dropdowns commit immediately; text fields **commit after a debounce** — per-character requests on
 * a device burn traffic and let intermediate states (`1`, `1.`) trip server validation.
 *
 * @param schema schema 定义 / the schema definition
 * @param group 分组名 / the group name
 * @param argument 参数名 / the argument name
 * @param field 参数元信息 / the argument's metadata
 * @param value 当前值 / the current value
 * @param status 保存状态 / the save status
 * @param statusError 保存失败时网关给的原文——「参数必须在 0 到 100 之间：Main.Campaign.X」 正是用户要的下一步 / the gateway's raw
 *   message on save failure — "argument must be between 0 and 100: Main.Campaign.X" is exactly the
 *   user's next step
 * @param onChange 提交新值 / commits a new value
 * @param onRetry 失败后重试提交 / retries the failed commit
 * @param onRunNow 「立刻运行」入口；仅调度时间参数提供 / the run-now entry, offered only for the scheduler-time
 *   argument
 * @param onCheckScript 受限 Lua 的预检入口 / the restricted-Lua precheck entry
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApFieldRow(
    schema: AzurPilotSchema,
    group: String,
    argument: String,
    field: AzurPilotField,
    value: ApValue,
    status: ApFieldStatus,
    statusError: String? = null,
    onChange: (ApValue) -> Unit,
    onRetry: () -> Unit,
    onRunNow: (() -> Unit)? = null,
    onCheckScript: (suspend (String) -> String?)? = null,
) {
    val readOnly = field.readOnly
    val label = schema.fieldLabel(group, argument)
    val help = schema.fieldHelp(group, argument)
        .takeUnless { it.isEmpty() || it == "help" || it == argument }
        ?.replace(HELP_TAG, "")
    val shortHelp = help?.let { if (it.length > 150) it.take(150) + "…" else it }
    val isMultiline = field.mode == "yaml" ||
        field.mode == "restricted_lua" ||
        field.type == "task_priority" ||
        (value is String && value.contains('\n')) ||
        (field.value is String && field.value.contains('\n'))
    val isPureReadOnly = readOnly && !field.clearableStorage
    // textarea 保留紧凑的设置行，弹窗仍允许换行；代码编辑模式继续使用专用控件。
    val simpleEdit = (field.type == "input" || field.type == "datetime" ||
        field.type == "textarea") && !isMultiline &&
        field.option.isNullOrEmpty() &&
        !field.booleanish &&
        !readOnly
    val inlinePreference = isPureReadOnly || field.booleanish ||
        (field.type != "multiselect" && !field.option.isNullOrEmpty()) ||
        simpleEdit

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
    ) {
        // 简单字段完全由原生 Preference 绘制标题、说明与控件，
        // 不再叠加自定义标题或分隔用的输入框卡片。
        if (!inlinePreference) {
            Row(
                modifier = Modifier.padding(horizontal = AppTokens.Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (readOnly) Text(
                    text = stringResource(R.string.ap_field_readonly),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusIndicator(status)
            }
            if (!help.isNullOrBlank()) {
                var helpExpanded by rememberSaveable(group, argument) { mutableStateOf(false) }
                Text(
                    text = help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (helpExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = AppTokens.Spacing.md)
                        .then(
                            if (help.length > 80) Modifier.clickable {
                                helpExpanded = !helpExpanded
                            } else Modifier
                        ),
                )
            }
        }
        when {
            field.clearableStorage ->
                StorageControl(value, enabled = !readOnly && field.clearableStorage) {
                    onChange(emptyMap<String, ApValue>())
                }

            isPureReadOnly ->
                com.azurpilot.ghio.ui.components.AppInfoRow(
                    label = label,
                    value = fieldTextOf(value).ifEmpty { "—" }.take(180),
                )

            field.booleanish ->
                com.azurpilot.ghio.ui.components.AppSwitchPreference(
                    title = label,
                    summary = shortHelp,
                    checked = value as? Boolean ?: false,
                    enabled = !readOnly,
                    onCheckedChange = { onChange(it) },
                )

            field.type == "multiselect" ->
                MultiSelect(
                    field = field,
                    value = value,
                    enabled = !readOnly,
                    labelOf = { schema.optionLabel(group, argument, it) },
                    onChange = onChange,
                )

            !field.option.isNullOrEmpty() ->
                OptionSelect(
                    title = label,
                    summary = shortHelp,
                    field = field,
                    value = value,
                    enabled = !readOnly,
                    labelOf = { schema.optionLabel(group, argument, it) },
                    onChange = onChange,
                )

            simpleEdit ->
                com.azurpilot.ghio.ui.components.AppEditablePreference(
                    title = label,
                    value = fieldTextOf(value),
                    onSave = { raw ->
                        val prepared = prepareFieldValue(field, raw)
                        val payload = prepared.payload
                        if (payload == null) false else {
                            if (!payload.sameValueAs(value)) onChange(payload)
                            true
                        }
                    },
                    keyboardType = when {
                        field.numeric -> KeyboardType.Decimal
                        field.type == "datetime" -> KeyboardType.Text
                        else -> KeyboardType.Text
                    },
                    singleLine = field.type != "textarea",
                    onRunNow = onRunNow,
                )

            else ->
                TextControl(
                    label = label,
                    field = field,
                    value = value,
                    enabled = !readOnly,
                    onCommit = onChange,
                    onRunNow = onRunNow,
                    onCheckScript = onCheckScript,
                )
        }
        if (inlinePreference && status != ApFieldStatus.Idle) {
            StatusIndicator(status)
        }
        if (status == ApFieldStatus.Failed) FailedNotice(statusError, onRetry)
    }
}

/** 帮助文案里的 HTML 标签，展示前直接剥掉 / HTML tags inside help text, stripped before display. */
private val HELP_TAG = Regex("<[^>]*>")

/**
 * 行尾的保存状态指示
 *
 * The save-status indicator at the row's end.
 */
@Composable
private fun StatusIndicator(status: ApFieldStatus) {
    // 保存中 → 已保存 → 无 是同一处的三种态，交叉淡入才不会在行尾闪一下
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            fadeIn(androidx.compose.animation.core.spring()) togetherWith
                fadeOut(androidx.compose.animation.core.spring())
        },
        label = "fieldStatus",
    ) { state ->
        when (state) {
            ApFieldStatus.Idle,
            ApFieldStatus.Failed -> Unit
            ApFieldStatus.Saving ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xs),
                ) {
                    ExpressiveLoadingIndicator(
                        modifier = Modifier.size(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.ap_field_saving),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            ApFieldStatus.Saved ->
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.ap_field_saved),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AppTokens.IconSize.sm),
                )
        }
    }
}

/**
 * 保存失败的原因
 *
 * 只给一个「重试」按钮等于让用户猜哪里错了：网关的校验消息（区间、正则、日期格式、类型） 就是修好这件事所需的全部信息，必须原样显示出来。
 *
 * The reason a save failed.
 *
 * A lone "retry" button makes users guess what went wrong: the gateway's validation message (range,
 * regex, date format, type) is exactly the information needed to fix it and must be shown verbatim.
 *
 * @param error 网关的校验消息；null 表示原因不明 / the gateway's validation message; null when the cause is
 *   unknown
 * @param onRetry 重试提交 / retries the commit
 */
@Composable
private fun FailedNotice(error: String?, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(AppTokens.IconSize.sm),
        )
        Text(
            text =
                error?.let { stringResource(R.string.ap_field_failed, it) }
                    ?: stringResource(R.string.ap_field_failed_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text(
                stringResource(R.string.ap_field_retry),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * 只读值：嵌套结构给等宽 pretty JSON，截断到固定行数
 *
 * A read-only value: nested structures get monospace pretty JSON, truncated to a fixed line count.
 */
@Composable
private fun ReadOnlyValue(text: String) {
    Text(
        text = text.ifEmpty { "—" },
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 存储区：只读展示 + 清空（网关只接受清成 `{}`）
 *
 * A storage area: read-only display plus a clear action (the gateway only accepts clearing it to
 * `{}`).
 */
@Composable
private fun StorageControl(value: ApValue, enabled: Boolean, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = fieldTextOf(value).ifEmpty { "—" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (enabled) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = stringResource(R.string.ap_field_clear),
                )
            }
        }
    }
}

/**
 * 单选下拉：候选取自 schema，显示名走翻译；当前值不在候选里时也照样显示出来
 *
 * A single-select dropdown: candidates come from the schema, display names go through translation,
 * and a current value outside the candidates still shows up instead of leaving the box empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSelect(
    title: String,
    summary: String?,
    field: AzurPilotField,
    value: ApValue,
    enabled: Boolean,
    labelOf: (ApValue) -> String,
    onChange: (ApValue) -> Unit,
) {
    val candidates = field.option.orEmpty()
    val options =
        if (candidates.any { it.sameValueAs(value) }) candidates else listOf(value) + candidates
    com.azurpilot.ghio.ui.ksu.AppDropdownPreference(
        title = title,
        summary = summary,
        items = options.map(labelOf),
        selectedIndex = options.indexOfFirst { it.sameValueAs(value) }.coerceAtLeast(0),
        enabled = enabled,
        onSelectedIndexChange = { index ->
            if (!options[index].sameValueAs(value)) onChange(options[index])
        },
    )
}

/**
 * 多选：勾选即提交整个列表（网关要求元素与候选"类型与值都相同"且不重复）
 *
 * A multi-select: each toggle commits the whole list (the gateway requires elements to be "same
 * type and value" as a candidate, without duplicates).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiSelect(
    field: AzurPilotField,
    value: ApValue,
    enabled: Boolean,
    labelOf: (ApValue) -> String,
    onChange: (ApValue) -> Unit,
) {
    val selected = value as? List<ApValue> ?: emptyList()
    Column(Modifier.fillMaxWidth()) {
        field.option.orEmpty().forEach { option ->
            val checked = selected.any { it.sameValueAs(option) }
            com.azurpilot.ghio.ui.components.AppSwitchPreference(
                title = labelOf(option),
                checked = checked,
                enabled = enabled,
                onCheckedChange = { next ->
                    onChange(
                        if (next) selected + option
                        else selected.filterNot { it.sameValueAs(option) }
                    )
                },
            )
        }
    }
}

/**
 * 文本类控件：防抖提交 + 本地预校验
 *
 * The text-family control: debounced commit plus local pre-validation.
 */
@Composable
private fun TextControl(
    label: String,
    field: AzurPilotField,
    value: ApValue,
    enabled: Boolean,
    onCommit: (ApValue) -> Unit,
    onRunNow: (() -> Unit)?,
    onCheckScript: (suspend (String) -> String?)?,
) {
    val multiline =
        field.type == "textarea" ||
            field.mode == "yaml" ||
            field.mode == "restricted_lua" ||
            field.type == "task_priority" ||
            (value is String && value.contains('\n')) ||
            (field.value is String && field.value.contains('\n'))
    var text by rememberSaveable(field) { mutableStateOf(fieldTextOf(value)) }
    var touched by rememberSaveable(field) { mutableStateOf(false) }
    var invalid by rememberSaveable(field) { mutableStateOf(false) }
    var scriptError by rememberSaveable(field) { mutableStateOf<String?>(null) }
    var scriptChecked by rememberSaveable(field) { mutableStateOf(false) }
    var checking by remember(field) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentValue by rememberUpdatedState(value)
    val currentCommit by rememberUpdatedState(onCommit)

    // 懒列表回收行时会取消防抖协程；先提交合法草稿，非法输入由可保存状态保留。
    DisposableEffect(field) {
        onDispose {
            if (touched) {
                val payload = prepareFieldValue(field, text).payload
                if (payload != null && !payload.sameValueAs(currentValue)) currentCommit(payload)
            }
        }
    }

    // 值来自服务端（配置刚加载完、或换了实例）时同步进来；用户动过的框不再覆盖
    LaunchedEffect(value) {
        if (!touched) text = fieldTextOf(value)
    }

    LaunchedEffect(text, touched) {
        if (!touched) return@LaunchedEffect
        delay(DEBOUNCE_MS)
        val prepared = prepareFieldValue(field, text)
        invalid = prepared.payload == null
        val payload = prepared.payload ?: return@LaunchedEffect
        // 清空回落默认值时，框里要立刻显示回落后的值——否则「看着是空的、存的是默认值」
        prepared.display?.let { text = it }
        if (!payload.sameValueAs(value)) onCommit(payload)
        // 提交后以本地副本/服务端为准：放开 touched，值回同步才能接上（失败时值不变，输入照旧保留）
        touched = false
    }

    val placeholder = when {
        field.type == "datetime" -> "YYYY-MM-DD HH:MM:SS"
        field.mode == "yaml" -> stringResource(R.string.ap_field_yaml_hint)
        field.mode == "restricted_lua" -> stringResource(R.string.ap_field_script_hint)
        field.value != null && fieldTextOf(field.value).isNotEmpty() -> fieldTextOf(field.value)
        else -> ""
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            touched = true
            scriptError = null
            scriptChecked = false
            text = it
        },
        labelText = label,
        placeholderText = placeholder,
        enabled = enabled,
        isError = invalid || scriptError != null,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 12 else 1,
        keyboardOptions =
            KeyboardOptions(
                keyboardType =
                    when {
                        field.type == "datetime" -> KeyboardType.Text
                        field.value is Int -> KeyboardType.Number
                        field.numeric -> KeyboardType.Decimal
                        else -> KeyboardType.Text
                    }
            ),
        textStyle =
            if (multiline || field.type == "datetime") {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        supportingText = {
            when {
                !enabled -> Text(stringResource(R.string.ap_field_readonly))
                invalid ->
                    Text(
                        stringResource(R.string.ap_field_invalid),
                        color = MaterialTheme.colorScheme.error,
                    )
                scriptError != null -> Text(scriptError!!, color = MaterialTheme.colorScheme.error)
                scriptChecked ->
                    Text(
                        text = stringResource(R.string.ap_script_valid),
                        color = MaterialTheme.colorScheme.primary,
                    )

                field.type == "datetime" -> Text(stringResource(R.string.ap_field_datetime_format))
                field.mode == "yaml" -> Text(stringResource(R.string.ap_field_yaml_hint))
                field.mode == "restricted_lua" ->
                    Text(stringResource(R.string.ap_field_script_hint))
            }
        },
        trailingIcon = {
            if (onRunNow != null && enabled) {
                IconButton(onClick = onRunNow) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.ap_run_now),
                    )
                }
            }
        },
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = AppTokens.Spacing.md)
                .then(if (multiline) Modifier.heightIn(min = 100.dp) else Modifier),
    )

    // 受限脚本可以在提交前先求一次诊断，拿到行列号；不点也能靠保存失败发现
    if (onCheckScript != null && enabled && !readOnlyHint(field)) {
        TextButton(
            enabled = !checking,
            onClick = {
                checking = true
                scope.launch {
                    scriptError = onCheckScript(text)
                    scriptChecked = true
                    checking = false
                }
            },
            modifier = Modifier.padding(start = AppTokens.Spacing.md),
        ) {
            Text(
                text =
                    stringResource(
                        if (checking) R.string.ap_script_checking else R.string.ap_script_check
                    )
            )
        }
    }
}

/** 只读字段不提供脚本检查按钮 / Read-only fields get no script-check button. */
private fun readOnlyHint(field: AzurPilotField): Boolean = field.readOnly

/** 文本提交的防抖时长（毫秒） / The text-commit debounce, in milliseconds. */
private const val DEBOUNCE_MS = 600L
