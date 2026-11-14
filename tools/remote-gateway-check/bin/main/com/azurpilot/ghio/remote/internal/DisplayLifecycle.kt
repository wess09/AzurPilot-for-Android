package com.azurpilot.ghio.remote.internal

/**
 * 串行化特权进程的显示生命周期，避免两种显示模式争用同一个 native 采集器。
 *
 * Binder 线程和主屏尺寸监听线程共用此锁；创建、释放、重建和模式切换必须完整持锁。
 *
 * Serializes the privileged process's display lifecycle, preventing the two display modes
 * from competing for the same native capturer.
 *
 * Binder threads and the primary-size listener share this lock. Creation, release, rebuild,
 * and mode switches must hold it for the entire operation.
 */
internal val displayLifecycleLock = Any()
