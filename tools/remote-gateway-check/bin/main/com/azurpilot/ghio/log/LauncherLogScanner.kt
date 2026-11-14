package com.azurpilot.ghio.log

import android.util.Log
import java.io.File

/**
 * 启动器日志目录（`log/`）的递归扫描：app 滚动日志、`proot/session.log`、crash 下的 txt
 *
 * 与 `AppLogWriter.listFiles()` 是两回事：那个只认 app.log 滚动系列（purge 的范围），
 * 这个管「启动器日志」列表页要展示的全部；export/ 下的 zip 不匹配任何一条，天然排除
 *
 * Recursive scan of the launcher log directory (`log/`): the app rotation series,
 * `proot/session.log`, and txt files under crash/.
 *
 * Not the same as `AppLogWriter.listFiles()`: that one only knows the app.log rotation
 * series (the purge scope), this one covers everything the launcher-log list page shows;
 * zips under export/ match no rule and are excluded naturally.
 */
object LauncherLogScanner {

    /**
     * 扫描 [logDir] 并按 mtime 倒序返回；失败返回空列表
     *
     * Scans [logDir] and returns files newest-mtime first; returns an empty list on
     * failure.
     *
     * @param logDir 日志根目录，通常为 [AppPaths.LOG_DIR] / the log root, usually
     *   [AppPaths.LOG_DIR]
     * @return 全部挂在 [logDir] 下的文件，展示用相对路径 / files all under [logDir];
     *   display uses relative paths
     */
    fun scan(logDir: File): List<File> = runCatching {
        logDir.walkTopDown()
            .filter { it.isFile && include(it.relativeTo(logDir).invariantSeparatorsPath) }
            .sortedByDescending { it.lastModified() }
            .toList()
    }.getOrElse {
        Log.w(TAG, "Failed to scan launcher logs", it)
        emptyList()
    }

    /** 判定相对路径是否属于启动器日志展示范围 / Decides whether a relative path belongs to the launcher-log listing. */
    private fun include(relPath: String): Boolean = when {
        APP_LOG_PATTERN.matches(relPath) -> true
        relPath == SESSION_LOG -> true
        relPath.startsWith(CRASH_PREFIX) && relPath.endsWith(".txt") -> true
        else -> false
    }

    private const val TAG = "LauncherLogScanner"
    private const val SESSION_LOG = "proot/session.log"
    private const val CRASH_PREFIX = "crash/"

    /** 与 `AppLogWriter.FILE_PATTERN` 同一条：滚动系列改名要两边同改 */
    private val APP_LOG_PATTERN = Regex("""app(\.\d+)?\.log""")
}
