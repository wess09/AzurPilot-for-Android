package com.azurpilot.ghio.privileged

import com.azurpilot.ghio.domain.RemoteBackend

/**
 * 后端授权状态变化的回调；唯一的真实实现在协调器里只做一次刷新
 *
 * Callback for a backend's authorization-state change; the only real
 * implementation triggers a refresh and nothing more.
 */
fun interface RemoteAccessStateListener {
    /**
     * @param backend 哪个后端变了 / which backend changed
     */
    fun onStateChanged(backend: RemoteBackend)
}

/**
 * 单个提权后端（Shizuku / Root）的可用性与授权边界
 *
 * 统一两个完全不同的授权世界：Shizuku 走 binder（`pingBinder` 探活、自己的
 * 授权弹窗、可常驻监听生死），Root 走 su（PATH 探测、su 提权对话框、无常驻
 * 回调）。[RemoteAccessCoordinator] 只认这个面，不关心底下是什么。
 *
 * 实现须线程安全：监听器集合会被 binder 回调与协程并发触碰。
 *
 * The availability-and-grant boundary of one privilege backend
 * (Shizuku / Root).
 *
 * Unifies two very different worlds: Shizuku talks binder (`pingBinder`
 * probes, its own grant dialog, persistent life/death listeners) while Root
 * talks su (PATH probing, the su prompt, no persistent callback).
 * [RemoteAccessCoordinator] only sees this interface and does not care what
 * sits underneath.
 *
 * Implementations must be thread-safe: the listener set is touched
 * concurrently by binder callbacks and coroutines.
 */
interface RemoteAccessPermissionBackend {
    /** 本实现代表哪个后端 / which backend this implementation represents */
    val backend: RemoteBackend

    /**
     * 后端此刻是否可用（Shizuku 服务活着 / su 可执行）
     *
     * Whether the backend is usable right now (Shizuku service alive / su
     * executable).
     */
    fun isAvailable(): Boolean

    /**
     * 是否已拿到授权；不可用时恒为 false
     *
     * Whether the grant is in hand; always false while unavailable.
     */
    fun isGranted(): Boolean

    /**
     * 发起授权流程；已授权时原样返回，不可用时不弹任何界面
     *
     * Starts the authorization flow; returns as-is when already granted, and
     * shows nothing when unavailable.
     *
     * @return 授权是否到手 / whether the grant was obtained
     */
    suspend fun requestPermission(): Boolean

    /**
     * 订阅后端状态变化（授权到手、binder 生死）
     *
     * Subscribes to backend state changes (grant obtained, binder life/death).
     */
    fun addStateListener(listener: RemoteAccessStateListener)

    /** 取消订阅 / unsubscribes */
    fun removeStateListener(listener: RemoteAccessStateListener)
}
