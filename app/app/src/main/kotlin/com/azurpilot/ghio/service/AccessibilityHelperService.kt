package com.azurpilot.ghio.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.keepalive.KeepAliveManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 支持音量键快捷方式，并在主屏模式下为设备桥提供截图、手势与前台窗口包名。
 *
 * 服务的连接与回调在主线程处理；设备桥只在服务已连接时调用系统自动化接口。
 *
 * Supports the volume-key shortcut and provides screenshots, gestures, and the foreground
 * window's package to the primary-display bridge. The main thread owns service lifecycle
 * and callbacks; the bridge only calls the automation APIs while the service is connected.
 */
class AccessibilityHelperService : AccessibilityService() {

    /** 两键最近一次按下时刻；0 表示尚未按下 / Each key's last down timestamp; 0 means not yet pressed. */
    private var volumeUpPressTime = 0L
    private var volumeDownPressTime = 0L

    /**
     * 一次组合按下只触发一次；两个键都抬起才复位
     *
     * One combo press fires once; reset only after both keys are up.
     */
    private var triggered = false

    /** 连上即报给保活管理器并置位 [isConnected] / Reports to the keep-alive manager and sets [isConnected] on connect. */
    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
        _isConnected.value = true
        Timber.d("Accessibility service connected")
        KeepAliveManager.getInstance()?.onAccessibilityConnected()
    }

    /**
     * 任何无障碍事件都当作存活心跳上报：守护只关心「服务还活着」，不关心事件内容
     *
     * Every accessibility event doubles as a liveness heartbeat: the daemon
     * only cares that the service is alive, never about the event content.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        KeepAliveManager.getInstance()?.onAccessibilityEvent()
    }

    override fun onInterrupt() = Unit

    /**
     * 音量组合键在这里拦；没有监听者时原样放行
     *
     * The volume combo is intercepted here; events pass through untouched when
     * nobody listens.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 没人监听就原样放行，别把音量键吞了
        if (onVolumeUpDownPressed.get() == null) return super.onKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> when (event.action) {
                KeyEvent.ACTION_DOWN -> if (recordAndCheck { volumeUpPressTime = it }) return true
                KeyEvent.ACTION_UP -> reset { volumeUpPressTime = 0L }
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> when (event.action) {
                KeyEvent.ACTION_DOWN -> if (recordAndCheck { volumeDownPressTime = it }) return true
                KeyEvent.ACTION_UP -> reset { volumeDownPressTime = 0L }
            }
        }
        return super.onKeyEvent(event)
    }

    /** 记下按下时刻并判定是否构成组合键 / Records the down timestamp and checks whether the combo is complete. */
    private inline fun recordAndCheck(record: (Long) -> Unit): Boolean {
        record(System.currentTimeMillis())
        return checkSimultaneousPress()
    }

    /** 抬起清零并复位触发位 / Clears the timestamp and resets the triggered flag on key-up. */
    private inline fun reset(clear: () -> Unit) {
        clear()
        triggered = false
    }

    /**
     * 两键按下时间差在容差内即算组合键
     *
     * 触发后一直返回 true 直到抬起：不然长按期间的重复事件会漏给系统，音量条会弹出来
     *
     * The combo counts when the two down timestamps sit within tolerance.
     *
     * After firing it keeps returning true until key-up: otherwise the repeat
     * events of a long press leak to the system and the volume slider pops up.
     */
    private fun checkSimultaneousPress(): Boolean {
        if (triggered) return true
        if (volumeUpPressTime <= 0L || volumeDownPressTime <= 0L) return false
        if (abs(volumeUpPressTime - volumeDownPressTime) >= SIMULTANEOUS_PRESS_THRESHOLD_MS) return false

        Timber.d("Volume up/down combo triggered")
        triggered = true
        onVolumeUpDownPressed.get()?.invoke()
        return true
    }

    /**
     * 断开即清 [isConnected] 并通知保活管理器 / Clears [isConnected] and notifies the keep-alive manager on disconnect.
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    /** 清理异常销毁的连接。 / Clears the connection on unexpected destruction. */
    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (connectedService !== this) return
        connectedService = null
        _isConnected.value = false
        Timber.d("Accessibility service disconnected")
        KeepAliveManager.getInstance()?.onAccessibilityDisconnected()
    }

    companion object {
        /** 两键按下时间差容差；超出即算两次独立按键 / The tolerance between the two down timestamps; beyond it they are two separate presses. */
        private const val SIMULTANEOUS_PRESS_THRESHOLD_MS = 300L

        /**
         * 写进 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 的组件名，代授时要用
         * 必须跟 applicationId 走：写死包名的话，分包出去的包会去启用一个不存在的组件
         *
         * The component id written into
         * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, used by the
         * grant-through-adb flow. It must follow applicationId: a hardcoded
         * package would make a repackaged build enable a nonexistent component.
         */
        val SERVICE_ID: String =
            BuildConfig.APPLICATION_ID + "/" + AccessibilityHelperService::class.java.name

        /**
         * 检查系统设置中无障碍服务是否已开启
         *
         * Checks whether the accessibility service is enabled in system settings.
         */
        fun isServiceEnabled(context: Context): Boolean {
            val contentResolver = context.contentResolver
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabledServices.contains(SERVICE_ID)
        }

        /**
         * 组合键回调，由 OverlayController 装卸；null 表示当前不需要拦截
         *
         * The combo callback, installed and removed by OverlayController; null
         * means no interception is wanted right now.
         */
        val onVolumeUpDownPressed = AtomicReference<(() -> Unit)?>()

        private val _isConnected = MutableStateFlow(false)

        /** 当前已连接的系统服务，可跨线程读取。 / The connected system service, readable across threads. */
        @Volatile
        internal var connectedService: AccessibilityHelperService? = null
            private set

        /**
         * 代授之后要等它变 true 才算真连上，系统绑定是异步的
         *
         * After a grant-through-adb, true is what really means connected — the
         * system binding is asynchronous.
         */
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }
}
