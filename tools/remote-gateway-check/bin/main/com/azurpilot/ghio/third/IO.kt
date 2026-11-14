package com.azurpilot.ghio.third

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.constant.AndroidVersions

import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Scanner

/**
 * 基于文件描述符的底层 IO 工具（阻塞式，任意线程可用）
 *
 * 用 [Os.write] 直写 fd 而不是包一层流，供 socket/管道这类无流对象的场景；
 * 阻塞语义由调用方的线程模型决定。
 *
 * 从 scrcpy 服务端的 `third/IO.java` 移植（Apache-2.0, Genymobile/scrcpy）。
 *
 * Low-level, fd-based IO helpers (blocking; usable from any thread).
 *
 * Writes go through [Os.write] directly on the fd rather than wrapping a
 * stream, for sockets/pipes that have no stream object; the blocking semantics
 * are owned by the caller's threading model.
 *
 * Ported from scrcpy's server `third/IO.java` (Apache-2.0, Genymobile/scrcpy).
 */
object IO {

    /**
     * 执行一次 [Os.write]，EINTR 时原地重试（内部用）
     *
     * [Os.write] 被信号打断会抛 errno 为 EINTR 的 [ErrnoException]，这里原地重试
     * 而不是把中断当失败抛出；返回本次实际写入的字节数。
     *
     * Performs a single [Os.write], retrying in place on EINTR (internal).
     *
     * An [Os.write] interrupted by a signal throws an [ErrnoException] with
     * errno EINTR; retry in place instead of treating the interruption as a
     * failure. Returns the number of bytes actually written this round.
     */
    @Throws(IOException::class)
    private fun write(fd: FileDescriptor, from: ByteBuffer): Int {
        while (true) {
            try {
                return Os.write(fd, from)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EINTR) {
                    throw IOException(e)
                }
            }
        }
    }

    /**
     * 把 [from] 的剩余字节全部写入 [fd]
     *
     * API 23 以下 [Os.write] 不按预期推进 ByteBuffer 的 position（老平台怪癖，
     * 见 <https://github.com/Genymobile/scrcpy/issues/291>），须自行记录
     * position/remaining 手动推进；API 23+ 循环直写即可。
     *
     * Writes all remaining bytes of [from] into [fd].
     *
     * Below API 23, [Os.write] does not advance the ByteBuffer position as
     * expected (old-platform quirk, see
     * <https://github.com/Genymobile/scrcpy/issues/291>), so position and
     * remaining bytes are tracked and advanced manually; from API 23 on, a plain
     * write loop suffices.
     */
    @Throws(IOException::class)
    fun writeFully(fd: FileDescriptor, from: ByteBuffer) {
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_23_ANDROID_6_0) {
            while (from.hasRemaining()) {
                write(fd, from)
            }
        } else {
            var position = from.position()
            var remaining = from.remaining()
            while (remaining > 0) {
                val w = write(fd, from)
                if (BuildConfig.DEBUG && w < 0) {
                    // w should not be negative, since an exception is thrown on error
                    throw AssertionError("Os.write() returned a negative value (" + w + ")")
                }
                remaining -= w
                position += w
                from.position(position)
            }
        }
    }

    /**
     * 把字节数组区间全部写入 [fd] / Writes the given array range fully into [fd].
     *
     * @throws IOException 底层写失败 / the underlying write failed
     */
    @Throws(IOException::class)
    fun writeFully(fd: FileDescriptor, buffer: ByteArray, offset: Int, len: Int) {
        writeFully(fd, ByteBuffer.wrap(buffer, offset, len))
    }

    /**
     * 按 UTF-8 行读干 [inputStream]，每行以 `\n` 结尾拼接
     *
     * Reads [inputStream] to the end line by line (UTF-8), joining lines with a
     * trailing `\n`.
     */
    fun toString(inputStream: InputStream): String {
        val builder = StringBuilder()
        val scanner = Scanner(inputStream)
        while (scanner.hasNextLine()) {
            builder.append(scanner.nextLine()).append('\n')
        }
        return builder.toString()
    }

    /**
     * 判断 [e] 是否由对端断开（EPIPE）引发
     *
     * 对端已关闭时继续写会得到 EPIPE，属于可预期的"连接死亡"而非程序错误，
     * 上层据此静默收尾。
     *
     * Returns whether [e] was caused by a broken pipe (EPIPE).
     *
     * Writing after the peer closed yields EPIPE — an expected "connection died"
     * outcome rather than a program error, which callers use to shut down
     * quietly.
     */
    fun isBrokenPipe(e: IOException): Boolean {
        val cause = e.cause
        return cause is ErrnoException && cause.errno == OsConstants.EPIPE
    }

    /**
     * [isBrokenPipe] 的宽松重载：任何异常层层剥开判断 / Relaxed overload of [isBrokenPipe] that inspects any exception.
     */
    fun isBrokenPipe(e: Exception): Boolean {
        return e is IOException && isBrokenPipe(e as IOException)
    }
}
