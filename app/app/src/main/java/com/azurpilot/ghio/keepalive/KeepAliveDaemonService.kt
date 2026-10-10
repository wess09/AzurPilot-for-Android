package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber

/**
 * 双进程互拉守护：独立守护子进程端服务
 *
 * 运行于独立的 `:daemon` 进程（manifest `android:process=":daemon"`），与主进程的
 * [KeepAliveLocalService] 互相绑定并监听对方 Binder 死亡：主进程因内存压力或系统
 * 策略被杀导致 Binder 破裂（[deathRecipient] 或 onServiceDisconnected 触发）时，
 * 本守护进程立即启动主进程的 [KeepAliveStickyService] 并重新绑定，反之亦然（见对方
 * 类文档），形成双向看门狗。
 *
 * onStartCommand 返回 START_STICKY，守护进程自身被杀后同样由系统重建；绑定由 [bound]
 * 去重，只在尚未持有时建立，断连或 Binder 死亡后才重新绑定。
 *
 * Main side of the dual-process watchdog: independent daemon process service.
 *
 * Runs in its own `:daemon` process (manifest `android:process=":daemon"`) and mutually
 * binds [KeepAliveLocalService] in the main process, each side listening for the other's
 * binder death: when the main process is killed by memory pressure or system policy the
 * binder breaks ([deathRecipient] or onServiceDisconnected fires) and this daemon
 * immediately starts [KeepAliveStickyService] in the main process and rebinds; the main
 * process does the same in reverse (see its class doc), forming a two-way watchdog.
 *
 * onStartCommand returns START_STICKY, so the system rebuilds the daemon process too if
 * it is killed; the binding is deduplicated by [bound] and created only while it is not
 * held yet, rebinding after a disconnect or a binder death.
 */
class KeepAliveDaemonService : Service() {

    private val binder = object : IKeepAliveDaemon.Stub() {
        override fun ping() {
            Timber.d("KeepAliveDaemonService: Ping received from main process")
        }
    }

    /** 主进程服务的远端句柄；断连 / 死亡时清空 / Remote handle to the main-process service; cleared on disconnect / death. */
    private var localService: IKeepAliveDaemon? = null

    /**
     * 是否已持有到主进程服务的绑定；bindService 的连接只增不减，靠它去重
     *
     * 死亡回调在 Binder 线程触发，用 [Volatile] 保证可见性。
     *
     * Whether the binding to the main-process service is currently held; bindService
     * connections only accumulate, so this is what deduplicates them.
     *
     * The death callback runs on a binder thread, hence [Volatile] for visibility.
     */
    @Volatile
    private var bound = false

    /**
     * 主进程 Binder 死亡回调：立即重拉 [KeepAliveStickyService] 并重新绑定，
     * 借助 startService 带动主进程复活
     *
     * Main-process binder death callback: immediately restarts [KeepAliveStickyService]
     * and rebinds, reviving the main process via startService.
     */
    private val deathRecipient = IBinder.DeathRecipient {
        Timber.w("KeepAliveDaemonService: Main process DIED! Resurrecting main process...")
        localService = null
        // Binder 死亡后系统可能仍持有旧绑定并在主进程重建时自动复用，
        // 先释放再重绑，避免同一连接留下两条记录。
        releaseBinding()
        // 重新拉起主进程
        KeepAliveStickyService.start(this@KeepAliveDaemonService)
        bindLocalService()
    }

    /**
     * 绑定回调：连接成功时挂上 [deathRecipient] 监听主进程死亡；
     * 意外断连时立即重拉并重绑
     *
     * Binding callbacks: on connect, attaches [deathRecipient] to watch for main-process
     * death; on unexpected disconnect, immediately restarts and rebinds.
     */
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Timber.d("KeepAliveDaemonService: Connected to KeepAliveLocalService")
            runCatching {
                service?.linkToDeath(deathRecipient, 0)
                localService = IKeepAliveDaemon.Stub.asInterface(service)
            }.onFailure {
                Timber.w(it, "KeepAliveDaemonService: Failed to link to death of main service")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.w("KeepAliveDaemonService: KeepAliveLocalService disconnected unexpectedly")
            localService = null
            releaseBinding()
            KeepAliveStickyService.start(this@KeepAliveDaemonService)
            bindLocalService()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveDaemonService: Daemon service created in process ${android.os.Process.myPid()}")
        bindLocalService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bindLocalService()
        return START_STICKY
    }

    /**
     * 以 BIND_AUTO_CREATE 绑定主进程服务：绑定本身即可把主进程服务拉起；
     * 已持有时直接返回，避免 ServiceConnection 累积
     *
     * Binds the main-process service with BIND_AUTO_CREATE: binding alone creates the
     * main-process service; returns early while the binding is already held so
     * ServiceConnections do not accumulate.
     */
    private fun bindLocalService() {
        // 每次 onStartCommand 都无条件 bindService 会让连接只增不减，
        // 最终触发 AMS 的 "bindService exceeded max service connection number per process"。
        if (bound) return
        runCatching {
            val intent = Intent(this, KeepAliveLocalService::class.java)
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure {
            bound = false
            Timber.w(it, "KeepAliveDaemonService: Failed to bind KeepAliveLocalService")
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
        /** 启动守护服务（带动 `:daemon` 进程）；失败仅记录，如后台启动限制 / Starts the daemon service (spawning the `:daemon` process); failures such as background start limits are logged. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, KeepAliveDaemonService::class.java)
                context.startService(intent)
            }.onFailure {
                Timber.w(it, "KeepAliveDaemonService: Failed to start daemon service")
            }
        }

        /** 停止守护服务；未启动时亦安全 / Stops the daemon service; safe when not running. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveDaemonService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveDaemonService: Failed to stop daemon service")
            }
        }
    }
}
