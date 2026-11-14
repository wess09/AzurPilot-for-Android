package com.azurpilot.ghio.privileged

import android.os.IBinder
import com.azurpilot.ghio.domain.RemoteBackend

/**
 * 单个后端拉起特权服务进程的连接器；[RemoteServiceManager] 按当前后端择一驱动
 *
 * 两条实现路径完全不同：Shizuku 侧用 `bindUserService` 托管服务进程（见
 * [ShizukuRemoteServiceConnector]）；Root 侧用 su 执行 `liblauncher.so`
 * （app_process 包装器）自拉一个 `:root_service` 进程，binder 经
 * [com.azurpilot.ghio.root.RootServiceBootstrapRegistry] 的 token 握手回投
 * （见 [RootRemoteServiceConnector]）。
 *
 * 回调可能来自 binder 线程或连接器自己的协程，管理器须自行串行化；
 * 每次 connect 必须有且只有一次成对的 disconnect。
 *
 * Spawns the privileged service process for one backend; the manager drives
 * exactly one connector per binding according to the configured backend.
 *
 * The two paths could not differ more: the Shizuku side hosts the service via
 * `bindUserService` (see [ShizukuRemoteServiceConnector]); the Root side runs
 * `liblauncher.so` (an app_process wrapper) under su to bring up a dedicated
 * `:root_service` process, with the binder handed back through the token
 * handshake in [com.azurpilot.ghio.root.RootServiceBootstrapRegistry] (see
 * [RootRemoteServiceConnector]).
 *
 * Callbacks may arrive on binder threads or the connector's own coroutines;
 * the manager must serialize them itself. Every connect is paired with
 * exactly one disconnect.
 */
interface RemoteServiceConnectorBackend {
    /** 本连接器服务哪个后端 / which backend this connector serves */
    val backend: RemoteBackend

    /**
     * 发起连接；成功走 [Callbacks.onConnected]，失败走 [Callbacks.onError]，
     * 服务进程消失走 [Callbacks.onDisconnected]
     *
     * Starts connecting; success lands in [Callbacks.onConnected], failures in
     * [Callbacks.onError], and the service process going away in
     * [Callbacks.onDisconnected].
     */
    fun connect(callbacks: Callbacks)

    /**
     * 拆掉当前连接；[currentBinder] 非空时实现方可顺带让服务进程退出
     *
     * Tears down the current connection; with a non-null [currentBinder] the
     * implementation may also end the service process.
     */
    fun disconnect(currentBinder: IBinder?)

    /**
     * 连接事件回调；可能从任意线程调用
     *
     * Connection event callbacks; may be invoked from any thread.
     */
    interface Callbacks {
        /** 拿到服务 binder / got the service binder */
        fun onConnected(backend: RemoteBackend, binder: IBinder)

        /** 服务进程断开 / the service process disconnected */
        fun onDisconnected(backend: RemoteBackend)

        /** 连接失败 / connecting failed */
        fun onError(backend: RemoteBackend, throwable: Throwable)
    }
}
