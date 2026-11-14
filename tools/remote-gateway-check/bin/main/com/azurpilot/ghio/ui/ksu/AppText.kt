package com.azurpilot.ghio.ui.ksu

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.TextUnit
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode

/** 嵌套标题和按钮的排版槽。 / Typography slot for nested headings and buttons. */
val LocalAppTextStyle = staticCompositionLocalOf<TextStyle?> { null }

/** 文本使用当前主题的内容色和排版上下文。 / Text inherits the active framework's colors and typography. */
@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle =
        LocalAppTextStyle.current
            ?: if (LocalUiMode.current == UiMode.Miuix)
                top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.main
            else androidx.compose.material3.LocalTextStyle.current,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout ?: {},
            style = style,
        )
    else
        androidx.compose.material3.Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout ?: {},
            style = style,
        )
}

/** 文本使用当前主题的内容色和排版上下文。 / Text inherits the active framework's colors and typography. */
@Composable
fun AppText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle =
        LocalAppTextStyle.current
            ?: if (LocalUiMode.current == UiMode.Miuix)
                top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.main
            else androidx.compose.material3.LocalTextStyle.current,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout ?: {},
            style = style,
            inlineContent = inlineContent,
        )
    else
        androidx.compose.material3.Text(
            text = text,
            modifier = modifier,
            color = color,
            fontSize = fontSize,
            fontStyle = fontStyle,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            letterSpacing = letterSpacing,
            textDecoration = textDecoration,
            textAlign = textAlign,
            lineHeight = lineHeight,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = onTextLayout ?: {},
            style = style,
            inlineContent = inlineContent,
        )
}

/** 图标继承对应框架的前景色。 / Icons inherit the active framework's content color. */
@Composable
fun AppIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color =
        if (LocalUiMode.current == UiMode.Miuix)
            top.yukonga.miuix.kmp.theme.LocalContentColor.current
        else androidx.compose.material3.LocalContentColor.current,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Icon(imageVector, contentDescription, modifier, tint)
    else androidx.compose.material3.Icon(imageVector, contentDescription, modifier, tint)
}

/** 图标继承对应框架的前景色。 / Icons inherit the active framework's content color. */
@Composable
fun AppIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color =
        if (LocalUiMode.current == UiMode.Miuix)
            top.yukonga.miuix.kmp.theme.LocalContentColor.current
        else androidx.compose.material3.LocalContentColor.current,
) {
    if (LocalUiMode.current == UiMode.Miuix)
        top.yukonga.miuix.kmp.basic.Icon(painter, contentDescription, modifier, tint)
    else androidx.compose.material3.Icon(painter, contentDescription, modifier, tint)
}
