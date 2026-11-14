package com.azurpilot.ghio.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 延迟到展示那一刻才解析的文本
 *
 * 领域层、Resolver、Builder、ViewModel 都拿不到 Context，产出文案的唯一办法
 * 就是携带资源 id 与参数，等 UI 解析。切语言后 Activity 重建。
 *
 * Text resolved only at the moment of display.
 *
 * The domain layer, resolvers, builders, and ViewModels hold no Context, so
 * the only way to produce copy is to carry resource ids and arguments and let
 * the UI resolve them. A locale switch recreates Activities.
 */
@Immutable
sealed interface UiText {
    /** 空文本，解析为空串 / Empty text, resolving to the empty string. */
    data object Empty : UiText

    /**
     * 已经是成品文本，**不参与本地化**
     *
     * 别直接构造，走 [uiTextFromProject] / [uiTextFromFramework] /
     * [uiTextFormatted]——这三个名字说清了「为什么不翻译」，而裸的
     * `Verbatim("…")` 看不出是有意还是漏了。单模块下 `internal` 挡不住任何人，
     * 靠 `UiTextBoundaryTest` 扫源码兜底
     *
     * Finished text, **never localized**.
     *
     * Do not construct it directly; go through [uiTextFromProject] /
     * [uiTextFromFramework] / [uiTextFormatted] — the names say why no
     * translation happens, while a bare `Verbatim("…")` cannot tell intent
     * from an oversight. In a single module `internal` fences nobody in, so
     * `UiTextBoundaryTest` scans the sources as a backstop.
     */
    data class Verbatim(val value: String) : UiText

    /**
     * 资源文本；[args] 里可以再放 UiText，解析时递归展开
     *
     * A resource string; [args] may themselves hold UiText, expanded
     * recursively at resolution time.
     */
    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<Any?> = emptyList(),
    ) : UiText

    /**
     * 复数资源文本；[count] 只用来选单复数形式，**不会自动进 [args]**——
     * 文案里要露出这个数就得再传一次
     *
     * 不能自动前置：`home_diagnostics_summary_with_errors` 与
     * `template_task_count` 这两条的选形数都不是第一个占位符，自动塞会当场
     * 对不上
     *
     * A plural resource; [count] only picks the plural form and is **not
     * automatically added to [args]** — pass it again if the copy shows the
     * number.
     *
     * It cannot be auto-prepended: for `home_diagnostics_summary_with_errors`
     * and `template_task_count` the selection count is not the first
     * placeholder, and auto-inserting would immediately mismatch.
     */
    data class Plural(
        @param:PluralsRes val resId: Int,
        val count: Int,
        val args: List<Any?> = emptyList(),
    ) : UiText

    /**
     * 顺序拼接若干文本；分隔符本身也是 [UiText]
     * Joins texts in order; the separator is itself a [UiText].
     */
    data class Joined(
        val parts: List<UiText>,
        val separator: UiText = Empty,
    ) : UiText
}

/**
 * 构造资源文本 / Builds a resource-backed [UiText].
 */
fun uiTextOf(@StringRes resId: Int, vararg args: Any?): UiText =
    UiText.Resource(resId = resId, args = args.toList())

/**
 * 构造复数资源文本 / Builds a plural-resource [UiText].
 */
fun uiTextPlural(@PluralsRes resId: Int, count: Int, vararg args: Any?): UiText =
    UiText.Plural(resId = resId, count = count, args = args.toList())

/**
 * PI 作者写的文案：task / option 的 label 与 description
 * Copy authored by PI: task/option labels and descriptions.
 */
fun uiTextFromProject(label: String?): UiText = verbatimOrEmpty(label)

/**
 * 任务执行层抛回的原文：错误信息、节点名（函数名沿自 AzurPilotApp 上游）
 * Raw strings handed back by the task-execution layer: error messages, node
 * names (the function name follows the AzurPilotApp upstream).
 */
fun uiTextFromFramework(raw: String?): UiText = verbatimOrEmpty(raw)

/**
 * java.time 或数值格式化的产物，本身已随 locale 变化，不需要再查资源
 * Output of java.time or numeric formatting, already locale-dependent, so no
 * further resource lookup is needed.
 */
fun uiTextFormatted(value: String?): UiText = verbatimOrEmpty(value)

/**
 * 在给定 context 下解析为成品文本；null 与 [UiText.Empty] 得到空串
 * Resolves into final text under the given context; null and [UiText.Empty]
 * yield the empty string.
 */
fun UiText?.resolve(context: Context): String = when (this) {
    null, UiText.Empty -> ""
    is UiText.Verbatim -> value
    is UiText.Resource -> context.getString(resId, *resolveArgs(context))
    is UiText.Plural -> context.resources.getQuantityString(resId, count, *resolveArgs(context))
    is UiText.Joined -> parts.joinToString(separator.resolve(context)) { it.resolve(context) }
}

/**
 * Compose 侧解析入口：读取 [LocalConfiguration] 订阅配置变化，语言切换时
 * 随重组重解析
 *
 * The Compose-side resolution: reads [LocalConfiguration] to subscribe to
 * configuration changes, so a locale switch re-resolves on recomposition.
 */
@Composable
fun UiText?.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}

private fun verbatimOrEmpty(value: String?): UiText =
    if (value.isNullOrBlank()) UiText.Empty else UiText.Verbatim(value)

private fun UiText.resolveArgs(context: Context): Array<Any?> = when (this) {
    is UiText.Resource -> args
    is UiText.Plural -> args
    else -> emptyList()
}.map { if (it is UiText) it.resolve(context) else it }.toTypedArray()
