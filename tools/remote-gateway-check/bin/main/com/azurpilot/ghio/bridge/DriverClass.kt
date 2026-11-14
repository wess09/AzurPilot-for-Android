package com.azurpilot.ghio.bridge

import com.azurpilot.ghio.remote.internal.ActivityUtils
import com.azurpilot.ghio.remote.internal.PrimaryDisplayManager
import com.azurpilot.ghio.third.Ln
import java.util.Locale

/**
 * native 桥的上行调用（upcall）入口：C++ 侧按名回调进 Kotlin
 *
 * `bridge.cpp` / `bridge_input.cpp` 在启动时按字符串查找本类与各方法
 * （GetStaticMethodID），把 native 捕获/输入线程的事件转交 Kotlin：启动目标
 * 应用（[startApp]）、注入虚拟触摸（[touchDown] / [touchMove] / [touchUp]）与
 * 按键（[keyDown] / [keyUp]）。
 *
 * 类名与方法名被 native 侧硬编码，且在 R8 关键类清单内，不可重命名；方法须
 * 保持 [JvmStatic] 以生成静态 JNI 符号。线程归属：全部由 native 桥线程调用
 * （非 Android 主线程），触摸/按键注入内部经 [InputControlUtils] 串行化。
 *
 * Upcall entry points from the native bridge: the C++ side calls back into
 * Kotlin by name.
 *
 * `bridge.cpp` / `bridge_input.cpp` look this class and its methods up by
 * string (GetStaticMethodID) at startup and hand native capture/input thread
 * events over to Kotlin: launching the target app ([startApp]), injecting
 * virtual touches ([touchDown] / [touchMove] / [touchUp]) and keys
 * ([keyDown] / [keyUp]).
 *
 * The class and method names are hard-coded on the native side and listed in
 * the R8 keep rules, so they must not be renamed; the methods must stay
 * [JvmStatic] to produce static JNI symbols. Thread affinity: everything is
 * called on native bridge threads (not the Android main thread); touch/key
 * injection is serialized inside [InputControlUtils].
 */
object DriverClass {

    private const val TAG = "DriverClass"

    /** 等待首帧的超时上限（ms）；超时只记警告，不判定启动失败 / First-frame wait timeout (ms); a timeout only logs a warning, it does not fail the launch. */
    private const val FRAME_WAIT_TIMEOUT_MS = 5000

    /** 首帧轮询步长（ms）/ Polling interval of the first-frame wait (ms). */
    private const val FRAME_WAIT_INTERVAL_MS = 50L

    /**
     * 在指定显示器上启动目标应用，并尝试等待首帧观测。
     *
     * 主屏（[PrimaryDisplayManager.DISPLAY_ID]）走常规启动；虚拟屏启动后会校验应用确实落在
     * 目标屏，必要时尝试拉回。成功启动后请求窗口焦点，再等待 [FRAME_WAIT_TIMEOUT_MS] 观察
     * native 帧计数前进；
     * 超时只记录警告，不改变启动结果。
     *
     * Launches the target app on the given display and attempts to observe a first frame.
     *
     * The primary display ([PrimaryDisplayManager.DISPLAY_ID]) takes the plain start. After a virtual-
     * display start, the app is checked on the target display and is moved back when possible. A
     * successful start requests window focus, then waits at most [FRAME_WAIT_TIMEOUT_MS] to observe
     * a native frame-counter advance;
     * timeout only logs a warning and does not change the launch result.
     *
     * @param packageName 目标应用（可为 "包名/Activity" 形式）/ the target app
     *   (possibly "package/Activity")
     * @param displayId 目标逻辑显示器 ID / target logical display id
     * @param forceStop 透传给 [ActivityUtils.startApp] 的强停开关 / force-stop
     *   flag passed through to [ActivityUtils.startApp]
     * @return Activity 启动且在虚拟屏场景通过落屏校验时 true / true when the activity starts and,
     *   for a virtual display, passes the placement check
     */
    @JvmStatic
    fun startApp(packageName: String, displayId: Int, forceStop: Boolean): Boolean {
        Ln.i(TAG + String.format(Locale.US, "%s %d %b", packageName, displayId, forceStop))
        if (displayId == PrimaryDisplayManager.DISPLAY_ID) {
            return ActivityUtils.startApp(packageName, displayId, forceStop)
        }
        var ret = ActivityUtils.startApp(packageName, displayId, forceStop, true)
        if (ret) {
            // 部分 ROM（如 One UI）会把游戏从虚拟屏挪回主屏，启动后校验并尝试拉回；
            // 拉不回则快速失败，避免识别对着虚拟屏空转
            // 这里比对的是包名，PI 给的可能是 "包名/Activity"，先拆
            val target = ActivityUtils.packageNameOf(packageName)
            ret = ActivityUtils.ensureAppOnDisplay(target, displayId)
            if (!ret) {
                Ln.e(TAG + ": " + target + " could not be pinned on display " + displayId)
            }
        }
        if (ret) {
            ActivityUtils.focusAppOnDisplay(ActivityUtils.packageNameOf(packageName), displayId)
            awaitFirstFrame()
        }
        return ret
    }

