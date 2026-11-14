package com.azurpilot.ghio.ui.oobe

import android.graphics.Color
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 向导显示期间隐藏状态栏并清除系统栏底色；离开向导后恢复宿主窗口的原始设置。
 * 授权页面返回或重新获得焦点时重新应用，底部导航和系统返回手势保持可用。
 *
 * Hides the status bar and clears system bar backgrounds while onboarding is composed,
 * restoring the host window on disposal. Reapplies after permission activities or focus changes
 * while keeping bottom navigation and system back gestures available.
 */
@Suppress("DEPRECATION")
@Composable
internal fun OobeSystemBars() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(window, view, lifecycle) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val statusVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(statusBars) ?: true
        val behavior = controller.systemBarsBehavior
        val statusColor = window.statusBarColor
        val navigationColor = window.navigationBarColor
        val statusContrast = if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced else false
        val navigationContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
        fun apply() {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(statusBars)
        }
        apply()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apply()
        }
        val focus = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) apply()
        }
        lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        onDispose {
            lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            window.statusBarColor = statusColor
            window.navigationBarColor = navigationColor
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = statusContrast
                window.isNavigationBarContrastEnforced = navigationContrast
            }
            controller.systemBarsBehavior = behavior
            if (statusVisible) controller.show(statusBars) else controller.hide(statusBars)
        }
    }
}
