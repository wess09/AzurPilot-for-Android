package com.azurpilot.ghio.keepalive

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 1px 透明悬浮窗活动信号。
 *
 * 组件在获得 [android.provider.Settings.canDrawOverlays] 权限后向 [WindowManager] 请求一个不可触摸、
 * 不可聚焦的透明 1px 窗口。该窗口可作为前台可见性相关的尽力而为信号，但不保证固定的进程状态、
 * 优先级或内存回收豁免；具体结果随 Android 版本、OEM 窗口策略和设备状态变化。
 *
 * 1-pixel transparent overlay activity signal.
 *
 * With [android.provider.Settings.canDrawOverlays] permission, this component requests a non-touchable,
 * non-focusable transparent 1-pixel [WindowManager] window. The window can be a best-effort foreground
 * visibility signal, but it does not guarantee a fixed process state, priority, or exemption from memory
 * reclamation. Android version, OEM window policy, and device state determine the result.
 */
class KeepAlivePixelOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pixelView: View? = null

    private val _isAttached = MutableStateFlow(false)

    /** 浮窗当前是否已挂载到 WindowManager / Whether the overlay is currently attached to the WindowManager. */
    val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    /**
     * 检查是否具备悬浮窗权限
     *
     * Checks whether the SYSTEM_ALERT_WINDOW permission is granted.
     */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /**
     * 挂载 1px 浮窗；幂等，已挂载时直接返回
     *
     * 返回值仅代表权限具备且挂载请求已提交，不代表窗口已挂载成功——实际 addView 在
     * 主线程异步执行（已在主线程则同步），结果以 [isAttached] 为准
     *
     * Attaches the 1px overlay; idempotent, returns immediately when already attached.
     *
     * The return value only means the permission is granted and the attach request was
     * submitted, not that the window is on screen — the actual addView runs async on the
     * main thread (synchronously when already there); [isAttached] is the source of truth.
     *
     * @return 权限具备且请求已提交时 true，否则 false / true when the permission is
     *   granted and the request was submitted, false otherwise
     */
    fun attach(): Boolean {
        if (!canDrawOverlays()) {
            Timber.w("KeepAlivePixelOverlay: SYSTEM_ALERT_WINDOW permission not granted; cannot attach 1px overlay")
            return false
        }

        if (_isAttached.value && pixelView != null) {
            return true
        }

        runOnMainThread {
            if (pixelView != null) return@runOnMainThread

            try {
                val view = View(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                }

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }

                val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    windowType,
                    flags,
                    PixelFormat.TRANSPARENT,
                ).apply {
                    gravity = Gravity.START or Gravity.TOP
                    x = 0
                    y = 0
                    alpha = 0.0f // 完全隐形
                }

                windowManager.addView(view, params)
                pixelView = view
                _isAttached.value = true
                Timber.d("KeepAlivePixelOverlay: 1px overlay attached successfully")
            } catch (e: Exception) {
                Timber.e(e, "KeepAlivePixelOverlay: Failed to add 1px overlay view to WindowManager")
                detach()
            }
        }
        return true
    }

    /**
     * 移除 1px 浮窗；幂等，未挂载时为空操作，实际移除切到主线程执行
     *
     * Detaches the 1px overlay; idempotent and a no-op when not attached, with the
     * actual removal dispatched to the main thread.
     */
    fun detach() {
        runOnMainThread {
            val view = pixelView
            pixelView = null
            _isAttached.value = false
            if (view != null) {
                try {
                    windowManager.removeViewImmediate(view)
                    Timber.d("KeepAlivePixelOverlay: 1px overlay detached")
                } catch (e: Exception) {
                    Timber.w(e, "KeepAlivePixelOverlay: Failed to remove 1px overlay view")
                }
            }
        }
    }

    /** 已在主线程则直接执行，否则 post 到 [mainHandler] / Runs inline when already on the main thread, otherwise posts to [mainHandler]. */
    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
