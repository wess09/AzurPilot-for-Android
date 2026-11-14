package com.azurpilot.ghio.overlay

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.azurpilot.ghio.MainActivity
import com.azurpilot.ghio.domain.OverlayControlMode
import com.azurpilot.ghio.domain.RunMode
import com.azurpilot.ghio.overlay.border.BorderOverlayManager
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.service.AccessibilityHelperService
import com.azurpilot.ghio.service.HostState
import com.azurpilot.ghio.settings.AppSettingsGateway
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.azurpilot.ghio.theme.AppThemeState
import com.azurpilot.ghio.theme.AzurPilotTheme
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxDisplayMode
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.assist.FxScopeType
import com.petterp.floatingx.compose.enableComposeSupport
import com.petterp.floatingx.listener.IKeyBackListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 外壳控制层：悬浮球/面板是环境状态的开关与仪表盘
 *
 * 观察 [HostState]：虚拟屏环境就绪时显示控制球或边框；主屏模式收起所有悬浮控件，
 * 避免进入无障碍截图。
 * 球点开出面板，面板上「启动环境 = 特权连接 → setup() → startVirtualDisplay()」
 * 与「停止环境 = stopVirtualDisplay()」两个动作直接打 [HostState]
 *
 * 全部方法主线程调用（内部协程域钉在 `Dispatchers.Main`，FloatingX 的窗口操作
 * 也只能在主线程做）；[setup] 生命周期与 Application 对齐，装好后自观察环境态。
 *
 * The shell's control layer: the floating ball / panel are the environment
 * state's switch and dashboard.
 *
 * It observes [HostState]: a ready virtual display shows the control ball or border.
 * Primary mode hides all floating controls so accessibility screenshots exclude them.
 * Tapping the ball opens the panel; the panel's
 * "start environment = privileged connect → setup() → startVirtualDisplay()"
 * and "stop environment = stopVirtualDisplay()" actions hit [HostState]
 * directly.
 *
 * Every method must be called on the main thread (the internal coroutine scope
 * is pinned to `Dispatchers.Main`, and FloatingX window operations are
 * main-thread only); the [setup] lifetime aligns with the Application, and
 * once installed the controller observes the environment state on its own.
 *
 * @property borderOverlayManager 无障碍呼出模式下的边框悬浮层 / The border
 *   overlay used by the accessibility summon mode
 */
