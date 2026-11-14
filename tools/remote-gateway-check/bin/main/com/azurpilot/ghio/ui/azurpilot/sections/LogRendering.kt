package com.azurpilot.ghio.ui.azurpilot.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotLogEntry
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.theme.AzurPilotTheme
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 标准日志行：`INFO 11:15:39.621 │ 消息`，可选日期在时间之前
 *
 * 网关给的是一份**定宽控制台输出**：满屏 `═` 分隔线、两侧留白居中的横幅，原样当纯文本铺出来 会很难看——一百多个 `═` 会折成好几行，串成一片噪声，级别、时间、消息也全是同一个颜色。
 * 本文件的分类规则与 WebUI 的 `LogPanel` 一致：先认分隔线，再认带标题的分隔线， 再认这里的标准日志行，最后认居中的横幅，都不匹配才原样输出。
 *
 * The standard log line: `INFO 11:15:39.621 │ message`, with an optional date before the time.
 *
 * The gateway emits **fixed-width console output**: full screens of `═` rule lines and banners
 * centered with side padding. Rendered verbatim as plain text it looks terrible — a hundred-plus
 * `═` characters wrap into several lines of noise, and level, time, and message all share one
 * color. This file's classification matches the WebUI's `LogPanel`: rule lines first, then titled
 * rules, then the standard log line here, then centered banners, and a verbatim fallback when
 * nothing matches.
 */
private val LOG_LINE =
    Regex(
        """^([A-Z]{4,8})\s+(?:(\d{4}-\d{2}-\d{2})\s+)?(\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?)\s*│\s*([\s\S]*)$"""
    )

/** 带可选标题的分隔线：`═ 标题 ═` / A rule line with an optional title: `═ title ═`. */
private val RULE = Regex("""^[═─]{3,}\s*(.*?)\s*[═─]{3,}$""")

/** 纯分隔线（无标题） / A bare rule line with no title. */
private val PURE_RULE = Regex("""^[═─]{3,}$""")

/** Removes terminal color/control sequences before Compose lays out the text. */
private val ANSI_ESCAPE = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")

/** 两侧各留三个以上空格的居中文本 / Text centered with three-plus spaces on each side. */
private val CENTER_TITLE = Regex("""^\s{3,}(.*?)\s{3,}$""")

/**
 * 行内高亮目标
 *
 * 一条日志里真正需要一眼认出的就这几类：布尔值、`<<<设备指令>>>`、`[标签]`、括号、 路径、时间。整行同一个颜色时，这些信息要靠逐字读才能找到。
 *
 * The inline highlight targets.
 *
 * Only these classes of content need at-a-glance recognition in a log line: booleans, `<<<device
 * directives>>>`, `[tags]`, brackets, paths, and times. When the whole line is one color, finding
 * them means reading character by character. Capture groups are numbered; [highlight] styles by
 * group index.
 */
private val TOKEN =
    Regex(
        """(\b(?:True|False|None)\b)""" + // 1 布尔/None
            """|(<<<[\s\S]*?>>>)""" + // 2 设备指令
            """|(\[[a-zA-Z0-9_.\u4e00-\u9fff-]+])""" + // 3 [标签]
            """|([{}\[\]()])""" + // 4 括号
            """|((?:[a-zA-Z]:[/\\]|(?:\.{1,2}[/\\]|[/\\]))[\w.\-/\\]+)""" + // 5 路径
            """|(\b\d{2}:\d{2}:\d{2}(?:\.\d+)?\b)""" // 6 时间
    )

/**
 * 一行日志该怎么画
 *
 * How one log line is rendered.
 */
private sealed interface LogLineKind {
    /** 分隔线；`double` 表示 `═` 双线 / A rule line; `double` marks a `═` double rule. */
    data class Rule(val title: String?, val double: Boolean) : LogLineKind

    /** 标准日志行 / A standard log entry. */
    data class Entry(val level: String, val date: String?, val time: String, val message: String) :
        LogLineKind

    /** 居中横幅 / A centered banner. */
    data class Banner(val text: String) : LogLineKind

    /** 无法识别的原文 / Unrecognized text, rendered verbatim. */
    data class Raw(val text: String) : LogLineKind
}

/**
 * 行内高亮用的一套颜色：AnnotatedString 在非 composable 上下文里拼，颜色得先取出来
 *
 * The palette for inline highlighting: the AnnotatedString is built outside a composable context,
 * so the colors must be resolved up front.
 */
private class LogPalette(
    val body: Color,
    val muted: Color,
    val success: Color,
    val error: Color,
    val primary: Color,
    val tertiary: Color,
    val secondary: Color,
    val highlight: Color,
    val onHighlight: Color,
    val levelColors: Map<String, Color>,
    val defaultLevel: Color,
) {
    fun levelOf(level: String): Color = levelColors[level] ?: defaultLevel
}

/**
 * 取当期主题的调色板；只读组合，可在组合期直接调用 / Resolves the current theme's palette; a read-only combination,
 * callable during composition.
 */
