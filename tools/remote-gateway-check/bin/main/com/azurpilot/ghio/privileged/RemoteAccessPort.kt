package com.azurpilot.ghio.privileged

import com.azurpilot.ghio.domain.RemoteBackend
import kotlinx.coroutines.flow.StateFlow

/**
 * 汇总两个提权后端（Shizuku / Root）的可用性与授权状态；授权侧的端口
 *
 * 后端选择不在这里：本层不决定它存在哪，也不感知持久化（见 [RemoteAccessCoordinator]）。
 * 生产实现是 [RemoteAccessCoordinator]，由 Koin 显式注入，不做默认参数。
 *
 * Aggregates availability and grant state of both privilege backends
 * (Shizuku / Root); the authorization-side port.
 *
 * Backend selection does not live here: this layer neither decides where the
 * choice is stored nor touches persistence (see [RemoteAccessCoordinator]).
 * The production implementation is [RemoteAccessCoordinator], injected
 * explicitly by Koin with no default parameters.
 */
interface RemoteAccessPort {
    /** 最新快照；后端状态变化时更新 / latest snapshot; updated as backend states change */
    val state: StateFlow<RemoteAccessState>

    /**
     * 重读两个后端的可用性与授权，顺带返回新快照
     *
     * Re-reads availability and grant state of both backends and returns the
     * fresh snapshot.
     */
    fun refresh(): RemoteAccessState

    /**
     * 向指定后端发起授权；已授权直接返回 true，后端不可用返回 false
     *
     * Requests authorization from [backend]; returns true immediately when
     * already granted, false when the backend is unavailable.
     */
    suspend fun request(backend: RemoteBackend): Boolean
}
