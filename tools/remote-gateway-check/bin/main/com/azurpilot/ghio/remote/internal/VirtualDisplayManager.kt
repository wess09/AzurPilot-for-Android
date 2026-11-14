package com.azurpilot.ghio.remote.internal

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Display
import android.view.Surface
import com.azurpilot.ghio.bridge.NativeBridgeLib
import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.constant.DefaultDisplayConfig.VD_NAME
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


/**
 * 独立虚拟屏：游戏实际运行的一块 PUBLIC 展示屏，native capturer 供 surface
 *
 * BACKGROUND 模式（默认）用：建屏后游戏被 pin 到这块屏，采集、注入、看门狗都围绕
 * [getDisplayId] 工作。分辨率默认按 [DefaultDisplayConfig]（桥协议钉死），可由 app 侧经
 * setResolution 改，采集中的屏改完即重启。
 *
 * 建屏后处理旋转：ROM 把 VD 转了非零角度时先 freezeRotation 回正；物理屏本就是横屏原生的
 * 设备（如 AYN Odin2）freezeRotation 无效，退而 setForcedDisplaySize 强报横屏尺寸。
 *
 * 线程：生命周期操作由 binder 线程调用，共用 [displayLifecycleLock] 串行执行；
 * displayId 与配置通过原子量供桥线程读取。
 *
 * Standalone virtual display: the PUBLIC display the game actually runs on, with its surface
 * fed by the native capturer.
 *
 * Used in BACKGROUND mode (the default): after creation the game is pinned onto it, and
 * capture, injection, and the watchdog all revolve around [getDisplayId]. The resolution
 * defaults to [DefaultDisplayConfig] (pinned by the bridge protocol) and can be changed by the
 * app via setResolution; a live display restarts on change.
 *
 * Post-creation rotation handling: when the ROM gives the VD a non-zero rotation, freezeRotation
 * is applied first; on devices whose physical panel is landscape-native (e.g. the AYN Odin2)
 * freezeRotation does nothing, so setForcedDisplaySize is applied instead to force the
 * landscape size reporting.
 *
 * Threading: lifecycle operations run on binder threads and serialize on [displayLifecycleLock].
 * The bridge reads the display id and configuration through atomics.
 */
object VirtualDisplayManager {

    private const val STATE_IDLE = 0
    private const val STATE_CAPTURING = 1

    // 以下多为 SDK 未公开的隐藏 flag 位，按 API 级别分层补齐，见 buildDisplayFlags
    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH: Int = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL: Int = 1 shl 8
    private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS: Int = 1 shl 9
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED: Int = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP: Int = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED: Int = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED: Int = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS: Int = 1 shl 14
    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP: Int = 1 shl 15
    private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED: Int = 1 shl 16

    /** 不带系统装饰：游戏全屏跑，无状态栏 / No system decorations: the game runs fullscreen without a status bar */
    private const val VD_SYSTEM_DECORATIONS = false

    /** 屏移除时销毁其上内容 / Content on the display is destroyed when the display is removed */
    private const val VD_DESTROY_CONTENT = true

    /** 无活动虚拟屏的哨兵值 / Sentinel for "no active virtual display" */
    const val DISPLAY_NONE = -1

    /**
     * 虚拟屏配置；帧缓冲与触摸坐标空间都按它算
     *
     * Virtual display configuration; the frame buffer and the touch coordinate space are both
     * derived from it.
     *
     * @property width 帧宽（px）/ frame width (px)
     * @property height 帧高（px）/ frame height (px)
     * @property dpi 密度 / density
     */
    data class DisplayConfig(
        val width: Int = DefaultDisplayConfig.WIDTH,
        val height: Int = DefaultDisplayConfig.HEIGHT,
        val dpi: Int = DefaultDisplayConfig.DPI
    )

    private val state = AtomicInteger(STATE_IDLE)
    private val config = AtomicReference(DisplayConfig())
    // 0 在每次建屏时解析主屏 DPI，避免保存一个过时的设备密度。
    private val requestedDpi = AtomicInteger(0)
    private val requestedRefreshRate = AtomicReference(0f)

    /**
     * 设定期刷新率，下次建（重）建屏生效；0 表示交给系统默认
     *
     * Sets the desired refresh rate, effective on the next display (re)creation; 0 leaves it
     * to the system default.
     *
     * @throws IllegalArgumentException rate 非有限值或为负 / when the rate is not finite or is negative
     */
    fun setRefreshRate(rate: Float) = synchronized(displayLifecycleLock) {
        require(rate.isFinite() && rate >= 0f)
        requestedRefreshRate.set(rate)
    }
    private val displayId = AtomicInteger(DISPLAY_NONE)
    private val virtualDisplay = AtomicReference<VirtualDisplay?>()
    // 两种模式共用 native 采集器，空闲模式的 stop 不能释放另一模式的资源。
    private var capturerOwned = false

