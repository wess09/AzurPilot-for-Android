package com.azurpilot.ghio.third.wrappers

import android.os.Build
import android.os.IInterface
import android.os.SystemClock

import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln

import java.lang.reflect.Method

/**
 * 隐藏 binder 服务 `IPowerManager`（"power"）的反射包装
 *
 * 提供查询交互状态（[isScreenOn]）、喂用户活动（[userActivity]）、亮屏
 * （[wakeUp]）与息屏（[goToSleep]）四类能力；隐藏 API 在 targetSdk 下没有
 * 编译期入口，故经 [ServiceManager.getService] 拿 binder 后按"方法名+签名"
 * 反射调用，不同版本签名由各私有解析器探测并缓存。[wakeUp] / [goToSleep] /
 * [resolveWakeUpVariant] 为本工程在 scrcpy 基础上扩展，服务于亮屏-解锁-熄屏
 * 自动化链。
 *
 * 实例经 [PowerManager.create] 创建；方法面向特权进程工作线程（binder 调用阻塞）。
 *
 * 从 scrcpy 服务端的 `third/wrappers/PowerManager.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * Reflection wrapper around the hidden `IPowerManager` binder ("power").
 *
 * Provides four capabilities: querying the interactive state ([isScreenOn]),
 * feeding user activity ([userActivity]), waking the screen ([wakeUp]) and
 * putting it to sleep ([goToSleep]). Hidden APIs have no compile-time surface
 * at targetSdk, so the binder from [ServiceManager.getService] is invoked
 * reflectively by name + signature, with per-version signatures probed and
 * cached by the private resolvers. [wakeUp] / [goToSleep] /
 * [resolveWakeUpVariant] are this project's extensions on top of scrcpy,
 * serving the wake-unlock-sleep automation chain.
 *
 * Instances come from [PowerManager.create]; methods target
 * privileged-process worker threads (binder calls block).
 *
 * Ported from scrcpy's server `third/wrappers/PowerManager.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
class PowerManager private constructor(private val manager: IInterface) {

    private var isScreenOnMethod: Method? = null
    private var userActivityMethod: Method? = null

    /**
     * 懒解析并按 API 34 边界选择 `isDisplayInteractive(displayId)` 或无参
     * `isInteractive`
     *
     * Lazily resolves `isDisplayInteractive(displayId)` on API 34+, else the
     * no-arg `isInteractive`.
     */
    @Throws(NoSuchMethodException::class)
    private fun getIsScreenOnMethod(): Method {
        var method = isScreenOnMethod
        if (method == null) {
            method = if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                manager.javaClass.getMethod("isDisplayInteractive", Int::class.java)
            } else {
                manager.javaClass.getMethod("isInteractive")
            }
            isScreenOnMethod = method
        }
        return method
    }

    /**
     * 查询显示器当前是否可交互（屏幕亮着且未挂起）
     *
     * API 34+ 走按显示器查询的 `isDisplayInteractive`；反射失败返回 false。
     *
     * Returns whether the display is currently interactive (on and not
     * suspended).
     *
     * Uses the per-display `isDisplayInteractive` from API 34 on; returns false
     * when reflection fails.
     */
    fun isScreenOn(displayId: Int): Boolean {
        return try {
            val method = getIsScreenOnMethod()
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                method.invoke(manager, displayId) as Boolean
            } else {
                method.invoke(manager) as Boolean
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            false
        }
    }

    /**
     * 懒解析并按 API 31 边界选择带/不带 displayId 的 `userActivity` 重载
     *
     * Lazily resolves the `userActivity` overload with or without displayId
     * across the API 31 boundary.
     */
    @Throws(NoSuchMethodException::class)
    private fun getUserActivityMethod(): Method {
        var method = userActivityMethod
        if (method == null) {
            method = if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                // API 31+ 签名：userActivity(int displayId, long time, int event, int flags)
                manager.javaClass.getMethod("userActivity", Int::class.java, Long::class.java, Int::class.java, Int::class.java)
            } else {
                // API 31 以下签名：userActivity(long time, int event, int flags)
                manager.javaClass.getMethod("userActivity", Long::class.java, Int::class.java, Int::class.java)
            }
            userActivityMethod = method
        }
        return method
    }

    /**
     * 喂一次用户活动，重置指定显示器的熄屏倒计时
     *
     * 以 `USER_ACTIVITY_EVENT_OTHER`、flags 0 调用隐藏 `userActivity`；用于
     * 自动化期间防止系统把虚拟显示器判为闲置而息屏。失败仅记日志。
     *
     * Feeds one user-activity event, resetting the screen-off countdown of the
     * given display.
     *
     * Invokes the hidden `userActivity` with `USER_ACTIVITY_EVENT_OTHER` and
     * flags 0; keeps the system from suspending the virtual display as idle
     * during automation. Failures are only logged.
     */
    fun userActivity(displayId: Int) {
        try {
            val method = getUserActivityMethod()
            val time = SystemClock.uptimeMillis()
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                method.invoke(manager, displayId, time, USER_ACTIVITY_EVENT_OTHER, 0)
                return
            }
            method.invoke(manager, time, USER_ACTIVITY_EVENT_OTHER, 0)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    private var wakeUpMethod: Method? = null

    // 反射命中的 wakeUp 重载版本：0 = API 29+ 四参；1 = API 28 三参；2 = 单参兜底；-1 = 未解析
    private var wakeUpMethodVersion = -1

    /**
     * 懒解析并按版本选择 `wakeUp` 重载（新版带 reason/details/opPackageName）
     *
     * Lazily resolves the `wakeUp` overload per version (newer ones carry
     * reason/details/opPackageName).
     */
    @Throws(NoSuchMethodException::class)
    private fun getWakeUpMethod(): Method {
        var method = wakeUpMethod
        if (method == null) {
            val cls = manager.javaClass
            try {
                // API 29+：wakeUp(long time, int reason, String details, String opPackageName)
                method = cls.getMethod("wakeUp", Long::class.java, Int::class.java, String::class.java, String::class.java)
                wakeUpMethodVersion = 0
            } catch (e1: NoSuchMethodException) {
                try {
                    // API 28：wakeUp(long time, String reason, String opPackageName)
                    method = cls.getMethod("wakeUp", Long::class.java, String::class.java, String::class.java)
                    wakeUpMethodVersion = 1
                } catch (e2: NoSuchMethodException) {
                    // 兜底：wakeUp(long time)
                    method = cls.getMethod("wakeUp", Long::class.java)
                    wakeUpMethodVersion = 2
                }
            }
            wakeUpMethod = method
        }
        return method
    }

    /**
     * 探测当前设备命中的 wakeUp 重载版本
     *
     * 返回值同 [wakeUpMethodVersion]：0/1/2 为各版本重载，-1 表示一个都找不到
     * （调用方可据此提前短路亮屏链路）。
     *
     * Probes which wakeUp overload the current device resolves to.
     *
     * Returns the same coding as [wakeUpMethodVersion]: 0/1/2 for the per-version
     * overloads, -1 when none exists (callers can short-circuit the wake chain
     * early on that).
     */
    fun resolveWakeUpVariant(): Int {
        return try {
            getWakeUpMethod()
            wakeUpMethodVersion
        } catch (e: NoSuchMethodException) {
            -1
        }
    }

    /**
     * 请求亮屏
     *
     * 走 IPowerManager 的 wakeUp 直调，比注入 KEYCODE_WAKEUP 可靠：不经过
     * PhoneWindowManager 的按键策略，不会被键盘锁策略拦截。
     *
     * Requests the screen to wake up.
     *
     * Calls IPowerManager.wakeUp directly, which is more reliable than
     * injecting KEYCODE_WAKEUP: it bypasses the PhoneWindowManager key policy
     * and cannot be blocked by it.
     *
     * @return 反射调用是否成功；不代表屏幕已亮，需另行轮询 [isScreenOn] /
     *   whether the reflective call succeeded; not a guarantee that the screen
     *   is on — poll [isScreenOn] separately
     */
    fun wakeUp(): Boolean {
        return try {
            val method = getWakeUpMethod()
            val time = SystemClock.uptimeMillis()
            when (wakeUpMethodVersion) {
                0 -> {
                    method.invoke(manager, time, WAKE_REASON_APPLICATION, "azurpilot:wake", FakeContext.PACKAGE_NAME)
                    true
                }
                1 -> {
                    method.invoke(manager, time, "azurpilot:wake", FakeContext.PACKAGE_NAME)
                    true
                }
                else -> {
                    method.invoke(manager, time)
                    true
                }
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke wakeUp", e)
            false
        }
    }

    private var goToSleepMethod: Method? = null

    // 反射命中的 goToSleep 重载版本：0 = (time, reason, flags)；1 = (time) 兜底
    private var goToSleepMethodVersion = -1

    /**
     * 懒解析并按版本选择 `goToSleep` 重载
     *
     * Lazily resolves the `goToSleep` overload per version.
     */
    @Throws(NoSuchMethodException::class)
    private fun getGoToSleepMethod(): Method {
        var method = goToSleepMethod
        if (method == null) {
            val cls = manager.javaClass
            try {
                // 签名：goToSleep(long time, int reason, int flags)
                method = cls.getMethod("goToSleep", Long::class.java, Int::class.java, Int::class.java)
                goToSleepMethodVersion = 0
            } catch (e1: NoSuchMethodException) {
                // 兜底：goToSleep(long time)
                method = cls.getMethod("goToSleep", Long::class.java)
                goToSleepMethodVersion = 1
            }
            goToSleepMethod = method
        }
        return method
    }

    /**
     * 请求息屏
     *
     * 以 `GO_TO_SLEEP_REASON_APPLICATION` 调用隐藏 `goToSleep`。
     *
     * Requests the screen to go to sleep.
     *
     * Invokes the hidden `goToSleep` with `GO_TO_SLEEP_REASON_APPLICATION`.
     *
     * @return 反射调用是否成功；不代表已息屏/上锁，需另行确认 / whether the
     *   reflective call succeeded; not a guarantee the device slept or locked —
     *   verify separately
     */
    fun goToSleep(): Boolean {
        return try {
            val method = getGoToSleepMethod()
            val time = SystemClock.uptimeMillis()
            if (goToSleepMethodVersion == 0) {
                method.invoke(manager, time, GO_TO_SLEEP_REASON_APPLICATION, 0)
            } else {
                method.invoke(manager, time)
            }
            true
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke goToSleep", e)
            false
        }
    }

    internal companion object {
        // 以下三个常量镜像隐藏 PowerManager 的同名语义值

        /** 镜像 `USER_ACTIVITY_EVENT_OTHER` / Mirrors `USER_ACTIVITY_EVENT_OTHER`. */
        private const val USER_ACTIVITY_EVENT_OTHER = 0

        /** 镜像 `WAKE_REASON_APPLICATION` / Mirrors `WAKE_REASON_APPLICATION`. */
        private const val WAKE_REASON_APPLICATION = 2

        /** 镜像 `GO_TO_SLEEP_REASON_APPLICATION` / Mirrors `GO_TO_SLEEP_REASON_APPLICATION`. */
        private const val GO_TO_SLEEP_REASON_APPLICATION = 2

        /** 经 [ServiceManager.getService] 获取 "power" binder 并包装 / Wraps the "power" binder obtained via [ServiceManager.getService]. */
        fun create(): PowerManager {
            val manager = ServiceManager.getService("power", "android.os.IPowerManager")
            return PowerManager(manager)
        }
    }
}
