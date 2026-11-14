package com.azurpilot.ghio.keepalive

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * CPU 防休眠唤醒锁
 *
 * 持有 [PowerManager.PARTIAL_WAKE_LOCK]（不带超时的无限期持有，非引用计数），确保
 * 屏幕关闭或后台挂机时 CPU 依然保持活跃运算，防止系统将应用进程及其后台线程冻结进
 * Deep Sleep / Doze，使 proot 运行时与自动化任务得以持续执行。
 *
 * 代价是息屏期间持续耗电——是否接受由保活开关统一取舍。
 *
 * CPU partial wake lock manager.
 *
 * Holds [PowerManager.PARTIAL_WAKE_LOCK] indefinitely (no timeout, non-reference-counted)
 * so the CPU remains active while the screen is off, preventing Android from freezing the
 * process and its background threads into Deep Sleep / Doze — keeping the proot runtime
 * and automation tasks running. The trade-off is sustained battery drain with the screen
 * off, accepted wholesale via the keep-alive toggle.
 */
class KeepAliveWakeLock(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AzurPilot:KeepAliveWakeLock").apply {
            setReferenceCounted(false)
        }

    private val _isHeld = MutableStateFlow(false)

    /** 唤醒锁当前是否持有 / Whether the wake lock is currently held. */
    val isHeld: StateFlow<Boolean> = _isHeld.asStateFlow()

    /**
     * 持有唤醒锁；已持有时为空操作。任意线程可调（[Synchronized] 与 [release] 互斥）
     *
     * Acquires the wake lock; a no-op when already held. Callable from any thread
     * ([Synchronized] mutually excludes [release]).
     */
    @Synchronized
    fun acquire() {
        try {
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
                _isHeld.value = true
                Timber.d("KeepAliveWakeLock: WakeLock acquired")
            }
        } catch (e: Exception) {
            Timber.e(e, "KeepAliveWakeLock: Failed to acquire wake lock")
        }
    }

    /**
     * 释放唤醒锁；未持有时为空操作
     *
     * Releases the wake lock; a no-op when not held.
     */
    @Synchronized
    fun release() {
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                _isHeld.value = false
                Timber.d("KeepAliveWakeLock: WakeLock released")
            }
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveWakeLock: Error while releasing wake lock")
        }
    }
}
