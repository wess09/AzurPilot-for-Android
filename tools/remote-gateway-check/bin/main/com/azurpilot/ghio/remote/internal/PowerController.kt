package com.azurpilot.ghio.remote.internal

import android.os.Build
import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.constant.ShellDirs
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.DisplayControl
import com.azurpilot.ghio.third.wrappers.ServiceManager
import com.azurpilot.ghio.third.wrappers.SurfaceControl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 物理屏电源控制 + userActivity 熄屏保活
 *
 * 经 SurfaceControl / DisplayControl 隐藏 API 对每块物理屏下发 power mode；开关状态同步落
 * flag 文件（[ShellDirs.POWER_OFF_FLAG]，/data/local/tmp 跨进程可见）——特权进程异常退出后，
 * 下一轮 [destroy] 仍能据 flag 恢复亮屏。
 *
 * userActivity 保活：虚拟屏采集期间定期替目标上报用户活动，压住系统自动熄屏；
 * 独立守护线程实现，起停全靠 keepAliveDisplayId 原子量，不直接 interrupt。
 *
 * 线程：setDisplayPower 由 binder 线程调用；保活线程自起自停。
 *
 * Physical screen power control plus a userActivity screen-on keep-alive.
 *
 * The power mode is pushed to every physical display via the SurfaceControl / DisplayControl
 * hidden APIs; the on/off state is mirrored into a flag file ([ShellDirs.POWER_OFF_FLAG],
 * under /data/local/tmp, visible across processes) so that after an abnormal privileged-process
 * exit the next [destroy] can still restore the screen from the flag.
 *
 * userActivity keep-alive: while the virtual display is being captured, user-activity events
 * are reported on the target's behalf to hold off the system's auto sleep; implemented as its
 * own daemon thread, started and stopped purely via the keepAliveDisplayId atomic, never
 * interrupted directly.
 *
 * Threading: setDisplayPower runs on binder threads; the keep-alive thread manages itself.
 */
object PowerController {
    private const val TAG = "PowerController"

    /** userActivity 上报节奏；须小于系统熄屏超时才压得住 / userActivity cadence; must stay below the system screen timeout to hold sleep off */
    private const val USER_ACTIVITY_INTERVAL_MS = 4_000L
    private val file = ShellDirs.POWER_OFF_FLAG

    private val keepAliveDisplayId = AtomicInteger(DefaultDisplayConfig.DISPLAY_NONE)
    private val keepAliveRunning = AtomicBoolean(false)