@Composable
@ReadOnlyComposable
private fun palette(): LogPalette =
    LogPalette(
        body = MaterialTheme.colorScheme.onSurface,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        success = AzurPilotTheme.palette.success,
        error = MaterialTheme.colorScheme.error,
        primary = MaterialTheme.colorScheme.primary,
        tertiary = MaterialTheme.colorScheme.tertiary,
        secondary = MaterialTheme.colorScheme.secondary,
        highlight = MaterialTheme.colorScheme.primaryContainer,
        onHighlight = MaterialTheme.colorScheme.onPrimaryContainer,
        // 级别配色只在这里定义一次；日志页与工具页的日志板共用
        levelColors =
            mapOf(
                "DEBUG" to MaterialTheme.colorScheme.onSurfaceVariant,
                "INFO" to MaterialTheme.colorScheme.onSurface,
                "WARNING" to AzurPilotTheme.palette.warning,
                "ERROR" to MaterialTheme.colorScheme.error,
                "CRITICAL" to MaterialTheme.colorScheme.error,
            ),
        defaultLevel = MaterialTheme.colorScheme.onSurface,
    )

/**
 * 这一行是不是夹在两条 `═` 分隔线之间的横幅
 *
 * 网关的横幅是「两侧各留三个以上空格」的居中文本，但那也是普通行可能有的形状， 所以要看上下文：上下都是双线、自己既不是线也不是标准日志行，才当横幅。
 *
 * Whether this line is a banner sandwiched between two `═` rules.
 *
 * The gateway's banners are centered text with three-plus spaces of padding on each side — but that
 * is also a shape an ordinary line can have, so context decides: only when both neighbors are
 * double rules and the line itself is neither a rule nor a standard log entry does it count as a
 * banner.
 *
 * @param previous 上一行；首行为 null / the previous line; null for the first
 * @param entry 当前行 / the current line
 * @param next 下一行；末行为 null / the next line; null for the last
 * @return 是否按居中横幅渲染 / whether to render the line as a centered banner
 */
fun isLogBanner(
    previous: AzurPilotLogEntry?,
    entry: AzurPilotLogEntry,
    next: AzurPilotLogEntry?,
): Boolean {
    val text = entry.text.trim()
    if (text.isEmpty() || PURE_RULE.matches(text) || LOG_LINE.matches(entry.text)) return false
    val before = previous?.text?.trim()
    val after = next?.text?.trim()
    return before != null &&
        after != null &&
        PURE_RULE.matches(before) &&
        before.contains('═') &&
        PURE_RULE.matches(after) &&
        after.contains('═')
}

/**
 * 把一行日志归入 [LogLineKind] 之一；`centered` 来自相邻行的横幅判断 / Classifies a log line into one of [LogLineKind];
 * `centered` comes from the neighbor-based banner check.
 */
private fun classify(entry: AzurPilotLogEntry, centered: Boolean): LogLineKind {
    val raw = entry.text.replace(ANSI_ESCAPE, "").trimEnd('\r', '\n')
    val trimmed = raw.trim()

    if (PURE_RULE.matches(trimmed)) {
        return LogLineKind.Rule(title = null, double = trimmed.contains('═'))
    }
    RULE.matchEntire(trimmed)?.let { match ->
        val title = match.groupValues[1].trim()
        if (title.isNotEmpty()) {
            return LogLineKind.Rule(title = title, double = trimmed.contains('═'))
        }
    }
    LOG_LINE.matchEntire(raw)?.let { match ->
        return LogLineKind.Entry(
            level = match.groupValues[1],
            date = match.groupValues[2].ifEmpty { null },
            time = match.groupValues[3],
            message = match.groupValues[4].trimEnd(),
        )
    }
    val singleLine = !raw.contains('\n')
    val centerMatch = if (singleLine) CENTER_TITLE.matchEntire(raw) else null
    if (singleLine && trimmed.isNotEmpty() && (centered || centerMatch != null)) {
        return LogLineKind.Banner(trimmed)
    }
    return LogLineKind.Raw(raw.trimEnd())
}

/**
 * 一行日志；[search] 非空时命中处加底色
 *
 * One log line; when [search] is non-empty, hits get a highlight background.
 *
 * @param entry 日志行 / the log entry
 * @param modifier 应用于行的修饰符 / the modifier applied to the row
 * @param centered 相邻行判定为横幅时为 true / true when the neighbor check classified the line as a banner
 * @param search 搜索词，命中处加底色 / the search term; hits get a highlight background
 */
@Composable
fun ApLogRow(
    entry: AzurPilotLogEntry,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    search: String = "",
) {
    val colors = palette()
    when (val kind = classify(entry, centered)) {
        is LogLineKind.Rule -> LogRule(kind.title, kind.double, search, modifier)
        is LogLineKind.Entry -> {
            // 前缀（级别 / 时间 / 竖线）单独成段，消息走自己的高亮
            val prefix = buildAnnotatedString {
                withStyle(
                    SpanStyle(color = colors.levelOf(kind.level), fontWeight = FontWeight.Bold)
                ) {
                    append(kind.level)
                }
                append(" ")
                withStyle(SpanStyle(color = colors.muted)) {
                    kind.date?.let {
                        append(it)
                        append(" ")
                    }
                    append(kind.time)
                }
                append(" ")
                withStyle(SpanStyle(color = colors.muted)) { append("│") }
                append(" ")
            }
            Text(
                text = prefix + highlight(kind.message, search, colors),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.body,
                modifier = modifier.fillMaxWidth().padding(vertical = 1.dp),
            )
        }

        is LogLineKind.Banner ->
            Text(
                text = highlight(kind.text, search, colors),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.primary,
                textAlign = TextAlign.Center,
                modifier = modifier.fillMaxWidth().padding(vertical = AppTokens.Spacing.xs),
            )

        is LogLineKind.Raw ->
            Text(
                text = highlight(kind.text, search, colors),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.levelOf(entry.level),
                modifier = modifier.fillMaxWidth().padding(vertical = 1.dp),
            )
    }
}

