package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber

/**
 * 双进程互拉守护：主进程端服务
 *
 * 运行于主进程（默认进程），与 `:daemon` 进程的 [KeepAliveDaemonService] 互相绑定并
 * 监听对方 Binder 死亡：守护进程一旦被杀，本服务通过 [KeepAliveDaemonService.start]
 * 重拉守护进程并重新绑定，反之守护进程也会在主进程死亡时重拉本侧，形成双向看门狗。
 *
 * onStartCommand 返回 START_STICKY，主进程被杀后服务由系统重建；绑定由 [bound] 去重，
 * 只在尚未持有时建立，断连或 Binder 死亡后才重新绑定。
 *
 * Main-process side of the dual-process watchdog.
 *
 * Runs in the main (default) process and mutually binds [KeepAliveDaemonService] in the
 * `:daemon` process, each side listening for the other's binder death: when the daemon
 * dies this service restarts it via [KeepAliveDaemonService.start] and rebinds; the
 * daemon likewise revives this side when the main process dies, forming a two-way
 * watchdog.
 *
 * onStartCommand returns START_STICKY, so the system recreates the service after the
 * main process is killed; the binding is deduplicated by [bound] and created only while
 * it is not held yet, rebinding after a disconnect or a binder death.
 */
class KeepAliveLocalService : Service() {

    private val binder = object : IKeepAliveDaemon.Stub() {
        override fun ping() {
            Timber.d("KeepAliveLocalService: Ping received from daemon process")
        }
    }

    /** 守护进程服务的远端句柄；断连 / 死亡时清空 / Remote handle to the daemon service; cleared on disconnect / death. */
    private var daemonService: IKeepAliveDaemon? = null

    /**
     * 是否已持有到守护进程的绑定；bindService 的连接只增不减，靠它去重
     *
     * 死亡回调在 Binder 线程触发，用 [Volatile] 保证可见性。
     *
     * Whether the binding to the daemon is currently held; bindService connections only
     * accumulate, so this is what deduplicates them.
     *
     * The death callback runs on a binder thread, hence [Volatile] for visibility.
     */
    @Volatile
    private var bound = false

    /**
     * 守护进程 Binder 死亡回调：立即重拉 [KeepAliveDaemonService] 并重新绑定
     *
     * Daemon binder death callback: immediately restarts [KeepAliveDaemonService]
     * and rebinds.
     */
    private val deathRecipient = IBinder.DeathRecipient {
        Timber.w("KeepAliveLocalService: Daemon process DIED! Resurrecting daemon process...")
        daemonService = null
        // Binder 死亡后系统可能仍持有旧绑定并在守护进程重建时自动复用，
        // 先释放再重绑，避免同一连接留下两条记录。
        releaseBinding()
        KeepAliveDaemonService.start(this@KeepAliveLocalService)
        bindDaemonService()
    }

    /**
     * 绑定回调：连接成功时挂上 [deathRecipient] 监听守护进程死亡；
     * 意外断连时立即重拉并重绑
     *
     * Binding callbacks: on connect, attaches [deathRecipient] to watch for daemon
     * death; on unexpected disconnect, immediately restarts and rebinds.
     */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Timber.d("KeepAliveLocalService: Connected to KeepAliveDaemonService")
            runCatching {
                service?.linkToDeath(deathRecipient, 0)
                daemonService = IKeepAliveDaemon.Stub.asInterface(service)
            }.onFailure {
                Timber.w(it, "KeepAliveLocalService: Failed to link to death of daemon service")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.w("KeepAliveLocalService: KeepAliveDaemonService disconnected")
            daemonService = null
            releaseBinding()
            KeepAliveDaemonService.start(this@KeepAliveLocalService)
            bindDaemonService()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveLocalService: Local service created in main process")
        bindDaemonService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bindDaemonService()
        return START_STICKY
    }

    /**
     * 以 BIND_AUTO_CREATE 绑定守护服务：绑定本身即可把 `:daemon` 进程拉起；
     * 已持有时直接返回，避免 ServiceConnection 累积
     *
     * Binds the daemon service with BIND_AUTO_CREATE: binding alone spawns the `:daemon`
     * process; returns early while the binding is already held so ServiceConnections do
     * not accumulate.
     */
    private fun bindDaemonService() {
        // 每次 onStartCommand 都无条件 bindService 会让连接只增不减，
        // 最终触发 AMS 的 "bindService exceeded max service connection number per process"。
        if (bound) return
        runCatching {
            val intent = Intent(this, KeepAliveDaemonService::class.java)
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            bound = false
            Timber.w(it, "KeepAliveLocalService: Failed to bind KeepAliveDaemonService")
        }
    }

    /** 释放当前绑定；未持有时安全 / Releases the current binding; safe when none is held. */
    private fun releaseBinding() {
        if (!bound) return
        bound = false
        // 服务已消亡时这条记录可能已被 AMS 回收，unbindService 会抛 IllegalArgumentException。
        runCatching { unbindService(connection) }
    }

    override fun onDestroy() {
        releaseBinding()
        super.onDestroy()
    }

    companion object {
        /** 启动主进程端守护服务；失败仅记录，如后台启动限制 / Starts the main-process watchdog service; failures such as background start limits are logged. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveLocalService::class.java)
                context.startService(intent)
            }.onFailure {
                Timber.w(it, "KeepAliveLocalService: Failed to start local service")
            }
        }

        /** 停止主进程端守护服务；未启动时亦安全 / Stops the main-process watchdog service; safe when not running. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveLocalService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveLocalService: Failed to stop local service")
            }
        }
    }
}
