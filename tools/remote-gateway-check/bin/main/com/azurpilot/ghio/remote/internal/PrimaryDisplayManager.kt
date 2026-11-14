package com.azurpilot.ghio.remote.internal

import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.view.Display
import android.view.Surface
import com.azurpilot.ghio.bridge.NativeBridgeLib
import com.azurpilot.ghio.constant.DefaultDisplayConfig.VD_NAME
import com.azurpilot.ghio.third.DisplayInfo
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager
import com.azurpilot.ghio.third.wrappers.SurfaceControl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 主屏采集：把物理主屏（[DISPLAY_ID]）镜像到一块由 native capturer 供 surface 的虚拟屏
 *
 * PRIMARY 模式（游戏跑在真实主屏上）用。建屏优先 DisplayManager API，失败退
 * SurfaceControl createDisplay + 事务投影；两条路都死抛 AssertionError，让调用方明确失败。
 *
 * 主屏尺寸变化（旋转 / 分辨率）触发重启采集；监听回调跑在专用 [FrameCaptureHelper] 线程，
 * start/stop/restart 由 binder 线程调用，所有生命周期操作共用 [displayLifecycleLock]。
 *
 * Primary display capture: mirrors the physical primary display ([DISPLAY_ID]) into a virtual
 * display whose surface is fed by the native capturer.
 *
 * Used in PRIMARY mode (the game runs on the real primary display). DisplayManager creation is
 * preferred, falling back to SurfaceControl createDisplay plus a transaction projection; if
 * both die an AssertionError is thrown so the caller fails loudly.
 *
 * A primary display size change (rotation / resolution) restarts capture. Listener callbacks
 * run on a dedicated [FrameCaptureHelper] thread; start/stop/restart are called from binder
 * threads, with all lifecycle operations serialized on [displayLifecycleLock].
 */
object PrimaryDisplayManager {

    private const val STATE_IDLE = 0
    private const val STATE_CAPTURING = 1

    private val state = AtomicInteger(STATE_IDLE)
    private val display = AtomicReference<IBinder?>()
    private val virtualDisplay = AtomicReference<VirtualDisplay?>()
    private val setup = AtomicBoolean(false)
    // 空闲主屏模式不拥有 native 采集器，不能释放后台模式正在使用的资源。
    private var capturerOwned = false

    /** 恒为物理主屏 / Always the physical default display */
    const val DISPLAY_ID = Display.DEFAULT_DISPLAY

    private val displayInfo = AtomicReference<DisplayInfo>()


    private fun getDisplayInfo(): DisplayInfo {
        return ServiceManager.getDisplayManager().getDisplayInfo(DISPLAY_ID)!!
    }

    private val listenerHandler by lazy {
        FrameCaptureHelper.createCaptureHandler("DisplayListener")
    }


    /** 只关心主屏：尺寸变了就重启采集 / Only the primary display matters: a size change restarts capture */
    private fun onDisplayChange(displayId: Int) = synchronized(displayLifecycleLock) {
        if (displayId != DISPLAY_ID) {
            return@synchronized
        }
        val info = getDisplayInfo()
        val oldInfo = displayInfo.get()
        if (info.size != oldInfo?.size) {
            Ln.i("Display changed: ${oldInfo?.size} -> ${info.size}, triggering restart")
            displayInfo.set(info)
            restart()
        }
    }

    /** 注册 DisplayListener 并立即对齐一次当前尺寸 / Registers the DisplayListener and syncs the current size once immediately */
    private fun setup() {
        ServiceManager.getDisplayManager().registerDisplayListener({
            onDisplayChange(it)
        }, listenerHandler)
        onDisplayChange(DISPLAY_ID)
    }

    /**
     * 开始采集主屏；重复调用幂等（已采集时直接返回 [DISPLAY_ID]）
     *
     * Starts primary display capture; idempotent — returns [DISPLAY_ID] immediately when
     * already capturing.
     */
    fun start(): Int = synchronized(displayLifecycleLock) {
        if (!setup.get()) {
            setup()
            setup.set(true)
        }
        if (state.get() == STATE_CAPTURING) {
            Ln.w("start: already capturing")
            return@synchronized DISPLAY_ID
        }
        startInternal()
    }

