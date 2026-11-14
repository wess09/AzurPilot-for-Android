package com.azurpilot.ghio.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreenViewProvider
import com.azurpilot.ghio.R
import timber.log.Timber

/**
 * 启动画面退出阶段探头弹入动画调度器
 *
 * 在启动画面退出阶段驱动人物自圆形遮罩左下角边缘探头并斜向弹入就位。
 * 舞台采用自带圆形裁切的外层容器 ([ViewOutlineProvider.setOval] + [View.setClipToOutline])，
 * 内部叠加两层：
 * 1. [R.drawable.splash_icon_backdrop]：包含冷蓝灰渐变底色、几何多边形装饰、圆角终端窗口与 `>_` 提示符；
 * 2. [R.drawable.launcher_character]：原版高清人物位图，按自适应图标 72dp 圆形构图精确对齐。
 *
 * 构图坐标完全与官方自适应图标规范 (preview/circle.png) 像素级一致：
 * - 相对 720px 圆形可见区，人物尺寸为 820px，基准左上角落在 (-120px, -10px)；
 * - 初始探头帧向左下大幅度偏移 (-175px, +175px)，猫耳、呆毛沿圆弧边缘探出；
 * - 动画沿对角线斜向归零，配合超调插值器产生轻快自然的大行程弹性探头入场效果；
 * - 圆形外框自裁彻底消除视口外裁剪导致的断头截断，所有边缘平滑过渡。
 *
 * Dispatches the spring peek entrance animation during splash exit.
 *
 * In the splash screen exit phase, animates the character peeking in from the
 * bottom-left mask edge diagonally into her official adaptive icon resting pose.
 * The stage uses an oval-clipped FrameLayout stacking the circular backdrop and the
 * high-resolution character bitmap. Ratios strictly match the 72dp circular mask metrics
 * from preview/circle.png (size = 820/720, left = -120/720, top = -10/720), smoothly
 * masked by the circular boundary to eliminate any rectangular clipping artifacts.
 */
object SplashExitAnimator {

    /** 探头动画时长 (毫秒) / Duration of the spring peek animation in milliseconds. */
    private const val ANIMATION_DURATION_MS = 680L

    /** 退出渐隐时长 (毫秒) / Duration of splash screen fade out in milliseconds. */
    private const val FADE_DURATION_MS = 160L

    /** 超调回弹张力 / Tension for the OvershootInterpolator. */
    private const val OVERSHOOT_TENSION = 1.35f

    /**
     * 在主线程运行退出动画；图标未挂载或尺寸无效时立即移除启动画面。
     *
     * Runs the exit animation on the main thread, removing the splash immediately if its icon is
     * detached or has invalid dimensions.
     *
     * @param provider 启动画面视图提供者。 / The splash screen view provider.
     */
    fun run(provider: SplashScreenViewProvider) {
        val splash = provider.view
        val icon = provider.iconView

        // Android 12+ 缺失图标时 AndroidX 会返回未挂载的占位 View，向它 post 无法完成退出。
        if (!icon.isAttachedToWindow || icon.width <= 0 || icon.height <= 0) {
            Timber.w(
                "Skipping splash exit animation: icon attached=%s size=%dx%d",
                icon.isAttachedToWindow,
                icon.width,
                icon.height,
            )
            provider.remove()
            return
        }

        val container = splash as? ViewGroup ?: run {
            provider.remove()
            return
        }

        val size = icon.width.coerceAtLeast(icon.height)

        // 基于自适应图标 720px 圆形构图的精准比例
        val charSize = (size * 820f / 720f).toInt()
        val charLeft = (-120f / 720f * size).toInt()
        val charTop = (-10f / 720f * size).toInt()
        val offset = 175f / 720f * size

        // 圆形裁切舞台：确保人物进入和超出边缘时严格遵循圆弧轮廓，杜绝直角切边断头
        val stage = FrameLayout(splash.context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }

        // 底图层：包含终端窗口与 >_ 提示符
        val backdrop = ImageView(splash.context).apply {
            setImageResource(R.drawable.splash_icon_backdrop)
            scaleType = ImageView.ScaleType.FIT_XY
        }

        // 人物层：原版高清 PNG，初始置于左下探头偏移行程
        val character = ImageView(splash.context).apply {
            setImageResource(R.drawable.launcher_character)
            scaleType = ImageView.ScaleType.FIT_XY
            translationX = -offset
            translationY = offset
        }

        stage.addView(backdrop, FrameLayout.LayoutParams(size, size))
        stage.addView(
            character,
            FrameLayout.LayoutParams(charSize, charSize).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = charLeft
                topMargin = charTop
            }
        )

        // 精确对齐系统 iconView 的屏幕坐标
        val locIcon = IntArray(2)
        val locContainer = IntArray(2)
        icon.getLocationInWindow(locIcon)
        container.getLocationInWindow(locContainer)
        val stageLeft = locIcon[0] - locContainer[0]
        val stageTop = locIcon[1] - locContainer[1]

        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = if (stageLeft > 0 || stageTop > 0) stageLeft else (container.width - size) / 2
            topMargin = if (stageLeft > 0 || stageTop > 0) stageTop else (container.height - size) / 2
        }

        container.addView(stage, params)
        icon.visibility = View.INVISIBLE

        // 斜向自左下往右上弹入目标位置
        val animator = AnimatorSet().apply {
            duration = ANIMATION_DURATION_MS
            interpolator = OvershootInterpolator(OVERSHOOT_TENSION)
            playTogether(
                ObjectAnimator.ofFloat(character, View.TRANSLATION_X, -offset, 0f),
                ObjectAnimator.ofFloat(character, View.TRANSLATION_Y, offset, 0f),
            )
        }

        animator.doOnEnd {
            splash.animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .withEndAction {
                    try {
                        provider.remove()
                    } catch (_: Throwable) {
                    }
                }
                .start()
        }

        animator.start()
    }
}