    /**
     * 息屏状态 flag（跨进程）：true 表示本控制器把物理屏关了
     *
     * The screen-power-off flag (cross-process): true means this controller powered the
     * physical screen off.
     */
    var flag: Boolean
        get() = runCatching { file.exists() }.getOrDefault(false)
        set(value) {
            runCatching {
                if (value) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                } else {
                    file.delete()
                }
            }
        }

    /**
     * 开关物理屏电源；先落 flag 再动手，崩溃后 [destroy] 能据 flag 恢复
     *
     * Powers the physical screen on or off; the flag is written before acting so [destroy] can
     * recover after a crash.
     *
     * @return 所有目标物理屏均设置成功才为 true / true only when every targeted physical display was set successfully
     */
    fun setDisplayPower(on: Boolean): Boolean {
        flag = !on
        return setDisplayPowerInternal(on)
    }

    /**
     * Android 10+ 走多屏路径：枚举所有物理 display 逐块下发（DisplayControl / SurfaceControl
     * 二选一，看 ROM 暴露面）；Honor + Android 14 且 getBuildInDisplay 可用时退回单
     * built-in 屏路径（厂商适配）
     */
    private fun setDisplayPowerInternal(on: Boolean): Boolean {
        var applyToMultiPhysicalDisplays =
            Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10

        if (applyToMultiPhysicalDisplays
            && Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14 && Build.BRAND.equals(
                "honor",
                ignoreCase = true
            )
            && SurfaceControl.hasGetBuildInDisplayMethod()
        ) {
            applyToMultiPhysicalDisplays = false
        }

        val mode: Int =
            if (on) SurfaceControl.POWER_MODE_NORMAL else SurfaceControl.POWER_MODE_OFF
        if (applyToMultiPhysicalDisplays) {
            val useDisplayControl =
                Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14 && !SurfaceControl.hasGetPhysicalDisplayIdsMethod()

            val physicalDisplayIds =
                if (useDisplayControl) DisplayControl.getPhysicalDisplayIds() else SurfaceControl.getPhysicalDisplayIds()
            if (physicalDisplayIds == null) {
                Ln.e("Could not get physical display ids")
                return false
            }

            var allOk = true
            for (physicalDisplayId in physicalDisplayIds) {
                val binder = if (useDisplayControl) DisplayControl.getPhysicalDisplayToken(
                    physicalDisplayId
                ) else SurfaceControl.getPhysicalDisplayToken(physicalDisplayId)
                allOk = allOk and SurfaceControl.setDisplayPowerMode(binder, mode)
            }
            return allOk
        }

        val d = SurfaceControl.getBuiltInDisplay()
        if (d == null) {
            Ln.e("Could not get built-in display")
            return false
        }
        return SurfaceControl.setDisplayPowerMode(d, mode)
    }

    /**
     * 启动 userActivity 保活：每 [USER_ACTIVITY_INTERVAL_MS] 向 PowerManager 报一次用户活动
     *
     * 幂等（CAS 防重复起线程）；停止走 [stopUserActivityKeepAlive]，线程看到 displayId
     * 归位 DISPLAY_NONE 后自行退出。
     *
     * Starts the userActivity keep-alive: reports a user-activity event to the PowerManager
     * every [USER_ACTIVITY_INTERVAL_MS].
     *
     * Idempotent (a CAS prevents duplicate threads); stop via [stopUserActivityKeepAlive] —
     * the thread exits on its own once it sees the display id return to DISPLAY_NONE.
     */
    fun startUserActivityKeepAlive(displayId: Int) {
        keepAliveDisplayId.set(displayId)
        if (!keepAliveRunning.compareAndSet(false, true)) return
        Thread {
            Ln.i("$TAG: userActivity keep-alive started, displayId=$displayId")
            while (true) {
                val id = keepAliveDisplayId.get()
                if (id == DefaultDisplayConfig.DISPLAY_NONE) break
                try {
                    Thread.sleep(USER_ACTIVITY_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val currentId = keepAliveDisplayId.get()
                if (currentId == DefaultDisplayConfig.DISPLAY_NONE) break
                runCatching { ServiceManager.getPowerManager().userActivity(currentId) }
                    .onFailure { Ln.e("$TAG: userActivity failed", it) }
            }
            keepAliveRunning.set(false)
            Ln.i("$TAG: userActivity keep-alive stopped")
        }.apply {
            name = "power-user-activity-keepalive"
            isDaemon = true
        }.start()
    }

    /** 请求保活线程退出：置空 displayId 而非 interrupt / Requests the keep-alive thread to exit by clearing the display id rather than interrupting */
    fun stopUserActivityKeepAlive() {
        keepAliveDisplayId.set(DefaultDisplayConfig.DISPLAY_NONE)
    }

    /**
     * 收尾：停保活线程；若 flag 显示屏是被本进程关的，应急恢复亮屏
     *
     * Teardown: stops the keep-alive thread; if the flag says this process powered the screen
     * off, restores it as an emergency measure.
     */
    fun destroy() {
        stopUserActivityKeepAlive()
        if (flag) {
            Ln.i("$TAG: Emergency recovering screen power...")
            runCatching {
                setDisplayPower(true)
            }.onFailure {
                Ln.e("$TAG: Failed to recover screen power: ${it.message}")
            }
        }
    }
}