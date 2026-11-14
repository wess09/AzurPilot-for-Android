package com.azurpilot.ghio.ocr

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files

/**
 * 保留 CPU 会话的私有模型路径，直到 LiteRT 模型关闭；只在工作线程使用。
 *
 * 每次创建独立文件，不复用其他版本或尺寸的修补结果。跨进程目录锁保护创建与清理，
 * 独立租约文件锁保护存活会话；进程退出后，下次创建回收残留文件。
 *
 * Keeps a private CPU model path until the LiteRT model closes; used on worker threads only.
 * Each creation uses a separate file, never reusing another version or shape's patches.
 * A cross-process directory lock protects creation and cleanup, while a separate lease protects
 * live sessions. The next creation reclaims files left by a terminated process.
 */
internal class OcrCpuModelFile private constructor(
    /** LiteRT 在会话存活期间可重新读取的路径。 / Path LiteRT may reread during the session. */
    val file: File,
    private val channel: FileChannel,
    private val lease: FileLock,
) : AutoCloseable {
    private val leaseFile = File("${file.absolutePath}.lease")

    /** 删除模型已不再使用的临时文件；可重复调用。 / Deletes an unused model file idempotently. */
    @Synchronized
    override fun close() = synchronized(Companion) {
        try {
            if (lease.isValid) lease.release()
        } finally {
            try {
                channel.close()
            } finally {
                try {
                    try {
                        Files.deleteIfExists(file.toPath())
                    } finally {
                        Files.deleteIfExists(leaseFile.toPath())
                    }
                } finally {
                    activeFiles.remove(file.absolutePath)
                }
            }
        }
    }

    companion object {
        // POSIX 文件锁会在同进程关闭租约的任意 fd 时释放，不能打开存活租约做探测。
        private val activeFiles = mutableSetOf<String>()

        /**
         * 完整写入修补字节后返回持有文件锁的路径；失败时关闭并删除半成品。
         *
         * Returns a leased path after writing all patched bytes; closes and deletes partial
         * files on failure. Same-process synchronization complements the OS directory lock.
         */
        @Synchronized
        fun create(cacheDirectory: File, identity: String, data: ByteBuffer): OcrCpuModelFile {
            require(Regex("[a-f0-9]{64}").matches(identity))
            require(data.remaining() in 1..256 * 1024 * 1024)
            val directory = File(cacheDirectory, "ocr-cpu-sessions")
            check(directory.isDirectory || directory.mkdirs()) { "Could not create CPU model directory" }
            RandomAccessFile(File(directory, ".lock"), "rw").channel.use { gate ->
                gate.lock().use {
                    directory.listFiles().orEmpty().filter {
                        it.name.endsWith(".tflite") || it.name.endsWith(".tflite.lease")
                    }.map { File(it.absolutePath.removeSuffix(".lease")) }.distinct().filter {
                        it.absolutePath !in activeFiles
                    }.forEach { stale ->
                        val staleLease = File("${stale.absolutePath}.lease")
                        val unused = RandomAccessFile(staleLease, "rw").channel.use { candidate ->
                            val lock = try {
                                candidate.tryLock()
                            } catch (_: OverlappingFileLockException) {
                                null
                            }
                            lock?.use { true } ?: false
                        }
                        if (unused) {
                            Files.deleteIfExists(stale.toPath())
                            Files.deleteIfExists(staleLease.toPath())
                        }
                    }
                    val file = File.createTempFile("$identity-", ".tflite", directory)
                    var channel: FileChannel? = null
                    try {
                        // LiteRT 可能关闭自己打开的模型 fd；租约必须在另一个 inode 上。
                        channel = RandomAccessFile(File("${file.absolutePath}.lease"), "rw").channel
                        val lease = channel.lock()
                        val bytes = data.duplicate()
                        file.outputStream().channel.use { output ->
                            while (bytes.hasRemaining()) check(output.write(bytes) > 0) { "Could not write CPU model" }
                        }
                        activeFiles.add(file.absolutePath)
                        return OcrCpuModelFile(file, channel, lease)
                    } catch (error: Throwable) {
                        activeFiles.remove(file.absolutePath)
                        runCatching { channel?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                        runCatching { Files.deleteIfExists(file.toPath()) }.exceptionOrNull()?.let(error::addSuppressed)
                        runCatching { Files.deleteIfExists(File("${file.absolutePath}.lease").toPath()) }
                            .exceptionOrNull()?.let(error::addSuppressed)
                        throw error
                    }
                }
            }
        }
    }
}

/**
 * 标记 CPU 初始化失败，避免 AP 将无法由重启游戏修复的错误当作游戏异常。
 *
 * Marks CPU initialization failures that restarting the game cannot repair.
 */
internal class OcrCpuInitializationException(cause: Throwable) :
    IllegalStateException("LiteRT CPU initialization failed: ${cause.message}", cause)
