package com.azurpilot.ghio.remote.internal

import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import com.azurpilot.ghio.bridge.NativeBridgeLib
import com.azurpilot.ghio.third.Ln
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * 在特权进程提供按连接独占的共享内存截图，每帧只在 Unix socket 上传元数据。
 *
 * App 接受连接后通过 Binder 传入已连接 FD，避免 SELinux 拒绝跨域 connect/listen/accept。
 * 客户端线程为守护线程。客户端先 open 取得只读 FD，再逐次 screencap；
 * 帧在下一次请求前保持不变，客户端必须先复制成自有 RGB 图像才可再次请求。
 * native 帧槽只在 memcpy 期间持有，不取得输入锁。停止时关闭活动连接并释放映射。
 *
 * Provides connection-owned shared-memory capture in the privileged process, sending only
 * metadata over a Unix socket per frame. The app accepts and passes a connected FD through Binder
 * to avoid SELinux cross-domain connect/listen/accept restrictions. Client threads are daemons.
 * Clients open a read-only FD before sequential screencap calls. Pixels remain unchanged until
 * the next request, so clients copy into an owned RGB image before requesting again. Native
 * slots are held only for memcpy, without the input lock. Stopping closes clients and mappings.
 */
object SharedFrameServer {
    /** 抽象地址，无文件路径，不受 PRoot 路径重写影响。 / Abstract address, independent of PRoot paths. */
    const val SOCKET_NAME = "com.azurpilot.ghio.frames.v1"

    private const val MAX_CLIENTS = 8
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_LINE = 4096
    private const val IDLE_TIMEOUT_MS = 60_000
    private val clients = mutableSetOf<LocalSocket>()

    private var accepting = false

    /**
     * 按桥服务生命周期启用通道，可在任意线程调用。
     *
     * Enables the channel for the bridge lifetime, from any thread.
     */
    @Synchronized
    fun start() {
        accepting = NativeBridgeLib.LOADED
    }

    /**
     * 接管 App 已接受的连接；保留独立 FD，并在客户端线程退出时释放。可在 Binder 线程调用。
     *
     * Adopts an app-accepted connection, keeping an independent FD until the client thread exits.
     * May be called from a Binder thread.
     */
    @Synchronized
    fun open(descriptor: ParcelFileDescriptor) {
        check(accepting) { "shared capture unavailable" }
        check(clients.size < MAX_CLIENTS) { "too many capture clients" }
        val owned = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        val socket = try {
            // 此构造器只包裹已连接 FD，不重新连接；特权域允许使用隐藏框架入口。
            adoptConnectedSocket(owned.fileDescriptor)
        } catch (error: Exception) {
            owned.close()
            throw error
        }
        clients.add(socket)
        thread(isDaemon = true, name = "azurpilot-shared-client") {
            owned.use { serve(socket) }
        }
    }

    /**
     * 包裹已连接 FD，兼容旧系统的静态工厂和新系统的构造器；调用方负责关闭 FD。
     *
     * Wraps a connected FD using the legacy static factory or the newer constructor. The caller
     * owns FD closure. Runs only in the privileged process, where hidden API access is allowed.
     */
    fun adoptConnectedSocket(descriptor: FileDescriptor): LocalSocket {
        val constructor = try {
            LocalSocket::class.java.getDeclaredConstructor(FileDescriptor::class.java)
        } catch (_: NoSuchMethodException) {
            null
        }
        return constructor?.newInstance(descriptor)
            ?: LocalSocket::class.java.getDeclaredMethod(
                "createConnectedLocalSocket", FileDescriptor::class.java
            ).invoke(null, descriptor) as LocalSocket
    }

    /**
     * 幂等关闭监听和所有连接，唤醒阻塞读并让客户端线程释放各自内存。可在任意线程调用。
     *
     * Closes the listener and clients idempotently, waking blocked reads so client threads release
     * their own memory. May be called from any thread.
     */
    @Synchronized
    fun stop() {
        accepting = false
        // 包裹借用 FD 的 LocalSocket.close() 不关闭它，shutdown 才能唤醒客户端线程。
        clients.toList().forEach { runCatching { it.shutdownInput() }; runCatching { it.shutdownOutput() } }
    }

    /** 返回是否可协商共享内存；可在任意线程查询。 / Reports channel availability from any thread. */
    @Synchronized
    fun isRunning(): Boolean = accepting

    private fun serve(socket: LocalSocket) {
        var memory: SharedMemory? = null
        var mapping: ByteBuffer? = null
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = BufferedInputStream(socket.inputStream)
            val output = socket.outputStream
            while (true) {
                val line = ByteArrayOutputStream()
                while (true) {
                    val value = input.read()
                    if (value < 0) return
                    if (value == '\n'.code) break
                    if (line.size() >= MAX_LINE) throw IOException("request line too long")
                    line.write(value)
                }
                val request = JSONObject(line.toString(Charsets.UTF_8.name()))
                val response = JSONObject().put("id", request.opt("id") ?: JSONObject.NULL)
                var descriptor: ParcelFileDescriptor? = null
                try {
                    when (request.optString("method")) {
                        "open" -> {
                            check(memory == null) { "already open" }
                            check(request.getInt("version") == 1) { "unsupported version" }
                            val cfg = VirtualDisplayManager.getConfig()
                            val size = cfg.width.toLong() * cfg.height * 3
                            check(size in 1..MAX_BYTES.toLong()) { "invalid frame size" }
                            val region = SharedMemory.create("azurpilot-frame", size.toInt())
                            memory = region
                            mapping = region.mapReadWrite()
                            // 已有可写映射继续可写；传出的 FD 只能新建只读映射。
                            check(region.setProtect(OsConstants.PROT_READ)) { "protect failed" }
                            val fd = NativeBridgeLib.dupSharedMemoryFd(region)
                            check(fd >= 0) { "duplicate FD failed" }
                            descriptor = ParcelFileDescriptor.adoptFd(fd)
                            socket.setFileDescriptorsForSend(arrayOf(descriptor.fileDescriptor))
                            response.put("ok", true).put("version", 1).put("capacity", size)
                        }
                        "screencap" -> {
                            val buffer = checkNotNull(mapping) { "open required" }
                            val cfg = VirtualDisplayManager.getConfig()
                            if (cfg.width.toLong() * cfg.height * 3 > buffer.capacity()) {
                                response.put("ok", false).put("error", "reopen required")
                                    .put("reopen", true)
                            } else {
                                val frame = NativeBridgeLib.copyFrameBuffer(buffer)
                                if (frame == null) {
                                    response.put("ok", false).put("error", "no frame available")
                                } else {
                                    response.put("ok", true).put("width", frame[0])
                                        .put("height", frame[1]).put("channels", 3)
                                        .put("length", frame[2]).put("frames", frame[3])
                                        .put("transport", "shared_memory")
                                }
                            }
                        }
                        else -> response.put("ok", false).put("error", "unknown method")
                    }
                    // 完成 memcpy 后才发送响应；同连接的下次请求是下一次写入许可。
                    output.write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
                } finally {
                    if (descriptor != null) socket.setFileDescriptorsForSend(null)
                    descriptor?.close()
                }
            }
        } catch (error: Exception) {
            Ln.w("SharedFrameServer client closed: ${error.message}")
        } finally {
            runCatching { socket.close() }
            mapping?.let { SharedMemory.unmap(it) }
            memory?.close()
            synchronized(this) { clients.remove(socket) }
        }
    }
}
