package com.azurpilot.ghio.overlay.screensaver

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.koin.android.ext.android.inject

/**
 * 屏保的全屏宿主：普通悬浮窗位于系统栏下方，须由 Activity 请求沉浸模式。
 *
 * 内容仍归悬浮窗管理器，宿主只绘制黑色背景并隐藏系统栏；退出时随遮罩一起销毁。
 * 系统边缘手势仍可暂时唤出导航栏。所有窗口与生命周期操作均在主线程执行。
 *
 * Immersive host for the screensaver: ordinary overlays sit below system bars, so an Activity
 * must request immersive mode.
 *
 * The manager still owns the content; the host only draws black and hides system bars, and finishes
 * with the overlay. System edge gestures may reveal navigation transiently. Window and lifecycle
 * operations run on the main thread.
 */
class ScreenSaverActivity : ComponentActivity() {
    private val manager: ScreenSaverOverlayManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!manager.attachHost(this)) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        window.attributes = window.attributes.apply {
            screenBrightness = 0.01f
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 返回键不应跳过滑块；系统主页手势仍由系统处理。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        manager.onHostStopped(this)
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        }
    }
}
