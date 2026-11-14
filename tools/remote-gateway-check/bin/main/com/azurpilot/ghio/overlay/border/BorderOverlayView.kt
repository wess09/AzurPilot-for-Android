package com.azurpilot.ghio.overlay.border

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator

/**
 * 沿屏幕边缘画一圈流动的渐变
 *
 * 由 [BorderOverlayManager] 挂为不可触摸的系统悬浮窗；圆角半径取自系统
 * RoundedCorner（API 31+，insets 驱动），更早版本按名查框架隐藏 dimen 兜底。
 * 动画在 attach 时启动、detach 时取消，绘制全部发生在主线程
 *
 * 前台模式下目标应用占满屏幕，除了这圈边框没有别的迹象能区分手动与自动
 *
 * Draws a flowing gradient ring along the screen edges.
 *
 * Mounted by [BorderOverlayManager] as an untouchable system overlay; the
 * corner radii come from the system RoundedCorner (API 31+, insets-driven),
 * with a named lookup into the framework's hidden dimen as the fallback on
 * older versions. The animation starts on attach and cancels on detach, and
 * all drawing happens on the main thread.
 *
 * In foreground mode the target app fills the screen; without this border
 * there is no other sign telling manual operation from automated.
 */
class BorderOverlayView(context: Context, private val style: BorderStyle = BorderStyle()) : View(context) {

    private val borderWidthPx: Float = style.widthDp * resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
    }

    private val borderPath = Path()
    private val borderRect = RectF()
    private val gradientMatrix = Matrix()

    private var rotationAngle = 0f
    private var sweepGradient: SweepGradient? = null

    private var cornerTopLeft = 0f
    private var cornerTopRight = 0f
    private var cornerBottomLeft = 0f
    private var cornerBottomRight = 0f

    // 转的是渐变矩阵而不是整个 View：转 View 会连带边框形状一起歪
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = style.animationDurationMs
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.let { cornerTopLeft = it.radius.toFloat() }
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.let { cornerTopRight = it.radius.toFloat() }
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.let { cornerBottomLeft = it.radius.toFloat() }
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.let { cornerBottomRight = it.radius.toFloat() }
            updateBorderPath()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 描边是以路径为中心向两侧各扩一半，不内缩会被裁掉外侧一半
        val half = borderWidthPx / 2
        borderRect.set(half, half, w - half, h - half)
        sweepGradient = SweepGradient(w / 2f, h / 2f, style.colors, null).also { paint.shader = it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val radius = legacyCornerRadius()
            cornerTopLeft = radius
            cornerTopRight = radius
            cornerBottomLeft = radius
            cornerBottomRight = radius
        }
        updateBorderPath()
    }

    /**
     * API 31 以下没有 `getRoundedCorner`，只能按名去查系统框架的隐藏 dimen
     *
     * 那是 `com.android.internal.R` 的 @hide 资源，没有公开常量，内部 id 跨版本与 OEM
     * 都不稳定，按名解析是 DiscouragedApi 承认的合法例外
     *
     * Below API 31 there is no `getRoundedCorner`, so the framework's hidden
     * dimen is looked up by name.
     *
     * It is a `com.android.internal.R` @hide resource with no public constant;
     * the internal id is unstable across versions and OEMs, and resolving by
     * name is the sanctioned exception acknowledged by DiscouragedApi.
     */
    @SuppressLint("DiscouragedApi")
    private fun legacyCornerRadius(): Float = runCatching {
        val id = resources.getIdentifier("rounded_corner_radius", "dimen", "android")
        if (id > 0) resources.getDimension(id) else 0f
    }.getOrDefault(0f)

    /** 重建圆角矩形路径；尺寸与圆角任一变化都要重算 / Rebuilds the rounded-rect path; any change to size or corners requires a recompute. */
    private fun updateBorderPath() {
        borderPath.reset()
        borderPath.addRoundRect(
            borderRect,
            floatArrayOf(
                cornerTopLeft, cornerTopLeft,
                cornerTopRight, cornerTopRight,
                cornerBottomRight, cornerBottomRight,
                cornerBottomLeft, cornerBottomLeft,
            ),
            Path.Direction.CW,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        sweepGradient?.let {
            gradientMatrix.setRotate(rotationAngle, width / 2f, height / 2f)
            it.setLocalMatrix(gradientMatrix)
        }
        canvas.drawPath(borderPath, paint)
    }
}
