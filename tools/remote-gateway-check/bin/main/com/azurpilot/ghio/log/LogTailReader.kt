package com.azurpilot.ghio.log

import java.io.File
import java.io.RandomAccessFile

/**
 * 日志查看器的尾部加载读取：只读文件末尾一块，用户点「加载更早」再往前翻
 *
 * 整份读入的隐患：session.log 无上限，4MB×5 的 app.log 全进内存也曾是 OOM 隐患；
 * 看日志绝大多数时候只看最后那段，尾部加载用一块定长缓冲把内存钉死
 *
 * Tail-loading reader for the log viewers: reads only the end of the file, paging
 * backward when the user taps "load earlier".
 *
 * Why not read the whole file: session.log is unbounded, and pulling the full 4 MB × 5
 * app.log series into memory has been an OOM risk; people almost always look at just the
 * last stretch, so tail-loading pins memory to one fixed-size chunk.
 */
object LogTailReader {

    /** 每次读取的窗口；首行/末行不足一行都算正常，首行被砍掉（见下）/ The per-read window; a truncated first or last line is normal, the first line is cut (see below). */
    const val CHUNK_BYTES = 512 * 1024

    /**
     * 一次尾部读取的产物
     *
     * One tail-read chunk.
     *
     * @property text 解码后的正文 / the decoded text
     * @property fromOffset 本块起始的文件偏移；再往前翻时以此为终点 / the file offset
     *   where this chunk starts; the end point when paging further back
     * @property hasMore false = 已经读到文件头 / false = the file head has been reached
     */
    data class Chunk(
        val text: String,
        /** 本块起始的文件偏移；再往前翻时以此为终点 */
        val fromOffset: Long,
        /** false = 已经读到文件头 */
        val hasMore: Boolean,
    )

    /** 读文件末尾一块 / Reads the final chunk of the file. */
    fun readTail(file: File): Chunk = readChunk(file, file.length())

    /**
     * 读 [endExclusive) 之前最多 [CHUNK_BYTES] 字节；切口从第一个换行后开始，
     * UTF-8 半字符随之一起砍掉
     *
     * Reads at most [CHUNK_BYTES] bytes before [endExclusive); the cut starts after the
     * first newline, discarding any split UTF-8 sequence with it.
     */
    fun readChunk(file: File, endExclusive: Long): Chunk = RandomAccessFile(file, "r").use { raf ->
        val end = minOf(endExclusive, raf.length())
        val start = maxOf(0L, end - CHUNK_BYTES)
        raf.seek(start)
        val bytes = ByteArray((end - start).toInt())
        raf.readFully(bytes)
        val body = if (start > 0) {
            val newline = bytes.indexOf('\n'.code.toByte())
            // 整窗挤在一行里（超长行）就交空串，hasMore 仍 true，再往前翻一块
            if (newline in 0 until bytes.size - 1) bytes.copyOfRange(newline + 1, bytes.size) else ByteArray(0)
        } else {
            bytes
        }
        Chunk(text = String(body, Charsets.UTF_8), fromOffset = start, hasMore = start > 0)
    }
}
