package com.azurpilot.ghio.ui.oobe

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.azurpilot.ghio.R

/**
 * 将系统和应用语言标签归一为向导选项，确保选择状态与配置摘要使用相同规则。
 *
 * Normalizes system and app locale tags to wizard choices so selection and summary agree.
 */
internal fun normalizeOobeLanguageTag(tag: String?): String? {
    val raw = tag?.replace('_', '-')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        raw.startsWith("zh") ->
            if (raw.contains("hant") || raw.endsWith("-tw") || raw.endsWith("-hk")) "zh-TW"
            else "zh-CN"
        else -> raw.substringBefore('-')
    }
}

/**
 * 在主线程返回语言自称，跟随系统时使用当前界面译文。
 *
 * Returns a language's native name on the main thread, localizing the follow-system option.
 */
@Composable
internal fun oobeLanguageName(tag: String?): String = when (normalizeOobeLanguageTag(tag)) {
    "zh-CN" -> "简体中文"
    "zh-TW" -> "繁體中文"
    "en" -> "English"
    "ja" -> "日本語"
    null -> stringResource(R.string.settings_follow_system)
    else -> tag.orEmpty()
}
