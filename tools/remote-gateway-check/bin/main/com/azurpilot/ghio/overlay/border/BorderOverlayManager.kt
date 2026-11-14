package com.azurpilot.ghio.overlay.border

import android.content.Context
import android.graphics.PixelFormat
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 边框视图的窗口挂载
 *
 * 不走 FloatingX：那个库管的是可拖拽的浮窗，而边框要的恰恰是**不可触摸、不可聚焦、
 * 铺满整屏**——直接用 WindowManager 更短也更可控
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限（TYPE_APPLICATION_OVERLAY）；[show] / [hide]
 * 为挂起函数，内部把 WindowManager 增删挪到主线程，调用协程可在任意调度器；
 * 同一时刻至多挂一个边框视图
 *
 * The window mount for the border view.
 *
 * Not via FloatingX: that library manages draggable floating windows, while
 * the border needs exactly the opposite — **untouchable, unfocusable,
 * full-screen** — so WindowManager directly is shorter and more controllable.
 *
 * Requires the SYSTEM_ALERT_WINDOW permission (TYPE_APPLICATION_OVERLAY);
 * [show] / [hide] are suspending and move the WindowManager add/remove onto
 * the main thread, while the calling coroutine may run on any dispatcher. At
 * most one border view is mounted at a time.
 */
class BorderOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: BorderOverlayView? = null
    private var currentStyle: BorderStyle = BorderStyle()

    /**
     * 显示边框；同款样式已挂时为 no-op，样式变了先拆再挂
     *
     * 挂载失败只记日志并复位内部状态，不向调用方抛异常
     *
     * Shows the border; a no-op when the same style is already mounted, and a
     * tear-down-then-remount when the style changed.
     *
     * A mount failure is only logged and the internal state reset, never
     * rethrown to the caller.
     *
     * @param style 边框样式 / The border style
     */
    suspend fun show(style: BorderStyle = BorderStyle()) {
        if (overlayView != null) {
            if (currentStyle == style) return
            hide()
        }
        currentStyle = style
        runCatching {
            val view = BorderOverlayView(context, style)
            withContext(Dispatchers.Main) { windowManager.addView(view, createLayoutParams()) }
            overlayView = view
            Timber.d("Run border shown")
        }.onFailure {
            Timber.e(it, "Failed to show run border")
            overlayView = null
        }
    }

    /**
     * 移除边框；未挂载时为 no-op，移除失败只记日志
     *
     * Removes the border; a no-op when nothing is mounted, removal failures
     * are only logged.
     */
    suspend fun hide() {
        val view = overlayView ?: return
        overlayView = null
        runCatching { withContext(Dispatchers.Main) { windowManager.removeView(view) } }
            .onFailure { Timber.e(it, "Failed to remove run border") }
    }

    /** 边框当前是否挂在屏上 / Whether the border is currently mounted on screen. */
    fun isShowing(): Boolean = overlayView != null

    /**
     * 不可聚焦 + 不可触摸：边框只是提示，任何触摸都要原样落到下面的目标应用
     * NO_LIMITS + SHORT_EDGES 让它盖到挖孔与圆角之外，否则边框会被状态栏区域截断
     *
     * Not focusable + not touchable: the border is a hint only, every touch
     * must land unchanged on the target app beneath.
     * NO_LIMITS + SHORT_EDGES let it extend past the cutout and rounded
     * corners, otherwise the border gets clipped by the status-bar area.
     */
    private fun createLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}
