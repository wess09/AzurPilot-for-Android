package com.azurpilot.ghio.log

import android.annotation.SuppressLint
import android.util.Log
import com.azurpilot.ghio.BuildConfig
import timber.log.Timber

/**
 * 三棵树的分工
 *
 * - [ShortTagDebugTree]  debug 下打全量到 logcat，tag 取类名
 * - [ReleaseTree]        release 下只放 W 以上进 logcat
 * - [FileLogTree]        两种构建都全量落盘
 *
 * 落盘那棵是重点：logcat 环形缓冲装不下一次长跑，而定时触发多半发生在没人看着的时候
 *
 * Division of labor across the three trees.
 *
 * - [ShortTagDebugTree]: logs everything to logcat in debug, tag from the class name
 * - [ReleaseTree]: in release, only WARN and above reach logcat
 * - [FileLogTree]: logs everything to disk in both build types
 *
 * The disk tree is the one that matters: logcat's ring buffer cannot span a long run,
 * and scheduled triggers mostly happen while nobody is watching.
 */

/**
 * debug 构建 logcat 树：剥包名生成短 tag
 *
 * Timber 默认拿调用处的完整类名当 tag，logcat 会截断
 * 这里剥掉包名与 Kotlin 文件类的 `Kt` 后缀，再按 23 字符上限截
 *
 * The debug-build logcat tree with short tags stripped of package names.
 *
 * Timber defaults to the full call-site class name as the tag, which logcat truncates;
 * this strips the package name and the Kotlin file-class `Kt` suffix, then trims to the
 * 23-character limit.
 */
class ShortTagDebugTree : Timber.DebugTree() {
    override fun createStackElementTag(element: StackTraceElement): String {
        val simple = element.className.substringAfterLast('.')
        val tag = when {
            // 顶层函数编成 XxxKt，匿名类与 lambda 编成 Xxx$1
            simple.endsWith("Kt") -> simple.dropLast(2)
            simple.contains('$') -> simple.substringBefore('$')
            else -> simple
        }
        return tag.take(MAX_TAG_LENGTH)
    }

    private companion object {
        /** logcat tag 的传统长度上限 / The traditional logcat tag length cap. */
        const val MAX_TAG_LENGTH = 23
    }
}

/**
 * release 构建的 logcat 树：压掉 D/I/V
 *
 * 正常运行的日志量没有价值，还会拖慢热路径
 *
 * The release-build logcat tree: suppresses D/I/V.
 *
 * Logs from normal operation carry no diagnostic value at that level and would slow the
 * hot path.
 */
class ReleaseTree : Timber.Tree() {

    /** 只放行 WARN 及以上 / Lets WARN and above through only. */
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.WARN

    @SuppressLint("LogNotTimber")
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        when (priority) {
            Log.WARN -> Log.w(tag, message, t)
            Log.ERROR -> Log.e(tag, message, t)
            Log.ASSERT -> Log.wtf(tag, message, t)
        }
    }
}

/**
 * 全量落盘树：只负责转交给 [AppLogWriter]，节流与轮转都在那边
 *
 * 继承 DebugTree 而不是 Tree：tag 的栈推导在 `DebugTree.getTag()` 里，
 * 裸 Tree 拿到的 tag 恒为 null，落盘出来每行都是「-」。覆盖 log 之后不会再写 logcat
 *
 * 全量落盘不设级别门槛：单份 4MB×5 滚动自带总量上限，落全量比让用户翻空日志强
 *
 * The full-to-disk tree: only forwards to [AppLogWriter]; throttling and rotation live
 * over there.
 *
 * Extends DebugTree rather than Tree: tag inference from the stack lives in
 * `DebugTree.getTag()` — a bare Tree always receives a null tag, so every persisted line
 * would read "-". Overriding log() also keeps it from writing logcat again.
 *
 * No level gate for the disk copy: the 4 MB × 5 rotation caps the total, and persisting
 * everything beats making the user scroll an empty log.
 */
class FileLogTree(
    private val writer: AppLogWriter,
) : Timber.DebugTree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        writer.submit(priority, tag, message, t)
    }
}

/**
 * 日志树装配器：按构建类型挂 logcat 树并常挂落盘树
 *
 * The log-tree installer: plants the logcat tree by build type and always the disk tree.
 */
class LogTreeHolder(
    private val writer: AppLogWriter,
) {

    /**
     * 重挂全部 Timber 树（debug 用 [ShortTagDebugTree]，release 用 [ReleaseTree]，
     * 另加 [FileLogTree] 落盘）；应在 Application 启动时调用一次
     *
     * Replants all Timber trees ([ShortTagDebugTree] in debug, [ReleaseTree] in release,
     * plus [FileLogTree] for disk); call once at Application startup.
     */
    fun setup() {
        Timber.uprootAll()
        Timber.plant(
            *arrayOf(
                if (BuildConfig.DEBUG) ShortTagDebugTree() else ReleaseTree(),
                FileLogTree(writer)
            )
        )
    }
}
