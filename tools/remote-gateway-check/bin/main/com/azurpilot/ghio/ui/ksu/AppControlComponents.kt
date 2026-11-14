@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.azurpilot.ghio.ui.ksu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.azurpilot.ghio.ui.components.LocalActionIconTrigger
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode

/** 辅助动作使用当前主题的按钮。 / Secondary actions use the active framework's button. */
@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var iconTrigger by remember { mutableIntStateOf(0) }
    val action = { iconTrigger++; onClick() }
    CompositionLocalProvider(LocalActionIconTrigger provides iconTrigger) {
        if (LocalUiMode.current == UiMode.Miuix)
            top.yukonga.miuix.kmp.basic.Button(action, modifier, enabled) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAppTextStyle provides top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.button
                ) {
                    content()
                }
            }
        else
            TextButton(
                action,
                modifier,
                enabled,
                shape,
                colors,
                elevation,
                border,
                contentPadding,
                interactionSource,
                content,
            )
    }
}

/** 多选控制使用当前主题的复选框。 / Checkboxes follow the active framework. */
@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onCheckedChange?.let { { it(!checked) } },
            modifier = modifier,
            enabled = enabled,
        )
    else Checkbox(checked, onCheckedChange, modifier, enabled, colors, interactionSource)
}

/** 数值调节使用框架自带滑块。 / Numeric controls use framework sliders. */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Slider(
            value,
            onValueChange,
            modifier,
            enabled,
            valueRange,
            steps,
            onValueChangeFinished,
        )
    else
        Slider(
            value,
            onValueChange,
            modifier,
            enabled,
            valueRange,
            steps,
            onValueChangeFinished,
            colors,
        )
}

/** 筛选动作使用 Miuix 按钮或 Material 芯片。 / Filter actions use native themed controls. */
@Composable
fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Button(
            onClick = onClick,
            modifier = modifier.semantics { this.selected = selected },
            enabled = enabled,
            minHeight = 32.dp,
            minWidth = 0.dp,
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            colors =
                if (selected) top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary()
                else top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors(),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAppTextStyle provides top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2
            ) {
                leadingIcon?.invoke()
                label()
                trailingIcon?.invoke()
            }
        }
    else FilterChip(selected, onClick, label, modifier, enabled, leadingIcon, trailingIcon)
}

/** 内容表面由对应框架绘制。 / Content surfaces are rendered by the active framework. */
@Composable
fun AppSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Surface(
            modifier,
            shape,
            color,
            contentColor,
            border,
            shadowElevation,
            content,
        )
    else
        Surface(
            modifier,
            shape,
            color,
            contentColor,
            tonalElevation,
            shadowElevation,
            border,
            content,
        )
}

/** 原生进度条同时覆盖确定和不确定进度。 / Native progress bars support determinate and indeterminate states. */
@Composable
fun AppLinearProgressIndicator(modifier: Modifier = Modifier) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.LinearProgressIndicator(modifier)
    else LinearProgressIndicator(modifier)
}

/**
 * 平滑显示业务进度；重试归零和完成立即同步，两套 UI 共用相同插值。
 *
 * 插值对齐下载器的 200 ms 采样间隔；动画沿用 Compose 的系统动画时长缩放。
 *
 * Smooths business progress, snapping on resets and completion with shared interpolation in both UIs.
 *
 * Interpolation matches the downloader's 200 ms sampling interval and honors Compose's system
 * animation duration scale.
 */
@Composable
fun AppLinearProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier) {
    val target = progress().coerceIn(0f, 1f)
    val animated = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (target <= 0f || target >= 1f || target < animated.targetValue) {
            animated.snapTo(target)
        } else {
            animated.animateTo(target, tween(durationMillis = 200, easing = LinearEasing))
        }
    }
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.LinearProgressIndicator(modifier, animated.value)
    else LinearProgressIndicator({ animated.value }, modifier)
}

/** 导出等底部操作复用原生弹层。 / Bottom actions use framework sheets. */
@Composable
fun AppBottomSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.window.WindowBottomSheet(
            show = true,
            onDismissRequest = onDismissRequest,
        ) {
            Column(content = content)
        }
    else ModalBottomSheet(onDismissRequest = onDismissRequest, content = content)
}

/** 分隔线使用对应主题默认颜色。 / Dividers use the active framework's line color. */
@Composable
fun AppHorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = Color.Unspecified,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.HorizontalDivider(
            modifier,
            thickness,
            if (color == Color.Unspecified)
                top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.dividerLine
            else color,
        )
    else
        HorizontalDivider(
            modifier,
            thickness,
            if (color == Color.Unspecified) MaterialTheme.colorScheme.outlineVariant else color,
        )
}

/**
 * 消息队列保留业务 API，显示和计时由主题原生宿主处理。 / Business keeps its queue API while native hosts render and time
 * messages.
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    if (LocalUiMode.current != UiMode.Miuix) {
        SnackbarHost(hostState, modifier)
        return
    }
    val nativeState =
        androidx.compose.runtime.remember { top.yukonga.miuix.kmp.basic.SnackbarHostState() }
    val current = hostState.currentSnackbarData
    androidx.compose.runtime.LaunchedEffect(current) {
        if (current != null) {
            val result =
                nativeState.showSnackbar(
                    message = current.visuals.message,
                    actionLabel = current.visuals.actionLabel,
                    withDismissAction = current.visuals.withDismissAction,
                    duration =
                        when (current.visuals.duration) {
                            SnackbarDuration.Short ->
                                top.yukonga.miuix.kmp.basic.SnackbarDuration.Short
                            SnackbarDuration.Long ->
                                top.yukonga.miuix.kmp.basic.SnackbarDuration.Long
                            SnackbarDuration.Indefinite ->
                                top.yukonga.miuix.kmp.basic.SnackbarDuration.Indefinite
                        },
                )
            if (result == top.yukonga.miuix.kmp.basic.SnackbarResult.ActionPerformed)
                current.performAction()
            else current.dismiss()
        }
    }
    top.yukonga.miuix.kmp.basic.SnackbarHost(nativeState, modifier)
}

/** 可点击浮层使用框架的 Surface 交互。 / Clickable surfaces use framework interactions. */
@Composable
fun AppSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Surface(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            shadowElevation = shadowElevation,
            interactionSource = interactionSource,
            content = content,
        )
    else
        Surface(
            onClick,
            modifier,
            enabled,
            shape,
            color,
            contentColor,
            tonalElevation,
            shadowElevation,
            border,
            interactionSource,
            content,
        )
}
