package com.azurpilot.ghio.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 收口外壳支持的语言范围：仅 en 与 zh-CN
 *
 * 外壳只带这两种资源，任何其它请求一律归到 zh-CN；PI 侧语言范围与此同步。
 *
 * Centralizes the shell's supported language range: en and zh-CN only.
 *
 * The shell ships only these two resources, so anything else folds into
 * zh-CN; PI's language range stays in sync with this.
 */
object AppLanguagePolicy {
    /**
     * 把请求的语言标签归一到项目支持的取值
     *
     * 取主语言小写比较：`en`（含 en-US 等）返回 `en`，其余（zh-TW、空白，以及
     * 未设 App 语言时回退的系统语言）一律 `zh-CN`。
     *
     * Normalizes a requested locale tag onto the project's supported values.
     *
     * Compares the lowercased primary language: `en` (including en-US and
     * friends) maps to `en`; everything else (zh-TW, blanks, and the
     * system-locale fallback when no in-app locale is set) maps to `zh-CN`.
     *
     * @param appLocaleTag 用户在 App 内选的语言标签，null / 空白表示跟随系统 /
     *   the in-app locale tag; null/blank means follow the system
     * @param systemLocaleTag 系统当前语言标签 / the current system locale tag
     * @return `en` 或 `zh-CN` / `en` or `zh-CN`
     */
    fun projectLocaleTag(appLocaleTag: String?, systemLocaleTag: String): String {
        val requested = appLocaleTag?.takeIf { it.isNotBlank() } ?: systemLocaleTag
        val language = requested.replace('_', '-').substringBefore('-').lowercase()
        return if (language == "en") "en" else "zh-CN"
    }
}

/**
 * 应用 per-app locale 的抽象，便于测试替换
 * Abstraction for applying a per-app locale, easing test substitution.
 */
fun interface LocaleController {
    /**
     * 应用语言标签；null 表示恢复跟随系统
     * Applies the locale tag; null restores follow-system.
     */
    fun apply(tag: String?)
}

/**
 * per-app locale 的事实来源，不进 DataStore
 *
 * API 33+ 由系统持久化；API 32 及以下交给 appcompat 的 autoStoreLocales。
 *
 * The source of truth for the per-app locale; never persisted to DataStore.
 *
 * On API 33+ the system persists it; on API 32 and below appcompat's
 * autoStoreLocales does.
 */
object AppLocales : LocaleController {

    /**
     * 当前 per-app locale 标签；null = 跟随系统
     * The current per-app locale tag; null means follow the system.
     */
    fun currentTag(): String? = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()

    /**
     * 当前归一到项目范围的标签（[AppLanguagePolicy.projectLocaleTag]）
     * The current tag normalized onto the project's range
     * ([AppLanguagePolicy.projectLocaleTag]).
     */
    fun currentProjectTag(): String = AppLanguagePolicy.projectLocaleTag(
        appLocaleTag = currentTag(),
        systemLocaleTag = Locale.getDefault().toLanguageTag(),
    )

    /**
     * 应用语言标签；null 恢复跟随系统，已启动 Activity 会重建
     * Applies the locale tag; null restores follow-system, and running
     * Activities are recreated.
     */
    override fun apply(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag),
        )
    }
}