/**
 * 只读的日志板：富渲染 + 自动沉底
 *
 * 给「需要顺带看一眼日志」的地方用（工具任务页）。要筛选、搜索、导出就用日志分区那一套。
 *
 * The read-only log board: rich rendering plus auto-scroll to the bottom.
 *
 * For spots that merely need logs glanced at (the tool-task page). Filtering, search, and export
 * belong to the full Logs section instead.
 *
 * @param entries 日志行，按时间正序 / the log entries, oldest first
 * @param modifier 应用于日志板的修饰符 / the modifier applied to the board
 * @param emptyHint 空态文案；缺省用通用文案 / the empty-state text; a generic one is used by default
 */
@Composable
fun ApLogBoard(
    entries: List<AzurPilotLogEntry>,
    modifier: Modifier = Modifier,
    emptyHint: String? = null,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(entries.size) { scrollState.scrollTo(scrollState.maxValue) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        border =
            BorderStroke(AppTokens.Separator.thickness, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(AppTokens.Spacing.md)
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = emptyHint ?: stringResource(R.string.ap_logs_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    ApLogRow(
                        entry = entry,
                        centered =
                            isLogBanner(
                                entries.getOrNull(index - 1),
                                entry,
                                entries.getOrNull(index + 1),
                            ),
                    )
                }
            }
        }
    }
}

/**
 * 分隔线
 *
 * 画成真的线而不是字符：一百多个 `═` 铺出来会折行，而且每行宽度还会随字号变化。 双层线用两像素、单层线用细线，与日志里 `═` / `─` 的语义对应。
 *
 * A rule line.
 *
 * Drawn as a real line instead of characters: a hundred-plus `═` glyphs wrap, and the line width
 * would shift with the font size anyway. Double rules use two pixels and single rules a hairline,
 * matching the `═` / `─` semantics in the log.
 */
@Composable
private fun LogRule(title: String?, double: Boolean, search: String, modifier: Modifier) {
    val colors = palette()
    val thickness = if (double) 2.dp else 1.dp
    val barColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = AppTokens.Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
    ) {
        Box(modifier = Modifier.weight(1f).height(thickness).background(barColor))
        if (title != null) {
            Text(
                text = highlight(title, search, colors),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(modifier = Modifier.weight(1f).height(thickness).background(barColor))
        }
    }
}

/**
 * 行内高亮 + 搜索命中底色
 *
 * Inline token highlighting plus a background on search hits.
 */
private fun highlight(text: String, search: String, colors: LogPalette): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val needle = search.trim().lowercase()
    return buildAnnotatedString {
        var index = 0
        TOKEN.findAll(text).forEach { match ->
            if (match.range.first > index)
                appendSegment(text.substring(index, match.range.first), needle, colors)
            val token = match.value
            val style =
                when {
                    match.groups[1] != null ->
                        SpanStyle(
                            color =
                                when (token) {
                                    "True" -> colors.success
                                    "False" -> colors.error
                                    else -> colors.muted
                                }
                        )

                    match.groups[2] != null ->
                        SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
                    match.groups[3] != null -> SpanStyle(color = colors.tertiary)
                    match.groups[4] != null -> SpanStyle(color = colors.muted)
                    match.groups[5] != null -> SpanStyle(color = colors.secondary)
                    else -> SpanStyle(color = colors.muted)
                }
            withStyle(style) { appendSegment(token, needle, colors) }
            index = match.range.last + 1
        }
        if (index < text.length) appendSegment(text.substring(index), needle, colors)
    }
}

/**
 * 片段本身还要再过一遍搜索命中，否则高亮会吃掉搜索底色
 *
 * Each segment is re-scanned for search hits; otherwise the token highlight would swallow the
 * search background.
 */
private fun AnnotatedString.Builder.appendSegment(
    segment: String,
    needle: String,
    colors: LogPalette,
) {
    if (needle.isEmpty()) {
        append(segment)
        return
    }
    val lower = segment.lowercase()
    var cursor = 0
    while (true) {
        val hit = lower.indexOf(needle, cursor)
        if (hit < 0) {
            append(segment.substring(cursor))
            return
        }
        append(segment.substring(cursor, hit))
        withStyle(SpanStyle(background = colors.highlight, color = colors.onHighlight)) {
            append(segment.substring(hit, hit + needle.length))
        }
        cursor = hit + needle.length
    }
}
