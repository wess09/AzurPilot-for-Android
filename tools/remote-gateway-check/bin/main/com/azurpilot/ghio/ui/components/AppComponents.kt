package com.azurpilot.ghio.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.ksu.AppDialog
import com.azurpilot.ghio.ui.ksu.AppSwitch
import com.azurpilot.ghio.ui.ksu.AppTextField
import com.azurpilot.ghio.ui.ksu.AppTextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.material.SegmentedListItem
import com.azurpilot.ghio.ui.ksu.material.TonalCard

private val LocalInsideAppCard = compositionLocalOf { false }

/** KSU 卡片的业务内容适配器。 / Business content adapter for KSU cards. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    collapsible: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) AppFieldLabel(title, Modifier.padding(start = 16.dp, bottom = 8.dp))
        if (LocalUiMode.current == UiMode.Miuix) {
            top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = {
                        CompositionLocalProvider(LocalInsideAppCard provides true) { content() }
                    },
                )
            }
        } else
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = {
                        CompositionLocalProvider(LocalInsideAppCard provides true) { content() }
                    },
                )
            }
    }
}

/** 使用 KSU 列表行的尾部控件槽。 / Uses KSU list rows with a trailing control. */
@Composable
fun AppLabeledControlRow(
    label: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    labelColor: Color = Color.Unspecified,
    icon: Any? = null,
    iconActive: Boolean? = null,
    trailing: @Composable () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.basic.BasicComponent(
            title = label,
            startAction = icon?.let { { AppPreferenceIcon(it, active = iconActive) } },
            insideMargin =
                if (LocalInsideAppCard.current) PaddingValues(vertical = 12.dp)
                else top.yukonga.miuix.kmp.basic.BasicComponentDefaults.InsideMargin,
            modifier = modifier,
            endActions = { trailing() },
        )
    } else
        SegmentedListItem(
            modifier = modifier,
            headlineContent = { Text(label, style = labelStyle, color = labelColor) },
            leadingContent = icon?.let { { AppPreferenceIcon(it, active = iconActive) } },
            trailingContent = trailing,
        )
}

/** 使用 KSU 的主题导航行。 / Uses KSU's themed navigation row. */
@Composable
fun AppNavigationRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: Any? = null,
) {
    var activation by remember { mutableIntStateOf(0) }
    val open = { activation++; onClick() }
    if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.preference.ArrowPreference(
            title = label,
            summary = description,
            onClick = open,
            startAction = icon?.let { { AppPreferenceIcon(it, animationKey = activation) } },
            modifier = modifier,
        )
    } else
        SegmentedListItem(
            modifier = modifier,
            onClick = open,
            leadingContent = icon?.let { { AppPreferenceIcon(it, animationKey = activation) } },
            headlineContent = { Text(label) },
            supportingContent = description?.let { { Text(it) } },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
        )
}

/** KSU 无边框卡片。 / KSU tonal card. */
@Composable
fun AppCardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Card(modifier = modifier) { content() }
    else TonalCard(modifier = modifier, content = content)
}

/** KSU 设置分组标题。 / KSU settings group heading. */
@Composable
fun AppFieldLabel(text: String, modifier: Modifier = Modifier) {
    val headingModifier = modifier.semantics { heading() }
    if (LocalUiMode.current == UiMode.Miuix)
        if (LocalInsideAppCard.current)
            top.yukonga.miuix.kmp.basic.Text(
                text = text,
                modifier = headingModifier,
                style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        else top.yukonga.miuix.kmp.basic.SmallTitle(text = text, modifier = headingModifier)
    else
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = headingModifier,
        )
}



// 保持所有 AzurPilot 参数与应用设置使用同一组主题原生偏好行。
/**
 * 复用 Miuix SwitchPreference / Material SegmentedListItem 的整行可点开关。
 *
 * Uses the platform-specific settings switch row instead of a free-standing custom switch.
 */
@Composable
fun AppSwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    icon: Any? = null,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.preference.SwitchPreference(
            title = title,
            summary = summary,
            checked = checked,
            startAction = icon?.let { { AppPreferenceIcon(it, active = checked) } },
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
        )
    } else {
        SegmentedListItem(
            modifier = modifier.semantics(mergeDescendants = true) {
                role = Role.Switch
            },
            onClick = { if (enabled) onCheckedChange(!checked) },
            headlineContent = { Text(title) },
            leadingContent = icon?.let { { AppPreferenceIcon(it, active = checked) } },
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = {
                AppSwitch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            },
        )
    }
}

/**
 * 使用主题原生复选设置行统一点击与无障碍状态，整行只触发一次值变更。
 *
 * Uses themed checkbox preference rows with unified clicks and accessibility state, triggering
 * exactly one value change per row activation.
 */
