package com.azurpilot.ghio.remote

import com.azurpilot.ghio.IRunnerCallback
import com.azurpilot.ghio.ITouchEventCallback
import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.bridge.InputControlUtils
import com.azurpilot.ghio.bridge.NativeBridgeLib
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.constant.DisplayMode
import com.azurpilot.ghio.remote.internal.ActivityUtils
import com.azurpilot.ghio.remote.internal.AppWatchdog
import com.azurpilot.ghio.remote.internal.BridgeServer
import com.azurpilot.ghio.remote.internal.PermissionGrantHelper
import com.azurpilot.ghio.service.AccessibilityHelperService
import com.azurpilot.ghio.remote.internal.PowerController
import com.azurpilot.ghio.remote.internal.PrimaryDisplayManager
import com.azurpilot.ghio.remote.internal.ScreenManager
import com.azurpilot.ghio.remote.internal.SdkTaskRepatriator
import com.azurpilot.ghio.constant.PrivilegedGrant
import com.azurpilot.ghio.remote.internal.VirtualDisplayManager
import com.azurpilot.ghio.remote.internal.WakeUnlockController
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager
import com.azurpilot.ghio.third.Workarounds
import android.view.Surface
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * 特权进程的入口对象：由 Shizuku 或 root starter 反射实例化，实例化即完成进程内初始化
 *
 * 进程内所有子系统（桥服务、看门狗、显示管理、电源控制）都挂在它底下，
 * [RemoteService] binder 接口的每个方法都是到对应 internal 对象的一跳转发。
 *
 * 线程约定：构造跑在 starter 反射调用的线程上，且**不能抛**——抛了 binder 回不去，
 * app 侧只看得到连接超时；其余方法跑在 binder 线程池上，各自幂等或无共享可变状态；
 * 心跳看门狗是独立守护线程。
 *
 * Entry object of the privileged process: instantiated reflectively by the Shizuku or root
 * starter, with in-process initialization done right in the constructor.
 *
 * Every in-process subsystem (bridge server, watchdog, display managers, power control) hangs
 * off it; each method of the [RemoteService] binder interface is a one-hop forward to the
 * matching internal object.
 *
 * Threading: the constructor runs on the starter's reflection thread and **must not throw** —
 * an exception there escapes to the app only as a bind timeout. The other methods run on
 * binder pool threads and are individually idempotent or free of shared mutable state; the
 * heartbeat watchdog is its own daemon thread.
 */
class RemoteServiceImpl : RemoteService.Stub() {

    private val virtualDisplayMode = AtomicInteger(DisplayMode.BACKGROUND)
    private val appPid = AtomicInteger(0)
    private val destroyed = AtomicBoolean(false)

