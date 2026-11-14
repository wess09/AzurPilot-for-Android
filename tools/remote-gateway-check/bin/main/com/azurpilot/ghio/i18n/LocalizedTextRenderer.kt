package com.azurpilot.ghio.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList

/**
 * 在 Activity 之外把 [UiText] 渲染成成品文本
 *
 * 不能直接拿 Application context 解析：API 33 起系统 LocaleManager 会把
 * per-app locale 铺到整个进程，32 及以下 appcompat 只改 Activity 的
 * Configuration，Application 那份仍是系统语言——落进日志文件的就成了用户
 * 没选的那一种。
 *
 * 按语言标签缓存：运行日志一行调一次，每次现建 Configuration context 太贵。
 *
 * Renders [UiText] into final text outside an Activity.
 *
 * The plain Application context cannot be used for resolution: since API 33
 * the system LocaleManager spreads the per-app locale across the whole
 * process, while on 32 and below appcompat rewrites only the Activity's
 * Configuration and the Application's copy stays on the system language —
 * what lands in the log file would then be a language the user never picked.
 *
 * The wrapped context is cached per language tag: runtime logging renders per
 * line, and building a Configuration context every time would be far too
 * costly.
 */
class LocalizedTextRenderer(private val base: Context) {

    private val lock = Any()
    private var cachedTag: String? = null
    private var cached: Context? = null

    /**
     * 用本地化后的 context 渲染 [UiText] 成品文本
     * Renders [UiText] into final text with the localized context.
     */
    fun render(text: UiText): String = text.resolve(localizedContext())

    /**
     * 取当前语言标签对应的包装 context，命中缓存直接复用（锁内完成）
     * Returns the context wrapped for the current locale tag, reusing the
     * cache on hit (under the lock).
     */
    private fun localizedContext(): Context = synchronized(lock) {
        val tag = AppLocales.currentTag()
        cached?.takeIf { cachedTag == tag }
            ?: build(tag).also {
                cachedTag = tag
                cached = it
            }
    }

    /**
     * 跟随系统时 [base] 那份本来就是对的，不必再包一层
     * When following the system, [base] is already correct and needs no
     * wrapping.
     */
    private fun build(tag: String?): Context {
        if (tag == null) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(config)
    }
}