    private val monitorSurface = AtomicReference<Surface?>()

    /**
     * 挂 app 侧预览 surface；换 surface 时释放旧的
     *
     * Attaches the app-side preview surface; the old one is released on replacement.
     */
    fun setMonitorSurface(surface: Surface?) = synchronized(displayLifecycleLock) {
        val old = monitorSurface.getAndSet(surface)
        if (old != null && old != surface) {
            old.release()
            Ln.i("Old monitor surface released")
        }
        Ln.i("setMonitorSurface: old=${old != null}, new=${surface != null}")
    }

    /**
     * 建虚拟屏并启动 native 采集；已在采集时幂等返回当前 displayId
     *
     * Creates the virtual display and starts native capture; idempotent — returns the current
     * display id when already capturing.
     *
     * @return displayId；失败为 [DISPLAY_NONE] / the display id, or [DISPLAY_NONE] on failure
     */
    fun start(): Int = synchronized(displayLifecycleLock) {
        if (state.get() == STATE_CAPTURING) {
            Ln.i("VirtualDisplayManager reused, displayId=${displayId.get()}")
            return@synchronized displayId.get()
        }
        startInternal()
    }

    /**
     * 停采集、释放虚拟屏与预览 surface；未采集时幂等
     *
     * Stops capture and releases the virtual display and the preview surface; idempotent when
     * idle.
     */
    fun stop() = synchronized(displayLifecycleLock) {
        state.set(STATE_IDLE)
        try {
            releaseResources()
        } finally {
            monitorSurface.getAndSet(null)?.release()
        }
        Ln.i("VirtualDisplayManager stopped")
    }

    /** 采集中的重建（分辨率 / 尺寸变化后）；空闲时不动 / Rebuilds while capturing (after a resolution / size change); no-op when idle */
    fun restart() = synchronized(displayLifecycleLock) {
        if (state.get() != STATE_CAPTURING) {
            return@synchronized
        }
        state.set(STATE_IDLE)
        releaseResources()
        startInternal()
        Unit
    }

    /**
     * 改虚拟屏分辨率；DPI 为 0 时跟随设备，采集中的屏在配置实际变化时才重启。
     *
     * Changes the virtual display resolution. A DPI of 0 follows the device; a live display restarts
     * only when the configuration actually changes.
     */
    fun setResolution(width: Int, height: Int, dpi: Int = requestedDpi.get()) {
        synchronized(displayLifecycleLock) {
            require(width > 0 && height > 0 && (dpi == 0 || dpi in 72..960))
            requestedDpi.set(dpi)
            val newConfig = DisplayConfig(width, height, resolveDpi(dpi))
            val oldConfig = config.getAndSet(newConfig)
            if (state.get() == STATE_CAPTURING && oldConfig != newConfig) {
                Ln.i("Display config changed: $oldConfig -> $newConfig, restart")
                restart()
            }
        }
    }

    /** 当前虚拟屏 displayId，未建为 [DISPLAY_NONE] / Current virtual display id, or [DISPLAY_NONE] when none */
    fun getDisplayId(): Int = displayId.get()

    /**
     * 帧缓冲与触摸坐标空间都按它算，交给 native controller 的 screen_resolution 必须与之一致
     *
     * The frame buffer and the touch coordinate space are both derived from it; the
     * screen_resolution handed to the native controller must match it exactly.
     */
    fun getConfig(): DisplayConfig = config.get()