    init {
        RemoteBootTrace.mark("CTOR_START")
        Workarounds.apply()
        // Ln 文件 sink：特权进程的 logcat 用户拿不到，落进 debug 目录随 launcher_logs
        // 导出（搬屏盯防、看门狗、启动校验的行为因此可在用户日志包里直接核对）
        runCatching { Ln.initFileSink(RemoteBootTrace.debugDir) }
        runCatching { BridgeServer.start() }.onFailure { Ln.e("$TAG: BridgeServer start failed", it) }
        // 非 destroy 路径退出（进程被杀等）时兜底撤残留：主屏强改尺寸、桥服务、电源状态
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching(::cleanup) }.apply { name = "remote-shutdown-hook" }
        )
        startHeartbeatWatchdog()
        RemoteBootTrace.mark("CTOR_DONE")
    }

    /**
     * 停掉一切并让特权进程退出；幂等，binder 侧与心跳看门狗都会调
     *
     * 先撤看门狗与触摸回调再走 [cleanup]，最后 exitProcess(0) 直接杀进程——
     * 本方法不会正常返回。
     *
     * Tears everything down and exits the privileged process; idempotent, called from both the
     * binder side and the heartbeat watchdog.
     *
     * Stops the watchdog and the touch callback before [cleanup], then kills the process via
     * exitProcess(0) — this method never returns normally.
     */
    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        Ln.i("$TAG: destroy()")
        AppWatchdog.stopWatching()
        InputControlUtils.setTouchCallback(null)
        cleanup()
        exitProcess(0)
    }

    /** 同 [destroy] / Alias of [destroy] */
    override fun exit() = destroy()

    /** 诊断串：native bridge 状态 + uid/pid / Diagnostics string: native bridge state + uid/pid */
    override fun version(): String = buildString {
        append("bridge=").append(if (NativeBridgeLib.LOADED) NativeBridgeLib.ping() else "not loaded")
        append(" uid=").append(Process.myUid())
        append(" pid=").append(Process.myPid())
    }

    /** 本特权进程的 pid / This privileged process's pid */
    override fun pid(): Int = Process.myPid()

    /** Stable process UID probe for display-service package/UID validation. */
    override fun processUid(): Int = Process.myUid()

    /** [AppWatchdog] 当前状态码 / Current [AppWatchdog] state code */
    override fun watchdogState(): Int = AppWatchdog.state.value

    /** [AppWatchdog] 运行期反推的目标包名，未取到为空串 / Target package inferred by [AppWatchdog]; empty when never acquired */
    override fun watchdogTargetPackage(): String = AppWatchdog.targetPackage.orEmpty()

    /** 亮屏解锁，透传 [WakeUnlockController] / Wake-and-unlock passthrough to [WakeUnlockController] */
    override fun unlock(credential: String?): Int =
        WakeUnlockController.unlock(credential.orEmpty())

    /** 上锁自测：先锁屏息屏再解一次 / Self-test: locks and sleeps first, then unlocks once */
    override fun testUnlock(credential: String?): Int =
        WakeUnlockController.testUnlock(credential.orEmpty())

    /** 上锁并息屏 / Locks the keyguard and puts the screen to sleep */
    override fun lockAndSleep(): Int = WakeUnlockController.lockAndSleep()

    /**
     * 主屏是否亮着；读取失败宽松返回 true，不拦后续动作
     *
     * Whether the primary screen is on; leniently true on read failure so later steps are not
     * blocked.
     */
    override fun isScreenOn(): Boolean =
        runCatching { ServiceManager.getPowerManager().isScreenOn(0) }.getOrDefault(true)

    /**
     * 强停看门狗反推出的目标 app；从未取到目标时返回 false
     *
     * Force-stops the target app inferred by the watchdog; false when no target was ever
     * acquired.
     */
    override fun stopTargetApp(): Boolean {
        val target = AppWatchdog.targetPackage ?: run {
            Ln.i("$TAG: stopTargetApp skipped, watchdog never acquired a target")
            return false
        }
        return runCatching {
            ServiceManager.getActivityManager().forceStopPackage(target)
            Ln.i("$TAG: force-stopped $target")
            true
        }.getOrElse {
            Ln.w("$TAG: stopTargetApp failed: ${'$'}it")
            false
        }
    }

    /**
     * app 侧周期上报自己的 pid；心跳看门狗据此探测 app 进程是否消失
     *
     * The app periodically reports its pid here; the heartbeat watchdog polls that pid to
     * detect app-process death.
     */
    override fun heartbeat(pid: Int) {
        appPid.set(pid)
    }

    /**
     * app 侧建连后的一次性配置；顺手关掉 phantom process killer
     * （Android 12 起会收割本进程的子进程）
     *
     * One-time configuration right after the app connects; also disables the phantom process
     * killer (Android 12+ reaps this process's children).
     */
    override fun setup(piRoot: String?, logDir: String?, isDebug: Boolean): Boolean {
        // Android 12 起子进程会被 phantom process killer 收割，先关掉
        PermissionGrantHelper.disablePhantomProcessKiller()
        Ln.i("$TAG: setup, piRoot=$piRoot logDir=$logDir isDebug=$isDebug")
        return true
    }

    /**
     * 切显示模式：PRIMARY=主屏镜像采集，BACKGROUND=独立虚拟屏；切换前先停另一路，
     * 未知模式返回 false
     *
     * Switches the display mode: PRIMARY mirrors the primary display, BACKGROUND uses a
     * standalone virtual display. The other manager is stopped first; unknown modes return
     * false.
     */
    override fun setVirtualDisplayMode(mode: Int): Boolean = when (mode) {
        DisplayMode.PRIMARY -> {
            // 主屏模式下游戏与 SDK 弹页同屏，搬屏盯防没有意义，停掉
            SdkTaskRepatriator.stop()
            VirtualDisplayManager.stop()
            virtualDisplayMode.set(mode)
            true
        }

        DisplayMode.BACKGROUND -> {
            PrimaryDisplayManager.stop()
            virtualDisplayMode.set(mode)
            true
        }

        else -> false
    }

    /** 改虚拟屏分辨率，采集中的屏会重启 / Changes the virtual display resolution, restarting a live display */
    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) {
        VirtualDisplayManager.setResolution(width, height, dpi)
    }

    /** 改虚拟屏刷新率，下次建屏生效 / Sets the virtual display refresh rate; applies on the next display creation */
    override fun setVirtualDisplayRefreshRate(rate: Float) {
        VirtualDisplayManager.setRefreshRate(rate)
    }

    /**
     * 按当前模式拉起显示；BACKGROUND 模式下建屏成功后顺带启动 userActivity 保活
     *
     * Starts the display per the current mode; in BACKGROUND mode a successful creation also
     * starts the userActivity keep-alive.
     */
    override fun startVirtualDisplay(): Int = when (virtualDisplayMode.get()) {
        DisplayMode.PRIMARY -> PrimaryDisplayManager.start()
        DisplayMode.BACKGROUND -> VirtualDisplayManager.start().also { displayId ->
            if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
                PowerController.startUserActivityKeepAlive(displayId)
                // 屏建好了才可能跑自动化，SDK 弹页盯防随之启动
                SdkTaskRepatriator.start()
                // 目标盯防（漂移拉回 / 帧停滞踢活）随之启动，漏起则 stopWatching 悬空
                AppWatchdog.startWatching()
            }
        }

        else -> DefaultDisplayConfig.DISPLAY_NONE
    }

    /** 停显示：连同看门狗、SDK 弹页盯防与 userActivity 保活一起撤 / Stops the display along with the watchdog, the SDK-popup watcher and the userActivity keep-alive */
    override fun stopVirtualDisplay() {
        AppWatchdog.stopWatching()
        SdkTaskRepatriator.stop()
        when (virtualDisplayMode.get()) {
            DisplayMode.PRIMARY -> PrimaryDisplayManager.stop()
            DisplayMode.BACKGROUND -> {
                PowerController.stopUserActivityKeepAlive()
                VirtualDisplayManager.stop()
            }
        }
    }

    /**
     * 没有虚拟屏时返回 true：调用方据此判断「是否需要拉回」，无屏可拉即无需处理
     *
     * Returns true when no virtual display exists: callers use this to decide whether an app
     * needs pulling back — with no display there is nothing to pull.
     */
    override fun isAppOnVirtualDisplay(packageName: String): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) return true
        return ActivityUtils.isAppOnDisplay(packageName, displayId)
    }

    /** 把目标 app 的任务固定到虚拟屏；无活动屏时返回 false / Pins the target app onto the virtual display; false with no active display */
    override fun moveAppToVirtualDisplay(packageName: String): Boolean {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            Ln.w("$TAG: moveAppToVirtualDisplay: no active virtual display")
            return false
        }
        return ActivityUtils.repinAppToDisplay(packageName, displayId)
    }

    /** 虚拟屏启动是否强制 FULLSCREEN 窗口模式（漂移拉回用）/ Whether launches onto the virtual display force FULLSCREEN windowing (used for drift repin) */
    override fun setForceFullscreenOnVirtualDisplay(enabled: Boolean) {
        ActivityUtils.forceFullscreenOnVirtualDisplay = enabled
    }

    /** 物理屏电源开关，透传 [PowerController] / Physical screen power switch, passthrough to [PowerController] */
    override fun setDisplayPower(on: Boolean) {
        PowerController.setDisplayPower(on)
    }

    /**
     * 改主屏分辨率会把整个系统的 UI 重排一遍，失败要报出去而不是吞掉——
     * 用户看到「已修改」却什么都没变，只会以为是自己屏幕不支持
     *
     * Changing the primary display size reflows the whole system UI, so failures must be
     * reported rather than swallowed — a user told "modified" who sees no change will just
     * assume their screen does not support it.
     */
    override fun setForcedDisplaySize(width: Int, height: Int): Boolean {
        Ln.i("$TAG: setForcedDisplaySize(${width}x$height)")
        return runCatching { ScreenManager.setForcedDisplaySize(width, height) }
            .onFailure { Ln.e("$TAG: setForcedDisplaySize failed: ${it.message}") }
            .getOrDefault(false)
    }

    /** 撤销主屏强制分辨率 / Clears the forced primary display size */
    override fun clearForcedDisplaySize(): Boolean {
        Ln.i("$TAG: clearForcedDisplaySize")
        return runCatching { ScreenManager.clearForcedDisplaySize() }
            .onFailure { Ln.e("$TAG: clearForcedDisplaySize failed: ${it.message}") }
            .getOrDefault(false)
    }

    /** 预览 surface：同时喂给虚拟屏与 native 预览路径 / Preview surface, fed to both the virtual display and the native preview path */
    override fun setMonitorSurface(surface: Surface?) {
        Ln.i("$TAG: setMonitorSurface(${surface != null})")
        VirtualDisplayManager.setMonitorSurface(surface)
        NativeBridgeLib.setPreviewSurface(surface)
    }

    /** 注册触摸事件回调（预览手势回传 app）/ Registers the touch event callback (preview gestures echoed back to the app) */
    override fun setTouchCallback(callback: ITouchEventCallback?) {
        InputControlUtils.setTouchCallback(callback)
    }

    /** 预览上的手动注入；仅虚拟屏模式生效 / Manual injection for the preview; effective only in virtual display mode */
    override fun touchDown(x: Int, y: Int) = withVirtualDisplay { InputControlUtils.down(x, y, 0, it) }

    /** 预览上的手动注入；仅虚拟屏模式生效 / Manual injection for the preview; effective only in virtual display mode */
    override fun touchMove(x: Int, y: Int) = withVirtualDisplay { InputControlUtils.move(x, y, 0, it) }

    /** 预览上的手动注入；仅虚拟屏模式生效 / Manual injection for the preview; effective only in virtual display mode */
    override fun touchUp(x: Int, y: Int) = withVirtualDisplay { InputControlUtils.up(x, y, 0, it) }

    /** 主屏模式不接管输入；无虚拟屏时静默丢弃 / Input is not intercepted in PRIMARY mode; silently dropped with no virtual display */
    private inline fun withVirtualDisplay(action: (Int) -> Unit) {
        if (virtualDisplayMode.get() == DisplayMode.PRIMARY) return
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) action(displayId)
    }

    // 执行链路在 PRoot 运行时侧，不在本进程；以下为 binder 接口占位，恒返回空值

    /** 接口占位：运行控制不在本进程实现 / Interface stub: run control is not implemented in this process */
    override fun setRunnerCallback(callback: IRunnerCallback?) = Unit

    /** 接口占位，恒返回 false / Interface stub, always false */
    override fun startRun(runPlanJson: String?): Boolean = false

    /** 接口占位，恒返回 false / Interface stub, always false */
    override fun stopRun(): Boolean = false

    /** 接口占位，恒返回 false / Interface stub, always false */
    override fun isRunning(): Boolean = false

    /** 接口占位，恒返回 false / Interface stub, always false */
    override fun saveCachedImage(path: String?): Boolean = false

    /** 接口占位，恒返回 null / Interface stub, always null */
    override fun nativeVersion(): String? = null

    /**
     * 逐项独立执行：一项失败不影响其余，返回实际授到的位
     * 失败不抛——app 侧据返回值决定要不要再引导用户手点
     *
     * Grants each requested item independently: one failure never blocks the rest, and the
     * return value carries the bits actually granted. Never throws — the app decides from the
     * returned bits whether to walk the user through manual granting.
     *
     * @param packageName 目标包名 / target package
     * @param uid 目标 uid / target uid
     * @param permissions 请求的 [PrivilegedGrant] 位集 / requested [PrivilegedGrant] bit set
     * @return 实际授予的 [PrivilegedGrant] 位集 / granted [PrivilegedGrant] bit set
     */
    override fun grantPermissions(packageName: String?, uid: Int, permissions: Int): Int {
        if (packageName.isNullOrBlank()) return 0
        var granted = 0
        if (permissions and PrivilegedGrant.NOTIFICATION != 0 &&
            PermissionGrantHelper.grantNotificationPermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.NOTIFICATION
        }
        if (permissions and PrivilegedGrant.BATTERY != 0 &&
            PermissionGrantHelper.grantBatteryOptimizationExemption(packageName)
        ) {
            granted = granted or PrivilegedGrant.BATTERY
        }
        if (permissions and PrivilegedGrant.BACKGROUND != 0 &&
            PermissionGrantHelper.grantBackgroundUnrestricted(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.BACKGROUND
        }
        if (permissions and PrivilegedGrant.OVERLAY != 0 &&
            PermissionGrantHelper.grantFloatingWindowPermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.OVERLAY
        }
        // 服务 id 不用过 binder 传：特权进程跑的就是这个 APK，直接引用常量即可
        if (permissions and PrivilegedGrant.ACCESSIBILITY != 0 &&
            PermissionGrantHelper.grantAccessibilityService(AccessibilityHelperService.SERVICE_ID)
        ) {
            granted = granted or PrivilegedGrant.ACCESSIBILITY
        }
        if (permissions and PrivilegedGrant.STORAGE != 0 &&
            PermissionGrantHelper.grantStoragePermission(packageName, uid)
        ) {
            granted = granted or PrivilegedGrant.STORAGE
        }
        Ln.i("$TAG: grantPermissions($packageName) requested=$permissions granted=$granted")
        return granted
    }

    /** 以特权身份查包是否存在 / Whether the package exists, checked with privileged identity */
    override fun isPackageInstalled(packageName: String): Boolean = try {
        FakeContext.get().packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        Ln.w("$TAG: isPackageInstalled: $packageName not found", e)
        false
    }

    /**
     * 逐项隔离，不共用一个 runCatching：原先四项串在一个块里，头一项抛了后面全跳过
     *
     * [ScreenManager.destroy] 尤其漏不得——它撤的是**物理主屏**的强改尺寸，
     * 漏掉的话用户会留在一块被改小的屏幕上，而且只能靠再拉一次特权进程才撤得回来。
     * 它自己按 flag 文件判要不要动手，没改过时是空操作
     *
     * Each teardown step is isolated in its own runCatching instead of one shared block: the
     * original version chained four steps together, so the first exception skipped the rest.
     *
     * [ScreenManager.destroy] must never be skipped — it reverts the forced size on the
     * **physical primary display**; forgetting it strands the user on a shrunken screen, and
     * only another privileged-process run can undo that. It is a no-op unless the flag file
     * says a size change was applied.
     */
    private fun cleanup() {
        step("sdk task repatriator") { SdkTaskRepatriator.stop() }
        step("bridge server") { BridgeServer.stop() }
        step("screen size") { ScreenManager.destroy() }
        step("power") { PowerController.destroy() }
        step("primary display") { PrimaryDisplayManager.stop() }
        step("virtual display") { VirtualDisplayManager.stop() }
    }

    /** 单步收尾：失败记日志，不中断后续步骤 / One teardown step: failures are logged without interrupting the rest */
    private inline fun step(name: String, action: () -> Unit) {
        runCatching(action).onFailure { Ln.e("$TAG: cleanup $name failed: ${it.message}") }
    }

    /**
     * app 进程消失后特权进程必须自杀
     * linkToDeath 是主路径，这里兜住「binder 还没建立就崩了」的窗口
     *
     * The privileged process must kill itself once the app process is gone. linkToDeath is the
     * primary mechanism; this poll covers the window where the app crashed before the binder
     * link was even established.
     */
    private fun startHeartbeatWatchdog() {
        Thread {
            while (!destroyed.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val pid = appPid.get()
                if (pid <= 0) continue
                if (!File("/proc/$pid").exists()) {
                    Ln.w("$TAG: app process (pid=$pid) gone, destroying remote service")
                    destroy()
                    return@Thread
                }
            }
        }.apply {
            name = "remote-heartbeat-watchdog"
            isDaemon = true
        }.start()
    }

    private companion object {
        const val TAG = "RemoteService"

        /** 心跳探测周期 / Heartbeat poll period */
        const val HEARTBEAT_INTERVAL_MS = 5_000L
    }
}