    /** 停止采集并释放镜像屏与 native capturer / Stops capture and releases the mirror display and the native capturer */
    fun stop() = synchronized(displayLifecycleLock) {
        state.set(STATE_IDLE)
        releaseResources()
    }

    /** 采集中的尺寸变化重建链路；空闲时不动 / Rebuilds the pipeline on a size change while capturing; no-op when idle */
    fun restart() = synchronized(displayLifecycleLock) {
        if (state.get() != STATE_CAPTURING) {
            return@synchronized
        }
        state.set(STATE_IDLE)
        releaseResources()
        startInternal()
        Unit
    }

    private fun startInternal(): Int {
        try {
            if (virtualDisplay.get() != null || display.get() != null || capturerOwned) {
                releaseResources()
            }
            val info = displayInfo.get()
            val width = info.size.width
            val height = info.size.height
            capturerOwned = true
            val surface = checkNotNull(NativeBridgeLib.setupNativeCapturer(width, height)) {
                "Native capturer returned no surface"
            }
            createVirtualDisplay(surface, info)
            state.set(STATE_CAPTURING)
            return DISPLAY_ID
        } catch (failure: Throwable) {
            state.set(STATE_IDLE)
            runCatching { releaseResources() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
    }

    private fun releaseResources() {
        try {
            virtualDisplay.get()?.let { vd ->
                vd.release()
                virtualDisplay.set(null)
            }
        } finally {
            try {
                display.get()?.let { token ->
                    SurfaceControl.destroyDisplay(token)
                    display.set(null)
                }
            } finally {
                if (capturerOwned) {
                    NativeBridgeLib.releaseNativeCapturer()
                    capturerOwned = false
                }
            }
        }
    }

    /**
     * DisplayManager API 优先；失败退 SurfaceControl：createDisplay + 事务里一次设好
     * surface / 投影 / layerStack。两条路都失败抛 AssertionError
     */
    private fun createVirtualDisplay(surface: Surface?, displayInfo: DisplayInfo) {
        val width = displayInfo.size.width
        val height = displayInfo.size.height
        try {
            val vd = checkNotNull(
                ServiceManager.getDisplayManager().createVirtualDisplay(
                    VD_NAME,
                    width,
                    height,
                    DISPLAY_ID,
                    surface
                )
            ) { "DisplayManager returned no mirror display" }
            virtualDisplay.set(vd)
            Ln.d("Display: using DisplayManager API")
        } catch (displayManagerException: Exception) {
            try {
                val vd = createDisplay()
                display.set(vd)

                val deviceSize = displayInfo.size
                val layerStack = displayInfo.layerStack
                val rect = deviceSize.toRect()
                setDisplaySurface(vd, surface, rect, rect, layerStack)
                Ln.d("Display: using SurfaceControl API")
            } catch (surfaceControlException: Exception) {
                Ln.e("Could not create display using DisplayManager", displayManagerException)
                Ln.e("Could not create display using SurfaceControl", surfaceControlException)
                throw AssertionError("Could not create display")
            }
        }
    }

    /**
     * Android 12 preview 起 shell 身份不再能创建 secure display，故固定 secure=false
     *
     * Secure displays can no longer be created with shell permissions since the Android 12
     * preview, hence the fixed secure=false.
     */
    private fun createDisplay(): IBinder {
        return SurfaceControl.createDisplay(VD_NAME, false)
    }

    /** SurfaceControl 事务内一次设好 surface / 投影 / layerStack / Sets surface, projection, and layer stack in one SurfaceControl transaction */
    private fun setDisplaySurface(
        display: IBinder?,
        surface: Surface?,
        deviceRect: Rect?,
        displayRect: Rect?,
        layerStack: Int
    ) {
        SurfaceControl.openTransaction()
        try {
            SurfaceControl.setDisplaySurface(display, surface)
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect)
            SurfaceControl.setDisplayLayerStack(display, layerStack)
        } finally {
            SurfaceControl.closeTransaction()
        }
    }
}