    /**
     * 持生命周期锁建屏并启动采集；失败清理资源，普通异常返回 [DISPLAY_NONE]。
     *
     * Creates the display and starts capture under the lifecycle lock. Failures release resources;
     * ordinary exceptions return [DISPLAY_NONE].
     */
    private fun startInternal(): Int {
        try {
            // 释放失败时保留的旧屏必须先清理，不能被新引用覆盖。
            if (virtualDisplay.get() != null || capturerOwned) releaseResources()
            val cfg = config.get().copy(dpi = resolveDpi(requestedDpi.get()))
            config.set(cfg)
            capturerOwned = true
            val surface = checkNotNull(NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height)) {
                "Native capturer returned no surface"
            }
            createVirtualDisplay(surface, cfg)
            state.set(STATE_CAPTURING)

            Ln.i("VirtualDisplayManager started, displayId=${displayId.get()}")
            return displayId.get()
        } catch (e: Throwable) {
            Ln.e("VirtualDisplayManager start failed", e)
            state.set(STATE_IDLE)
            runCatching { releaseResources() }
                .onFailure { Ln.e("VirtualDisplayManager start cleanup failed", it) }
            if (e is Error) throw e
            return DISPLAY_NONE
        }
    }

    private fun releaseResources() {
        displayId.set(DISPLAY_NONE)
        try {
            virtualDisplay.get()?.let { vd ->
                vd.release()
                virtualDisplay.set(null)
            }
        } finally {
            if (capturerOwned) {
                NativeBridgeLib.releaseNativeCapturer()
                capturerOwned = false
            }
        }
    }

    /** 建 VD 并处理旋转怪癖（见实现处注释）；成功后记录 displayId / Creates the VD and handles rotation quirks (see the implementation notes); records the display id on success */
    private fun createVirtualDisplay(surface: Surface?, cfg: DisplayConfig) {
        val flags = buildDisplayFlags()
        val wm = ServiceManager.getWindowManager()
        val physicalRotation = runCatching { wm.getRotation() }.getOrDefault(-1)
        Ln.i("Physical display rotation: $physicalRotation")

        val vd = checkNotNull(
            ServiceManager.getDisplayManager().createNewVirtualDisplay(
                VD_NAME,
                cfg.width,
                cfg.height,
                cfg.dpi,
                surface,
                flags,
                requestedRefreshRate.get()
            )
        ) { "DisplayManager returned no virtual display" }
        virtualDisplay.set(vd)
        val vdId = vd.display.displayId
        displayId.set(vdId)

        val d = vd.display
        Ln.i(
            "VD created: id=$vdId" +
            ", configured=${cfg.width}x${cfg.height}" +
            ", dpi=${cfg.dpi}, requestedDpi=${requestedDpi.get()}" +
            ", actual=${d.width}x${d.height}" +
            ", rotation=${d.rotation}" +
            ", requestedRefreshRate=${requestedRefreshRate.get()}, refreshRate=${d.refreshRate}" +
            ", flags=0x${flags.toString(16)}"
        )

        if (d.rotation != Surface.ROTATION_0) {
            // 所有旋转非零的情况都先尝试 freezeRotation
            runCatching {
                wm.freezeRotation(vdId, Surface.ROTATION_0)
                Ln.i("freezeRotation done, post-freeze rotation=${vd.display.rotation}")
            }.onFailure { e -> Ln.w("freezeRotation failed: ${e.message}") }

            if (physicalRotation == Surface.ROTATION_0) {
                // 物理屏处于自然方向（rotation=0）而 VD 却有旋转角，
                // 这是横屏原生设备如AYN Odin2的典型特征：
                // 此类设备的定制 ROM 对二级显示调 freezeRotation 无效，
                // 额外调 setForcedDisplaySize 强制 VD 向内部 app 上报横屏尺寸。
                Ln.w(
                    "Landscape-native device detected (physRot=0, vdRot=${d.rotation}), " +
                    "applying setForcedDisplaySize"
                )
                runCatching {
                    wm.setForcedDisplaySize(vdId, cfg.width, cfg.height)
                    Ln.i("setForcedDisplaySize(${cfg.width}x${cfg.height}) applied")
                }.onFailure { e -> Ln.w("setForcedDisplaySize failed: ${e.message}") }
            }
        }
    }

    private fun resolveDpi(dpi: Int): Int = if (dpi != 0) dpi else {
        ServiceManager.getDisplayManager().getDisplayInfo(Display.DEFAULT_DISPLAY)?.dpi
            ?.takeIf { it > 0 } ?: DefaultDisplayConfig.DPI
    }

    /**
     * 组装建屏 flag：基础四件套（PUBLIC / PRESENTATION / OWN_CONTENT_ONLY / SUPPORTS_TOUCH）
     * 让 VD 能展示并接收触摸；API 33+ 补 TRUSTED / OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED /
     * TOUCH_FEEDBACK_DISABLED，API 34+ 再补 OWN_FOCUS / DEVICE_DISPLAY_GROUP /
     * STEAL_TOP_FOCUS_DISABLED，让游戏把 VD 当可信屏正常运行
     */
    private fun buildDisplayFlags(): Int {
        var flags = (VIRTUAL_DISPLAY_FLAG_PUBLIC
                or VIRTUAL_DISPLAY_FLAG_PRESENTATION
                or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH)

        if (VD_DESTROY_CONTENT) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
        }
        if (VD_SYSTEM_DECORATIONS) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                        or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
                        or VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED)
            }
        }
        return flags
    }
}
