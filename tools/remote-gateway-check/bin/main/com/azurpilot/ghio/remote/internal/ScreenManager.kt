package com.azurpilot.ghio.remote.internal

import android.view.Display
import com.azurpilot.ghio.constant.ShellDirs
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager

/**
 * 物理主屏强制分辨率的设置与应急恢复
 *
 * 经 WindowManager 隐藏包装改主屏尺寸；是否改过同步落 flag 文件
 * （[ShellDirs.SCREEN_FLAG]，/data/local/tmp 跨进程可见）——特权进程中途被杀时，
 * 下一轮 [destroy] 仍能据 flag 把用户的屏幕尺寸撤回来。
 *
 * Forced display size for the physical primary display, with emergency recovery.
 *
 * The size change goes through the WindowManager hidden wrapper; whether one is applied is
 * mirrored into a flag file ([ShellDirs.SCREEN_FLAG], under /data/local/tmp, visible across
 * processes) so that if the privileged process is killed mid-flight, the next [destroy] can
 * still revert the user's screen size based on the flag.
 */
object ScreenManager {
    private const val TAG = "ScreenManager"
    private val _flag = ShellDirs.SCREEN_FLAG

    /**
     * 强改分辨率状态 flag（跨进程）：true 表示当前主屏被改过尺寸
     *
     * The forced-size state flag (cross-process): true means the primary display size is
     * currently overridden.
     */
    var flag: Boolean
        get() = runCatching { _flag.exists() }.onFailure {
            Ln.e("$TAG: Failed to check if alive flag file exists: ${it.message}")
            Ln.e(it.stackTraceToString())
        }.getOrDefault(false)
        set(value) {
            runCatching {
                if (value) {
                    _flag.parentFile?.mkdirs()
                    _flag.createNewFile()
                } else {
                    _flag.delete()
                }
            }.onFailure {
                Ln.e("$TAG: Failed to set alive flag file: ${it.message}")
                Ln.e(it.stackTraceToString())
            }
        }

    /**
     * 强制主屏尺寸并落 flag
     *
     * Forces the primary display size and sets the flag.
     *
     * @return WindowManager 侧是否接受 / whether the WindowManager accepted it
     */
    fun setForcedDisplaySize(width: Int, height: Int): Boolean {
        flag = true
        return ServiceManager.getWindowManager()
            .setForcedDisplaySize(Display.DEFAULT_DISPLAY, width, height)
    }

    /** 撤销强制尺寸并清 flag / Clears the forced size and the flag */
    fun clearForcedDisplaySize(): Boolean {
        flag = false
        return ServiceManager.getWindowManager().clearForcedDisplaySize(Display.DEFAULT_DISPLAY)
    }

    /**
     * 应急恢复：flag 显示改过尺寸才动手，没改过是空操作
     *
     * Emergency recovery: acts only when the flag says a size change was applied; otherwise a
     * no-op.
     */
    fun destroy() {
        if (flag) {
            Ln.i("$TAG: Emergency recovering display size...")
            runCatching {
                clearForcedDisplaySize()
            }.onFailure {
                Ln.e("$TAG: Failed to clear forced display size: ${it.message}")
            }
        }
    }
}