@Composable
fun AppCheckboxPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: Any? = null,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.preference.CheckboxPreference(
            title = title,
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            checkboxLocation = top.yukonga.miuix.kmp.preference.CheckboxLocation.End,
            startAction = icon?.let { { AppPreferenceIcon(it, active = checked) } },
        )
    } else {
        SegmentedListItem(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            headlineContent = { Text(title) },
            leadingContent = icon?.let { { AppPreferenceIcon(it, active = checked) } },
            trailingContent = {
                com.azurpilot.ghio.ui.ksu.AppCheckbox(checked, onCheckedChange = null)
            },
        )
    }
}

/**
 * 常规字符串与数字设置：列表只占一行，编辑时使用与主设置相同的原生弹窗和文本框。
 * [singleLine] 为 false 时允许换行；[onRunNow] 提供调度快捷操作，执行后关闭弹窗。
 *
 * Simple string/number preference edited in a themed dialog. Validation happens before dismissal.
 * [singleLine] controls multiline input; [onRunNow] adds a scheduling action and dismisses the dialog.
 */
@Composable
fun AppEditablePreference(
    title: String,
    value: String,
    onSave: (String) -> Boolean,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    icon: Any? = null,
    singleLine: Boolean = true,
    onRunNow: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    var invalid by remember { mutableStateOf(false) }
    val detail = summary ?: if (password && value.isNotEmpty()) "••••••" else value.ifEmpty { "—" }
    if (enabled) {
        AppNavigationRow(
            label = title,
            description = detail,
            icon = icon,
            modifier = modifier,
            onClick = {
                draft = value
                invalid = false
                editing = true
            },
        )
    } else {
        AppInfoRow(label = title, value = detail, icon = icon)
    }
    if (editing) {
        AppDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                AppTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        invalid = false
                    },
                    labelText = title,
                    leadingIcon = icon?.let { { AppPreferenceIcon(it) } },
                    trailingIcon = onRunNow?.let { runNow ->
                        {
                            IconButton(onClick = {
                                runNow()
                                editing = false
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.ap_run_now),
                                )
                            }
                        }
                    },
                    singleLine = singleLine,
                    maxLines = if (singleLine) 1 else 12,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation =
                        if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    isError = invalid,
                    supportingText = {
                        if (invalid) {
                            Text(
                                stringResource(R.string.ap_field_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                AppTextButton(onClick = {
                    if (onSave(draft)) {
                        editing = false
                    } else {
                        invalid = true
                    }
                }) { Text(stringResource(R.string.ap_deploy_save)) }
            },
            dismissButton = {
                AppTextButton(onClick = { editing = false }) {
                    Text(stringResource(R.string.ap_cancel))
                }
            },
        )
    }
}

/**
 * 信息通过主题列表行展示；[copyable] 为真时整行长按与尾部按钮均可复制值。
 *
 * Displays information through themed list rows; when [copyable] is true, both a long press on
 * the row and the trailing button copy the value to the clipboard.
 */
@Composable
fun AppInfoRow(label: String, value: String, icon: Any? = null, copyable: Boolean = false) {
    val context = LocalContext.current
    val copiedHint = stringResource(R.string.settings_info_copied)
    val copyLabel = stringResource(R.string.settings_info_copy)
    val haptics = LocalHapticFeedback.current
    val copyToClipboard = {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        // Android 13+ 写入剪贴板时系统自带确认气泡，只有旧系统才需要自行提示
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, copiedHint, Toast.LENGTH_SHORT).show()
        }
    }
    val longPressCopy: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        copyToClipboard()
    }
    // 长按手势挂在行根容器上，两种主题的行组件都不暴露独立的长按入口
    val copyModifier =
        if (copyable)
            Modifier.pointerInput(value) { detectTapGestures(onLongPress = { longPressCopy() }) }
        else Modifier
    val copyButton: @Composable () -> Unit = {
        IconButton(onClick = copyToClipboard) {
            AppSymbolIcon(AppSymbol.Copy, contentDescription = copyLabel)
        }
    }
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.BasicComponent(
            modifier = Modifier.semantics(mergeDescendants = true) {}.then(copyModifier),
            title = label,
            summary = value,
            startAction = icon?.let { { AppPreferenceIcon(it) } },
            insideMargin =
                if (LocalInsideAppCard.current) PaddingValues(vertical = 12.dp)
                else top.yukonga.miuix.kmp.basic.BasicComponentDefaults.InsideMargin,
            endActions = if (copyable) {
                { copyButton() }
            } else null,
        )
    else SegmentedListItem(
        modifier = Modifier.semantics(mergeDescendants = true) {}.then(copyModifier),
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        leadingContent = icon?.let { { AppPreferenceIcon(it) } },
        trailingContent = if (copyable) {
            { copyButton() }
        } else null,
    )
}
