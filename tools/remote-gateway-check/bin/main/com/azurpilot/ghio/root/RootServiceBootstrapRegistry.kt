package com.azurpilot.ghio.root

import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * 在 app 主进程中管理 root 特权服务一次性 Binder 回传令牌。
 *
 * [RootServiceStarter] 先经 [register] 申请 token，再由
 * [RootServiceBootstrapProvider] 在 shell/root 调用者回传 Binder 时用 [attach] 原子消费它。
 * `ConcurrentHashMap` 允许 ContentProvider 的 Binder 线程与 app 协程并发操作；token 绝不
 * 可复用，以避免旧子进程接管新的连接请求。
 *
 * Manages one-time Binder-handoff tokens for the root privileged service in the app main process.
 *
 * [RootServiceStarter] first obtains a token through [register], then
 * [RootServiceBootstrapProvider] atomically consumes it through [attach] when a shell/root caller
 * returns the Binder. `ConcurrentHashMap` permits concurrent ContentProvider Binder threads and
 * app coroutines; tokens are never reusable so an old child cannot claim a new connection request.
 */
object RootServiceBootstrapRegistry {

    /** Provider authority 的应用包名后缀。 / Application-package suffix of the provider authority. */
    const val AUTHORITY_SUFFIX = ".root.bootstrap"

    /** Binder 回传的唯一 provider 方法名。 / Sole provider method name for the Binder handoff. */
    const val METHOD_ATTACH_REMOTE_SERVICE = "attachRemoteService"

    /** 回传握手使用的 Bundle 键。 / Bundle keys used by the return handshake. */
    const val KEY_TOKEN = "token"
    const val KEY_SERVICE_BINDER = "service_binder"
    const val KEY_APP_BINDER = "app_binder"
    const val KEY_APP_PID = "app_pid"

    // attach/remove 通过移除 pending deferred 原子消费 token，禁止旧子进程复用回传通道。
    private val pendingBinders = ConcurrentHashMap<String, CompletableDeferred<IBinder>>()

    // app 主进程死亡时该 Binder 死亡，供 root 子进程关闭服务。
    private val appLifecycleBinder = Binder()

    /**
     * 为新 root 子进程登记一个尚待回传的 token。
     *
     * 同一 token 若被错误复用会覆盖旧 deferred，因此调用方必须生成不可预测且唯一的 token。
     *
     * Registers a token awaiting one root-child Binder handoff.
     *
     * Reusing a token would overwrite the older deferred, so callers must generate an unpredictable
     * unique token.
     */
    fun register(token: String): CompletableDeferred<IBinder> {
        return CompletableDeferred<IBinder>().also { pendingBinders[token] = it }
    }

    /**
     * 取消尚未完成的 token，防止超时或失败连接遗留等待者。
     *
     * Cancels a token that has not completed, preventing a timed-out or failed connection from
     * leaving a waiter behind.
     */
    fun unregister(token: String) {
        pendingBinders.remove(token)?.cancel()
    }

    /**
     * 原子消费 [token]、完成等待者并返回 app 生命周期 Binder。
     *
     * 未登记或已消费的 token 返回 null；调用方不可重试相同 token。
     *
     * Atomically consumes [token], completes its waiter, and returns the app lifecycle Binder.
     *
     * An unknown or already consumed token returns null; callers must not retry the same token.
     */
    fun attach(token: String, binder: IBinder): IBinder? {
        val deferred = pendingBinders.remove(token) ?: return null
        deferred.complete(binder)
        return appLifecycleBinder
    }
}
