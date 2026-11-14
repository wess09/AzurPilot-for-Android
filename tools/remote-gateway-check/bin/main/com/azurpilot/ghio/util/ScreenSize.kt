package com.azurpilot.ghio.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlin.math.abs

/**
 * 主屏尺寸的读取与 16:9 换算（对齐 参考实现 的 `Misc` 同名几个函数）
 *
 * 与后台虚拟屏无关：那边是自己建的屏，尺寸由外壳指定（`DefaultDisplayConfig`）；
 * 这里读的是物理主屏，前台模式在上面直接采集与注入。
 *
 * 调用方是进程级组件（`DisplaySizeController`），手上只有 Application context，
 * 所以取屏幕的方式受限——见 [physical] 上的说明。
 *
 * Reads the primary display's size and derives 16:9 values (aligned with the
 * reference implementation's `Misc` functions of the same names).
 *
 * Unrelated to the background virtual display: that screen is created by the
 * shell itself and sized via `DefaultDisplayConfig`; this object reads the
 * physical primary display, which foreground mode captures and injects on
 * directly.
 *
 * Callers are process-level components (`DisplaySizeController`) holding only
 * an Application context, which constrains how the display can be obtained —
 * see the notes on [physical].
 */
object ScreenSize {

    /** 长短边比与 16:9 的相对偏差上限；面板布局按 16:9 摆，差一点点不至于错位 / Relative deviation cap between the long/short-side ratio and 16:9; the panel layout assumes 16:9 and a hair of drift still lands correctly. */
    private const val ASPECT_TOLERANCE = 0.02f

    private const val UNIT_W = 16
    private const val UNIT_H = 9

    /** 16:9 换算的下限；再低就不够放下面板本身 / Lower bounds for the 16:9 fit; anything smaller cannot host the panel itself. */
    private const val MIN_LONG_SIDE = 1280
    private const val MIN_SHORT_SIDE = 720

    /**
     * 当前**生效**的主屏尺寸，含 `setForcedDisplaySize` 改过之后的值
     *
     * 不能用 `context.resources.displayMetrics`：Application 那份不反映强改后的
     * 尺寸（实测 Android 9 上一直返回物理分辨率减系统栏），校验会永远判错
     *
     * The primary display's currently **effective** size, including any
     * `setForcedDisplaySize` override.
     *
     * `context.resources.displayMetrics` cannot be used: the Application copy
     * does not reflect forced sizes (on Android 9 it consistently reported the
     * physical resolution minus system bars in testing), so validation would
     * misjudge forever.
     */
    fun current(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    /**
     * 出厂物理尺寸，**不受** `setForcedDisplaySize` 影响——它是硬件 mode 的分辨率
     *
     * 算 16:9 目标值要用它：拿改过的当前尺寸去算，连点两次「修改分辨率」会一路
     * 缩下去。
     *
     * 经 [DisplayManager] 取 [Display] 而不是 `context.display`：后者会校验调用方
     * 是不是 visual context（Activity 或 `createWindowContext` 造的），拿
     * Application context 调直接抛 `UnsupportedOperationException: Tried to obtain
     * display from a Context not associated with one`。参考实现 那边用
     * `context.display` 是因为它从 UI 把 Activity context 传了下来，而这里的调用方
     * 在进程级，不该持有 Activity。
     *
     * 取不到屏幕时返回 0×0，由 [fit16x9] 判成「换算无意义」
     *
     * The factory physical size, **unaffected** by `setForcedDisplaySize` — it
     * is the hardware mode's resolution.
     *
     * The 16:9 target must be derived from it: deriving from the modified
     * current size shrinks the display a bit further on every "change
     * resolution" tap.
     *
     * The [Display] comes via [DisplayManager] rather than `context.display`:
     * the latter verifies the caller is a visual context (an Activity or
     * something built by `createWindowContext`) and throws
     * `UnsupportedOperationException: Tried to obtain display from a Context not
     * associated with one` for an Application context. The reference
     * implementation gets away with `context.display` because it passes an
     * Activity context down from the UI; the callers here are process-level
     * and must not hold an Activity.
     *
     * Returns 0×0 when the display cannot be obtained, which [fit16x9] treats
     * as "fitting is meaningless".
     */
    fun physical(context: Context): Pair<Int, Int> {
        val mode = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.mode
            ?: return 0 to 0
        return mode.physicalWidth to mode.physicalHeight
    }

    /**
     * 按长短边比判，与横竖屏无关
     * Judges by the long/short-side ratio, independent of orientation.
     */
    fun isAspect16x9(width: Int, height: Int): Boolean {
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        if (shortSide <= 0) return false
        val target = UNIT_W.toFloat() / UNIT_H
        return abs(longSide.toFloat() / shortSide - target) <= target * ASPECT_TOLERANCE
    }

    /**
     * 物理尺寸内能放下的最大 16:9，方向跟随原屏
     *
     * 取 16 与 9 的整倍数而不是按边裁：非整倍数的宽高在部分 ROM 上会被
     * SurfaceFlinger 再对齐一次，落到的实际尺寸与请求的对不上。
     *
     * The largest 16:9 size that fits within the physical dimensions,
     * following the source display's orientation.
     *
     * Multiples of 16 and 9 are used instead of cropping to the edge: non-
     * multiple sizes get re-aligned once more by SurfaceFlinger on some ROMs,
     * and the resulting size then misses the request.
     *
     * @return null 表示屏幕太小或读不到物理尺寸，换算无意义 / null when the
     *   screen is too small or the physical size is unreadable, making the
     *   fit meaningless
     */
    fun fit16x9(physicalWidth: Int, physicalHeight: Int): Pair<Int, Int>? {
        if (physicalWidth <= 0 || physicalHeight <= 0) return null

        val landscape = physicalWidth >= physicalHeight
        val maxLong = if (landscape) physicalWidth else physicalHeight
        val maxShort = if (landscape) physicalHeight else physicalWidth
        if (maxLong < MIN_LONG_SIDE || maxShort < MIN_SHORT_SIDE) return null

        val scale = minOf(maxLong / UNIT_W, maxShort / UNIT_H)
        val longSide = UNIT_W * scale
        val shortSide = UNIT_H * scale
        return if (landscape) longSide to shortSide else shortSide to longSide
    }
}
