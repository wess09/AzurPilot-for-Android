package com.azurpilot.ghio.ui.ksu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import com.azurpilot.ghio.ui.components.LocalActionIconTrigger
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.ui.components.AppSymbolIcon

/** 业务按钮使用两种主题的现成组件。 / Business buttons use existing themed components. */
@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var iconTrigger by remember { mutableIntStateOf(0) }
    val action = { iconTrigger++; onClick() }
    CompositionLocalProvider(LocalActionIconTrigger provides iconTrigger) {
        if (LocalUiMode.current == UiMode.Miuix)
            top.yukonga.miuix.kmp.basic.Button(
                onClick = action,
                modifier = modifier,
                enabled = enabled,
                colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(),
                content = {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppTextStyle provides
                            top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.button
                    ) {
                        content()
                    }
                },
            )
        else
            Button(
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

/** 次要动作复用原生 Miuix 按钮和 Material tonal button。 / Uses native secondary buttons. */
@Composable
fun AppTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var iconTrigger by remember { mutableIntStateOf(0) }
    val action = { iconTrigger++; onClick() }
    CompositionLocalProvider(LocalActionIconTrigger provides iconTrigger) {
        if (LocalUiMode.current == UiMode.Miuix)
            top.yukonga.miuix.kmp.basic.Button(
                onClick = action,
                modifier = modifier,
                enabled = enabled,
                content = {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppTextStyle provides
                            top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.button
                    ) {
                        content()
                    }
                },
            )
        else
            FilledTonalButton(
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

/** 输入状态仍由业务持有，外观交给框架。 / Business owns input state while frameworks render fields. */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    label: (@Composable () -> Unit)? = null,
    labelText: String = "",
    placeholder: (@Composable () -> Unit)? = null,
    placeholderText: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        val hasLabelText = labelText.isNotEmpty()
        val hasPlaceholderText = placeholderText.isNotEmpty()
        val miuixColors =
            if (isError) {
                top.yukonga.miuix.kmp.basic.TextFieldDefaults.textFieldColors(
                    borderColor = MaterialTheme.colorScheme.error,
                    labelColor = MaterialTheme.colorScheme.error,
                )
            } else {
                top.yukonga.miuix.kmp.basic.TextFieldDefaults.textFieldColors()
            }
        Column(modifier) {
            if (hasLabelText && hasPlaceholderText) {
                top.yukonga.miuix.kmp.basic.SmallTitle(labelText)
            } else if (label != null) {
                label()
            }
            val effectiveLabel =
                when {
                    hasPlaceholderText -> placeholderText
                    hasLabelText -> labelText
                    else -> ""
                }
            val useAsPlaceholder = hasPlaceholderText

            top.yukonga.miuix.kmp.basic.TextField(
                value = value,
                onValueChange = onValueChange,
                label = effectiveLabel,
                useLabelAsPlaceholder = useAsPlaceholder,
                modifier = Modifier.fillMaxWidth(),
                colors = miuixColors,
                enabled = enabled,
                readOnly = readOnly,
                textStyle = textStyle,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                singleLine = singleLine,
                maxLines = maxLines,
                minLines = minLines,
                visualTransformation = visualTransformation,
                interactionSource = interactionSource,
            )
            supportingText?.let {
                Box(modifier = Modifier.padding(start = 16.dp, top = 4.dp)) {
                    it()
                }
            }
        }
    } else
        OutlinedTextField(
            value,
            onValueChange,
            modifier,
            enabled,
            readOnly,
            textStyle,
            label ?: labelText.takeIf { it.isNotEmpty() }?.let { { AppText(it) } },
            placeholder ?: placeholderText.takeIf { it.isNotEmpty() }?.let { { AppText(it) } },
            leadingIcon,
            trailingIcon,
            prefix,
            suffix,
            supportingText,
            isError,
            visualTransformation,
            keyboardOptions,
            keyboardActions,
            singleLine,
            maxLines,
            minLines,
            interactionSource,
            shape,
            colors,
        )
}

/** 小动作使用对应主题的图标按钮。 / Small actions use themed icon buttons. */
@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.IconButton(onClick, modifier, enabled, content = content)
    else IconButton(onClick, modifier, enabled, colors, interactionSource, content = content)
}

/** 辅助按钮复用主题原生实现。 / Auxiliary buttons reuse native implementations. */
@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var iconTrigger by remember { mutableIntStateOf(0) }
    val action = { iconTrigger++; onClick() }
    CompositionLocalProvider(LocalActionIconTrigger provides iconTrigger) {
        if (LocalUiMode.current == UiMode.Miuix)
            top.yukonga.miuix.kmp.basic.Button(
                onClick = action,
                modifier = modifier,
                enabled = enabled,
                content = {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppTextStyle provides
                            top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.button
                    ) {
                        content()
                    }
                },
            )
        else
            OutlinedButton(
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
