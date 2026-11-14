package com.azurpilot.ghio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 收口后台协程调度器的进程级单例
 *
 * 组件内部直接引用，不为单测把 dispatcher 塞进构造参数；单测用
 * mockkObject(AppDispatchers) 替换为 TestDispatcher，驱动 testScheduler 控制协程时序
 *
 * Main 不收进来：UI / 前台 scope 走 Dispatchers.Main + setMain，那是另一套可测手段
 *
 * Process-level singleton funneling the dispatchers used by background
 * coroutines.
 *
 * Components reference it directly instead of taking dispatchers through
 * constructor parameters for testability; unit tests use
 * mockkObject(AppDispatchers) to swap in TestDispatchers and drive
 * testScheduler for coroutine timing.
 *
 * Main is deliberately excluded: UI and foreground scopes use
 * Dispatchers.Main + setMain, which is a separate, already-testable path.
 */
object AppDispatchers {
    /** IO 调度器，承载阻塞型 IO / The IO dispatcher for blocking I/O work. */
    val IO: CoroutineDispatcher get() = Dispatchers.IO

    /** Default 调度器，承载 CPU 密集型工作 / The Default dispatcher for CPU-bound work. */
    val Default: CoroutineDispatcher get() = Dispatchers.Default
}
