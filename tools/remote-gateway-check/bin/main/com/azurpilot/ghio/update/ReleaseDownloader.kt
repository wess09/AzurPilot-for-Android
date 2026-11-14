package com.azurpilot.ghio.update

import com.azurpilot.download.DownloadCancelledException
import com.azurpilot.download.ParallelDownloader
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/**
 * 表示下载被主动中止（换源）；调用方据此把「换源重来」与「真失败」区分开。
 *
 * Thrown when a download is aborted on purpose (source switch); callers use it
 * to tell a deliberate restart apart from a genuine failure.
 */
class DownloadAborted : Exception("download aborted")

/**
 * 计算整文件 SHA-256；下载完成后由调用方统一校验。
 *
 * Whole-file SHA-256, verified by the caller once the download completes.
 */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 使用 OkHttp 下载 Release 资源，大文件自动采用四连接分段。
 *
 * 进程级单例；每次下载独立管理连接。镜像分段失败时自动回退单连接，大小与 SHA-256
 * 由调用方在完成后统一校验。阻塞 API 在 IO 线程调用。
 *
 * Downloads release assets through OkHttp, using four parallel connections for large files.
 *
 * Process-wide singleton with independently managed calls per download. Mirrors failing segmented
 * transfers fall back to a single connection. Callers verify size and SHA-256 after completion.
 * Invoke the blocking API on an IO thread.
 */
object ReleaseDownloader {

    private val downloader = ParallelDownloader()

    /**
     * 阻塞下载 [url] 到 [target]（覆盖写）。
     *
     * - [shouldAbort] 每 100 ms 检查（换源）并取消活动请求，抛出 [DownloadAborted]。
     * - [totalBytes] 传清单里的大小供进度显示；未知传 -1，[onProgress] 收到的
     *   total 即该值
     *
     * Downloads [url] into [target] (overwriting), blocking the caller.
     *
     * - [shouldAbort] is polled every 100 ms (source switch), cancels active requests, and
     *   throws [DownloadAborted].
     * - Pass the manifest size as [totalBytes] for progress display, or -1
     *   when unknown; [onProgress] reports that same value as its total
     */
    fun download(
        url: String,
        target: File,
        shouldAbort: () -> Boolean = { false },
        totalBytes: Long = -1L,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        try {
            downloader.download(
                url = url,
                target = target,
                totalBytes = totalBytes,
                shouldAbort = shouldAbort,
                onFallback = { Timber.w(it, "Segmented download failed; retrying with one connection") },
                onProgress = onProgress,
            )
        } catch (_: DownloadCancelledException) {
            throw DownloadAborted()
        }
    }
}
