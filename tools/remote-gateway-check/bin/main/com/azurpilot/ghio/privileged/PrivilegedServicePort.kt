package com.azurpilot.ghio.privileged

import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.domain.RemoteBackend
import kotlinx.coroutines.flow.StateFlow

/**
 * 管理与特权进程的连接；跨进程边界的端口
 *
 * 与 [RemoteAccessPort] 分工：那边管「有没有权限」，这边管「连没连上」——
 * 两者的失败原因与恢复手段都不一样，合成一个接口之后调用方分不清该重授还是该重连。
 *
 * 生产实现是 [RemoteServiceManager]（它持 binder 与 linkToDeath，本就该是单例），
 * 由 Koin 显式注入，不做默认参数：默认值挂生产实现会让接口
 * 看着可替换其实不可能替换。
 *
 * Manages the connection to the privileged process; the port across the
 * process boundary.
 *
 * Division of labor with [RemoteAccessPort]: that one answers "is the grant
 * in hand", this one "is the service connected" — their failure causes and
 * recovery paths differ, and merging them into one interface would leave
 * callers unable to tell whether to re-grant or re-connect.
 *
 * The production implementation is [RemoteServiceManager] (it owns the binder
 * and linkToDeath, so it is inherently a singleton), injected explicitly by
 * Koin with no default parameters: wiring a default to the production
 * implementation makes the interface look swappable while it is not.
 */
interface PrivilegedServicePort {

    /**
     * 连接态；binder 不出这一层（见 [PrivilegedServiceState]）
     *
     * Connection state; the binder never leaves this layer (see
     * [PrivilegedServiceState]).
     */
    val serviceState: StateFlow<PrivilegedServiceState>

    /**
     * 发起（异步）绑定；失败与超时最终收敛到 [PrivilegedServiceState.Error]
     *
     * Starts binding (asynchronous); failures and timeouts settle into
     * [PrivilegedServiceState.Error].
     */
    fun bind()

    /** 解绑；幂等 / unbinds; idempotent */
    fun unbind()

    /**
     * 当前绑定用的后端；LogcatService 要跟主服务同后端、同拉起方式，未绑定时为 null
     *
     * The backend of the current binding; LogcatService must share the main
     * service's backend and launch path. Null when unbound.
     */
    val currentBackend: RemoteBackend?

    /**
     * 已连接时给出服务面，否则 null；不触发绑定
     *
     * The service face when connected, else null; never triggers binding.
     */
    fun serviceOrNull(): RemoteService?

    /**
     * 先刷新授权、必要时发起授权与重绑，再把服务面交给 [action]
     *
     * Refreshes authorization, requests a grant and rebinds when needed, then
     * hands the service face to [action].
     */
    suspend fun <R> useService(action: suspend (RemoteService) -> R): R
}
