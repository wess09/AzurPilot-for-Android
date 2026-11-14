package com.azurpilot.ghio.ui.azurpilot.sections.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApEmptyState
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppText as Text

/**
 * 公告
 *
 * 正文是 Markdown。这里**不**引入 Markdown 渲染器：公告的实际内容是标题 + 段落 + 少量强调， 为它拉一个解析器加一份 XSS
 * 面不划算。做最小清洗（去掉标记符号）后按纯文本排版， 需要原样看的话右侧有「在浏览器打开」。
 *
 * The announcement page.
 *
 * The body is Markdown. A Markdown renderer is **not** introduced here: real announcements are a
 * title, paragraphs, and a little emphasis — pulling in a parser plus an XSS surface for that is
 * not worth it. Minimal scrubbing (stripping marker characters) and plain-text layout instead; the
 * "open in browser" action sits on the right for the verbatim view.
 *
 * @param repository 网关仓库 / the gateway repository
 */
@Composable
fun AnnouncementPage(repository: AzurPilotRepository) {
    val announcement by repository.announcement.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) { repository.refreshAnnouncement() }

    ApSectionColumn {
        AppCard(
            title = announcement?.title ?: stringResource(R.string.ap_title_announcement),
            collapsible = false,
            modifier = Modifier,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                Text(
                    text =
                        if (announcement != null) {
                            stringResource(R.string.ap_announcement_id, announcement!!.id.take(8))
                        } else {
                            stringResource(R.string.ap_waiting_data)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { repository.refreshAnnouncement(force = true) }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.ap_refresh),
                    )
                }
                announcement
                    ?.url
                    ?.takeIf { it.isNotBlank() }
                    ?.let { url ->
                        IconButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.ap_open_in_browser),
                            )
                        }
                    }
            }

            val body = announcement?.content.orEmpty()
            if (body.isBlank()) {
                ApEmptyState(
                    icon = Icons.Filled.Campaign,
                    title = stringResource(R.string.ap_announcement_empty),
                    hint = stringResource(R.string.ap_announcement_empty_hint),
                )
            } else {
                Text(
                    text = plainMarkdown(body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * 极简 Markdown 清洗：只处理公告里真的会出现的那几种标记
 *
 * 目标是「读起来不别扭」，不是解析 Markdown——标题符号、加粗星号、链接语法去掉即可。
 *
 * Minimal Markdown scrubbing: handles only the marker kinds that actually appear in announcements.
 *
 * The goal is "reads without friction", not parsing Markdown — heading marks, bold asterisks, and
 * link syntax get stripped, nothing more.
 *
 * @param source 公告原文 / the raw announcement body
 * @return 清洗后的纯文本 / the scrubbed plain text
 */
private fun plainMarkdown(source: String): String =
    source
        .lineSequence()
        .map { line ->
            line
                .trimStart('#', ' ')
                .replace(LINK, "$1")
                .replace(BOLD, "$1")
                .replace(ITALIC, "$1")
                .replace("`", "")
        }
        .joinToString("\n")
        .trim()

/** 链接语法：留文字、去地址 / Link syntax: keep the text, drop the address. */
private val LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")

/** 加粗星号 / Bold markers. */
private val BOLD = Regex("""\*\*([^*]+)\*\*""")

/** 斜体星号（不匹配加粗，避免吃掉 `**`） / Italic markers (not matching bold, so `**` survives). */
private val ITALIC = Regex("""(?<!\*)\*([^*]+)\*(?!\*)""")
