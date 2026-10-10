package com.azurpilot.ghio

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.AppThemeState
import com.azurpilot.ghio.ui.AppRoot
import com.azurpilot.ghio.ui.SplashExitAnimator
import com.azurpilot.ghio.ui.shortcut.ShortcutIntents
import com.azurpilot.ghio.ui.shortcut.ShortcutRequests
import com.azurpilot.ghio.widget.AzurPilotWidgetActionReceiver
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * App 主壳 Activity：承载 Compose UI，并处理窗口层面的平台适配
 *
 * 职责：
 * - splash 屏保持到设置异步加载完成才放行首帧
 * - 挖孔屏 edge-to-edge 适配（见 onCreate 内的 cutout 模式说明）
 * - 挂机 / 工具运行期间保持屏幕常亮：STARTED 期间跟随运行状态加 / 清
 *   FLAG_KEEP_SCREEN_ON；退到后台或用户手动息屏时不阻止锁屏
 * - 明暗切换时同步状态栏 / 导航栏样式，并把最终结果播给 [AppThemeState]，
 *   供悬浮窗这类拿不到 Configuration 的独立窗口读取
 * - 分发桌面长按快捷方式：启停复用小组件广播链，跳转型经 [ShortcutRequests] 转交
 *
 * The app's main shell Activity: hosts the Compose UI and handles
 * window-level platform adaptations.
 *
 * Responsibilities:
 * - hold the splash screen until settings finish loading asynchronously
 * - display-cutout edge-to-edge adaptation (see the cutout-mode note in
 *   onCreate)
 * - keep the screen on while an automation session or tool runs: while
 *   STARTED, FLAG_KEEP_SCREEN_ON follows the run state; backgrounding or a
 *   manual screen-off still allows lock
 * - on dark-theme changes, refresh the status / navigation bar styles and
 *   publish the outcome to [AppThemeState] for standalone windows such as
 *   overlays, which cannot read Configuration themselves
 * - dispatch launcher shortcuts: toggles reuse the widget broadcast chain,
 *   navigation requests go to [AppRoot] via [ShortcutRequests]
 */
class MainActivity : AppCompatActivity() {

    private val appSettings: AppSettingsManager by inject()
    private val runController: AzurPilotRunController by inject()
    private val shortcutRequests: ShortcutRequests by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !appSettings.loaded.value }
        // 退出阶段：31+ 图标位 AVD 已随启动画面自动播放，直接放行；
        // 旧系统 compat 不会播 AVD，由 SplashExitAnimator 在此补播探头动画
        splash.setOnExitAnimationListener(SplashExitAnimator::run)
        super.onCreate(savedInstanceState)
        handleShortcutIntent(intent)

        // 挖孔屏：edge-to-edge 下允许内容画进孔区两侧，**避让**由 Compose 的 displayCutout
        // insets 做（默认模式在横屏会把整窗从孔洞处挤开，出现一条黑边，虚拟屏页面尤其难看）
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 挂机/工具运行期间保持屏幕唤醒（App 退到后台或用户手动息屏时仍允许锁屏）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runController.state.collect { s ->
                    if (s.runnerAlive || s.toolAlive) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        setContent {
            AppRoot(
                onDarkThemeChanged = { dark ->
                    applyEdgeToEdge(dark)
                    // 悬浮窗是独立窗口，拿不到这里的 Configuration，只能读播出来的结果
                    AppThemeState.publish(dark)
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcutIntent(intent)
    }

    /**
     * 分发桌面长按快捷方式（隐式 action 见 [ShortcutIntents]）
     *
     * 一键启停原样复用小组件的广播链：广播接收窗口内拉 FGS 享后台启动豁免，
     * 与桌面小组件行为完全一致；跳转型目的地经 [ShortcutRequests] 转交
     * [AppRoot]——pager 与 NavHost 都在组合里，Activity 摸不到。
     *
     * Dispatches launcher long-press shortcuts (implicit action in [ShortcutIntents]).
     *
     * The toggle reuses the widget's broadcast chain as-is: the FGS raised inside
     * the broadcast window keeps its background-start exemption, matching the
     * widget behavior exactly; navigation-type destinations go through
     * [ShortcutRequests] to [AppRoot], since the pager and the NavHost both live
     * in composition, out of the Activity's reach.
     */
    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action != ShortcutIntents.ACTION_SHORTCUT) return
        when (intent.getStringExtra(ShortcutIntents.EXTRA_ID)) {
            ShortcutIntents.ID_TOGGLE_RUNNER -> toggleRunnerFromShortcut()
            ShortcutIntents.ID_SCREEN -> shortcutRequests.post(ShortcutRequests.OPEN_SCREEN)
            ShortcutIntents.ID_RUNNER_LOG -> shortcutRequests.post(ShortcutRequests.OPEN_RUNNER_LOG)
        }
    }

    /** 一键启停：先读状态弹提示再广播，接收器异步执行后状态流自会更新 / Toggles via the widget broadcast chain. */
    private fun toggleRunnerFromShortcut() {
        val alive = runController.state.value.runnerAlive
        sendBroadcast(Intent(this, AzurPilotWidgetActionReceiver::class.java).apply {
            action = AzurPilotWidgetActionReceiver.ACTION_TOGGLE_RUNNER
        })
        Toast.makeText(
            this,
            if (alive) R.string.shortcut_toggle_stopping else R.string.shortcut_toggle_starting,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun applyEdgeToEdge(darkMode: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Q+ 不关掉对比度强制，系统会在透明导航栏下面垫一层半透明 scrim
            window.isNavigationBarContrastEnforced = false
        }
    }
}
