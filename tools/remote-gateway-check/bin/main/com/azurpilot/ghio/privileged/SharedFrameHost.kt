package com.azurpilot.ghio.privileged

import android.net.LocalServerSocket
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.remote.internal.SharedFrameServer
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * 在 App 进程接受 Runtime 截图连接，将已连接 FD 经 Binder 交给特权服务。
 *
 * 客户端和监听端均属于 App 域，避免跨域 connectto 被 SELinux 拒绝。仅建立连接时
 * 使用 Binder；像素及逐帧元数据由特权服务直接交给 Runtime。绑定死亡时关闭监听。
 * 进程级单例，生命周期方法串行，接受循环在守护线程执行。
 *
 * Accepts Runtime capture connections in the app process and hands connected FDs to the
 * privileged service through Binder. Both peers belong to the app domain, avoiding SELinux
 * connectto restrictions. Binder is used only at connection setup; the privileged service sends
 * pixels and per-frame metadata directly to Runtime. Binder death closes the listener. This
 * process singleton serializes lifecycle methods and accepts on a daemon thread.
 */
object SharedFrameHost {
    private var listener: LocalServerSocket? = null
    private var binder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    /**
     * 幂等连接当前服务；服务更换时释放旧监听。可在连接回调线程调用。
     *
     * Connects to the current service idempotently, releasing the old listener on replacement.
     * May be called from a connection callback thread.
     */
    @Synchronized
    fun start(service: RemoteService) {
        val target = service.asBinder()
        if (binder === target && listener != null) return
        stop()
        val socket = LocalServerSocket(SharedFrameServer.SOCKET_NAME)
        val death = IBinder.DeathRecipient { synchronized(this) { if (binder === target) stop() } }
        try {
            target.linkToDeath(death, 0)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
        binder = target
        deathRecipient = death
        listener = socket
        thread(isDaemon = true, name = "azurpilot-shared-host") {
            while (synchronized(this) { listener === socket }) {
                val client = try {
                    socket.accept()
                } catch (error: Exception) {
                    if (synchronized(this) { listener === socket }) {
                        Timber.w(error, "Shared-frame accept failed")
                    }
                    break
                }
                runCatching {
                    client.use {
                        // 只接入本 App 的 Runtime，不能让其它 UID 借用特权截图通道。
                        if (it.peerCredentials.uid != Process.myUid()) return@use
                        // shell 对 App 域 socket 没有 setopt 权限，交付前在 App 域设置超时。
                        it.soTimeout = 60_000
                        ParcelFileDescriptor.dup(it.fileDescriptor).use { descriptor ->
                            service.openSharedFrameChannel(descriptor)
                        }
                    }
                }.onFailure { Timber.w(it, "Shared-frame handoff failed") }
            }
        }
    }

    /**
     * 关闭监听并解除死亡回调，可在任意线程调用；客户端由特权服务负责关闭。
     *
     * Closes the listener and unlinks death notification from any thread. The privileged service
     * owns client shutdown.
     */
    @Synchronized
    fun stop() {
        runCatching { listener?.close() }
        listener = null
        deathRecipient?.let { runCatching { binder?.unlinkToDeath(it, 0) } }
        binder = null
        deathRecipient = null
    }
}
