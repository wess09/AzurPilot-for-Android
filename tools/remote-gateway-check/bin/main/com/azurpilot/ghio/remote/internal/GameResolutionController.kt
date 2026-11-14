package com.azurpilot.ghio.remote.internal

import android.os.Build
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager

/**
 * 在游戏启动前关闭目标包的兼容缩放，让虚拟屏、游戏和采集缓冲共用 1280×720。
 *
 * 特权线程调用并持显示生命周期锁；拒绝主屏和失效 ID。仅修改被启动的包，配置变化后
 * 强停旧游戏进程，避免缓存的 CompatibilityInfo 将物理屏缩放带到虚拟屏。
 *
 * Disables package compatibility scaling before launch so the display, game, and capture buffer
 * share 1280×720. Privileged threads hold the display lifecycle lock and reject primary or stale IDs.
 * Only the launched package is changed. An old game process is force-stopped after a change so its
 * cached CompatibilityInfo cannot carry primary-display scaling onto the virtual display.
 */
internal object GameResolutionController {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /**
     * 准备当前虚拟屏上的游戏原生渲染；ROM 拒绝修改时抛异常，由启动方停止任务。
     *
     * Prepares native game rendering on the current virtual display. ROM rejection throws so the
     * launcher can stop the task.
     */
    fun prepare(packageName: String, displayId: Int) = synchronized(displayLifecycleLock) {
        require(packageName.length <= 255 && packagePattern.matches(packageName)) { "Invalid package" }
        check(displayId > 0 && displayId == VirtualDisplayManager.getDisplayId()) {
            "No current virtual display $displayId"
        }
        val cfg = VirtualDisplayManager.getConfig()
        check(cfg.width == DefaultDisplayConfig.WIDTH && cfg.height == DefaultDisplayConfig.HEIGHT) {
            "Native game rendering requires 1280x720"
        }
        @Suppress("DEPRECATION")
        FakeContext.get().packageManager.getApplicationInfo(packageName, 0)

        val aps = ServiceManager.getOptionalService("aps_service")
        val apsChanged = GameResolutionPolicy.disableApsScaling(aps, packageName)
        val compatService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "activity_task"
        } else {
            "activity"
        }
        val compat = checkNotNull(ServiceManager.getOptionalService(compatService)) {
            "Screen compatibility service unavailable"
        }
        val compatChanged = GameResolutionPolicy.disableScreenCompat(compat, packageName)
        // APS 配置可能已被上次启动修正，但现有游戏进程仍缓存旧的兼容比例。
        if (aps != null || compatChanged) {
            ServiceManager.getActivityManager().forceStopPackage(packageName)
        }
        Ln.i("Native game resolution prepared: package=$packageName display=$displayId " +
            "size=${cfg.width}x${cfg.height} dpi=${cfg.dpi} " +
            "aps=${aps != null} apsChanged=$apsChanged compatChanged=$compatChanged")
    }
}