class OverlayController(
    private val context: Application,
    private val hostState: HostState,
    private val appSettings: AppSettingsGateway,
    val borderOverlayManager: BorderOverlayManager,
    private val viewModelOwner: OverlayViewModelOwner,
    private val runController: AzurPilotRunController,
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 面板是否锁定拖动；锁定即 ClickOnly，防误触移动 / Whether the panel is locked against dragging; locked means ClickOnly to fend off accidental moves. */
    private val isPanelLocked = MutableStateFlow(true)

    private var hostJob: Job? = null
    private var panelLayout: Pair<Int, Int>? = null

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val next = calculatePanelLayout(newConfig)
            if (next == panelLayout) return
            panelLayout = next
            applyPanelLayout(newConfig, next)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }

    /**
     * 安装悬浮窗并开始观察环境态与呼出模式；幂等，重复调用无害
     *
     * Installs the overlay windows and starts observing the environment state
     * and the summon mode; idempotent, safe to call repeatedly.
     */
    fun setup() {
        if (hostJob != null) return
        context.registerComponentCallbacks(configCallback)
        install()
        configCallback.onConfigurationChanged(context.resources.configuration)
        hostJob = scope.launch {
            combine(
                hostState.snapshot.map { it.runMode == RunMode.BACKGROUND && it.environmentUp }
                    .distinctUntilChanged(),
                appSettings.runMode,
                appSettings.overlayControlMode,
                ::Triple,
            ).collect { (_, runMode, summonMode) ->
                if (runMode == RunMode.FOREGROUND) hidePanel()
                when {
                    !canShowControls() -> {
                        unregisterVolumeKeyListener()
                        hideBall()
                        borderOverlayManager.hide()
                    }
                    summonMode == OverlayControlMode.ACCESSIBILITY -> {
                        hideBall()
                        registerVolumeKeyListener()
                        borderOverlayManager.show()
                    }
                    else -> {
                        unregisterVolumeKeyListener()
                        borderOverlayManager.hide()
                        if (FloatingX.controlOrNull(PANEL_TAG)?.isShow() != true) showBall()
                    }
                }
            }
        }
    }

    private fun canShowControls(): Boolean {
        val host = hostState.snapshot.value
        return appSettings.runMode.value == RunMode.BACKGROUND &&
            host.runMode == RunMode.BACKGROUND && host.environmentUp
    }

    private fun install() {
        if (!FloatingX.isInstalled(PANEL_TAG)) {
            FloatingX.install {
                enableComposeSupport()
                setContext(context)
                setTag(PANEL_TAG)
                setScopeType(FxScopeType.SYSTEM)
                setLayoutView(createPanelView())
                setEnableEdgeAdsorption(false)
                setGravity(FxGravity.CENTER)
                setEnableSafeArea(false)
                setEnableAnimation(true)
                setDisplayMode(FxDisplayMode.ClickOnly)
                setEnableKeyBoardAdapt(true)
                setKeyBackListener(object : IKeyBackListener {
                    // 消费返回键：不然会落到下面的目标应用，把它退出去
                    override fun onBackPressed(): Boolean = true
                })
            }
        }
        if (!FloatingX.isInstalled(BALL_TAG)) {
            FloatingX.install {
                enableComposeSupport()
                setContext(context)
                setTag(BALL_TAG)
                setScopeType(FxScopeType.SYSTEM)
                setLayoutView(createBallView())
                setEnableEdgeAdsorption(true)
                setGravity(FxGravity.RIGHT_OR_CENTER)
                setEnableAnimation(true)
            }
        }
        Timber.d("Control overlay attached")
    }

    /** 构建面板视图；每次尺寸变化都会新建一份（见 [applyPanelLayout]）/ Builds the panel view; a fresh one is created on every size change (see [applyPanelLayout]). */
    private fun createPanelView(): ComposeView = newComposeView().apply {
        panelLayout?.let { layoutParams = ViewGroup.LayoutParams(it.first, it.second) }
        setContent {
            OverlayTheme {
                // 不用 collectAsStateWithLifecycle：悬浮窗隐藏时 owner 停在 CREATED，
                // 那样收不到环境态变化，再显示出来就是过期数据
                val snapshot by hostState.snapshot.collectAsState()
                val locked by isPanelLocked.collectAsState()
                val run by runController.state.collectAsState()
                OverlayPanel(
                    snapshot = snapshot,
                    run = run,
                    isLocked = locked,
                    onStart = { scope.launch { hostState.ensureEnvironmentStarted() } },
                    onStop = { scope.launch { hostState.stopEnvironment() } },
                    onRunStart = { runController.startRunner() },
                    onRunStop = { runController.stopRunner() },
                    onToolStart = { runController.startTool(it) },
                    onToolStop = { runController.stopTool() },
                    onBackToApp = ::bringAppToFront,
                    onLockToggle = { setPanelLocked(it) },
                    onClose = ::onPanelClosed,
                )
            }
        }
    }

    /** 构建悬浮球视图；running 态直接取自环境快照 / Builds the ball view; its running state comes straight from the environment snapshot. */
    private fun createBallView(): ComposeView = newComposeView().apply {
        setContent {
            OverlayTheme {
                val snapshot by hostState.snapshot.collectAsState()
                FloatBall(running = snapshot.environmentUp, onClick = ::onBallClick)
            }
        }
    }

    /**
     * 悬浮窗主题：优先用 Activity 播出来的明暗档
     *
     * 面板视图只在尺寸变化时重建（见 configCallback），系统换深浅色不会重建它——
     * 自己问系统就会停在旧配色，出现"App 已浅色、悬浮窗还是深色"。
     * 进程里还没播过（App 没起来过）时退回系统判断
     *
     * The overlay window theme: prefers the dark/light level published by the
     * Activity.
     *
     * The panel view is rebuilt only on size changes (see configCallback), so a
     * system dark/light switch does not rebuild it — asking the system directly
     * would freeze the old palette, producing "the app is light but the overlay
     * is still dark". Falls back to the system judgment when nothing has been
     * published yet (the app has not been opened).
     */
    @Composable
    private fun OverlayTheme(content: @Composable () -> Unit) {
        val published by AppThemeState.darkTheme.collectAsState()
        AzurPilotTheme(darkTheme = published ?: isSystemInDarkTheme(), content = content)
    }

    /** 新建挂到 [OverlayViewModelOwner] 下的透明 ComposeView / Creates a transparent ComposeView attached to the [OverlayViewModelOwner]. */
    private fun newComposeView(): ComposeView = ComposeView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setViewTreeLifecycleOwner(viewModelOwner)
        setViewTreeViewModelStoreOwner(viewModelOwner)
        setViewTreeSavedStateRegistryOwner(viewModelOwner)
    }

    private fun onBallClick() {
        hideBall()
        showPanel()
    }

    private fun onPanelClosed() {
        hidePanel()
        if (appSettings.overlayControlMode.value == OverlayControlMode.FLOAT_BALL) showBall()
    }

    /** 把外壳 App 拉回前台；启动失败只记日志，不打断悬浮窗交互 / Brings the shell app back to the front; a failed start is only logged, never breaking the overlay interaction. */
    private fun bringAppToFront() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Timber.w(it, "Failed to return to app") }
    }

    private fun setPanelLocked(locked: Boolean) {
        isPanelLocked.value = locked
        FloatingX.controlOrNull(PANEL_TAG)?.updateConfig {
            setDisplayMode(if (locked) FxDisplayMode.ClickOnly else FxDisplayMode.Normal)
        }
    }

    private fun showPanel() {
        if (!canShowControls()) return
        viewModelOwner.start()
        FloatingX.controlOrNull(PANEL_TAG)?.show()
    }

    private fun hidePanel() {
        val panel = FloatingX.controlOrNull(PANEL_TAG) ?: return
        if (!panel.isShow()) return
        panel.hide()
        viewModelOwner.stop()
    }

    private fun showBall() {
        if (canShowControls()) FloatingX.controlOrNull(BALL_TAG)?.show()
    }

    private fun hideBall() = FloatingX.controlOrNull(BALL_TAG)?.hide()

    private fun togglePanel() {
        if (FloatingX.controlOrNull(PANEL_TAG)?.isShow() == true) hidePanel() else showPanel()
    }

    /** 注册音量 ± 同按监听（由无障碍服务转发），触发面板开关 / Registers the Volume +/- combo listener (forwarded by the accessibility service) to toggle the panel. */
    private fun registerVolumeKeyListener() {
        AccessibilityHelperService.onVolumeUpDownPressed.set { scope.launch { togglePanel() } }
    }

    /** 摘除音量双键监听 / Removes the volume double-key listener. */
    private fun unregisterVolumeKeyListener() {
        AccessibilityHelperService.onVolumeUpDownPressed.set(null)
    }

    /**
     * 计算面板尺寸（像素）：宽 0.85 屏宽，高横屏 0.85 / 竖屏 0.72 屏高
     *
     * 横屏时高度吃满一点：可用高度本来就少，按竖屏那个比例会挤成一条。
     *
     * 竖屏从 0.6 提到 0.72：面板里有状态卡、日志板、启停与工具槽，0.6 放不下，
     * 中段得滚一下才看得见工具槽。底部按钮不随滚动消失是对的，
     * 但"内容藏在下面"不该是常态——中段滚动留着给矮屏与横屏兜底
     *
     * Computes the panel size in pixels: width is 0.85 of the screen width,
     * height is 0.85 (landscape) / 0.72 (portrait) of the screen height.
     *
     * In landscape the height takes a larger share: the usable height is small
     * already, and the portrait ratio squeezes it into a sliver.
     *
     * Portrait went from 0.6 up to 0.72: the panel holds a status card, a log
     * board, start/stop and tool slots, and 0.6 could not fit them — the tool
     * slots needed a mid-scroll to become visible. Bottom buttons staying put
     * during a scroll is right, but "content hidden below" must not be the
     * norm — mid-scroll remains as the fallback for short and landscape
     * screens.
     */
    private fun calculatePanelLayout(config: Configuration): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val heightRatio =
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) 0.85f else 0.72f
        return (config.screenWidthDp * density * 0.85f).toInt() to
                (config.screenHeightDp * density * heightRatio).toInt()
    }

    /** 应用新尺寸：先隐藏、居中、换视图再按原状态恢复 / Applies the new size: hide first, re-center, swap the view, then restore the previous visibility. */
    private fun applyPanelLayout(config: Configuration, layout: Pair<Int, Int>) {
        val control = FloatingX.controlOrNull(PANEL_TAG) ?: return
        val density = context.resources.displayMetrics.density
        val (width, height) = layout
        val wasShowing = control.isShow()
        if (wasShowing) control.hide()
        control.move(
            (config.screenWidthDp * density - width) / 2,
            (config.screenHeightDp * density - height) / 2
        )
        // 尺寸变了必须换视图：FloatingX 不会因为 layoutParams 改了就重新测量已挂载的那份
        control.updateView(createPanelView())
        if (wasShowing && appSettings.runMode.value == RunMode.BACKGROUND) control.show()
    }

    private companion object {
        /** FloatingX 面板窗口标签 / The FloatingX panel window tag. */
        const val PANEL_TAG = "azurpilot_overlay_panel"

        /** FloatingX 悬浮球窗口标签 / The FloatingX ball window tag. */
        const val BALL_TAG = "azurpilot_overlay_ball"
    }
}