    /**
     * 轮询等待 native 帧计数前进（应用真正渲染出一帧）
     *
     * 以 [FRAME_WAIT_INTERVAL_MS] 步进轮询 [NativeBridgeLib.getFrameCount]，
     * 超过 [FRAME_WAIT_TIMEOUT_MS] 仅记警告 —— 首帧迟到不改变启动结果。
     */
    private fun awaitFirstFrame() {
        val baseline = NativeBridgeLib.getFrameCount()
        var elapsed = 0
        while (NativeBridgeLib.getFrameCount() <= baseline && elapsed < FRAME_WAIT_TIMEOUT_MS) {
            try {
                Thread.sleep(FRAME_WAIT_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            elapsed += FRAME_WAIT_INTERVAL_MS.toInt()
        }
        if (elapsed >= FRAME_WAIT_TIMEOUT_MS) {
            Ln.w(TAG + ": awaitFirstFrame timed out after " + FRAME_WAIT_TIMEOUT_MS + "ms")
        }
    }

    // 热路径：一次 Swipe / MultiSwipe 会连发几十次，坐标由框架记账，这里不打日志

    /**
     * 注入虚拟触摸按下（native 上行）
     *
     * [x] / [y] 为目标显示器像素坐标，[contact] 为触点编号（0 起），同一
     * [contact] 的 down/move/up 构成一个触点生命周期；[displayId] 为目标显示器
     * （0 = 默认屏）。返回注入是否成功。
     *
     * Injects a virtual touch down (native upcall).
     *
     * [x] / [y] are display-pixel coordinates on the target display, [contact]
     * is the pointer index (0-based); the down/move/up of one [contact] form a
     * pointer's lifecycle; [displayId] is the target display (0 = default).
     * Returns whether the injection succeeded.
     */
    @JvmStatic
    fun touchDown(x: Int, y: Int, contact: Int, displayId: Int): Boolean {
        return InputControlUtils.down(x, y, contact, displayId)
    }

    /**
     * 注入虚拟触摸移动（native 上行）；契约同 [touchDown]
     *
     * Injects a virtual touch move (native upcall); same contract as
     * [touchDown].
     */
    @JvmStatic
    fun touchMove(x: Int, y: Int, contact: Int, displayId: Int): Boolean {
        return InputControlUtils.move(x, y, contact, displayId)
    }

    /**
     * 注入虚拟触摸抬起（native 上行）；契约同 [touchDown]
     *
     * Injects a virtual touch up (native upcall); same contract as
     * [touchDown].
     */
    @JvmStatic
    fun touchUp(x: Int, y: Int, contact: Int, displayId: Int): Boolean {
        return InputControlUtils.up(x, y, contact, displayId)
    }

    /**
     * 注入按键按下（native 上行）
     *
     * [keyCode] 为标准 Android 键码；注入等待分发完成（WAIT_FOR_FINISH 模式）。
     *
     * Injects a key down (native upcall).
     *
     * [keyCode] is a standard Android key code; the injection waits for the
     * dispatch to finish (WAIT_FOR_FINISH mode).
     *
     * @return 注入是否成功 / whether the injection succeeded
     */
    @JvmStatic
    fun keyDown(keyCode: Int, displayId: Int): Boolean {
        Ln.i(TAG + ": keyDown(keyCode=" + keyCode + ", displayId=" + displayId + ")")
        val result = InputControlUtils.keyDown(keyCode, displayId)
        Ln.i(TAG + ": keyDown result=" + result)
        return result
    }

    /**
     * 注入按键抬起（native 上行）
     *
     * 异步注入（不等待分发结果）；契约同 [keyDown]。
     *
     * Injects a key up (native upcall).
     *
     * Asynchronous injection (does not wait for the dispatch result); same
     * contract as [keyDown].
     *
     * @return 注入是否成功 / whether the injection succeeded
     */
    @JvmStatic
    fun keyUp(keyCode: Int, displayId: Int): Boolean {
        Ln.i(TAG + ": keyUp(keyCode=" + keyCode + ", displayId=" + displayId + ")")
        val result = InputControlUtils.keyUp(keyCode, displayId)
        Ln.i(TAG + ": keyUp result=" + result)
        return result
    }
}
