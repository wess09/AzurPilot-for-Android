package com.azurpilot.ghio.third.wrappers

import android.annotation.TargetApi
import android.os.Build
import android.os.Bundle
import android.os.IInterface

import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.third.Ln

import java.lang.reflect.Method

/**
 * 隐藏 binder 服务 `IWindowManager`（"window"）的反射包装
 *
 * 隐藏 API 在 targetSdk 下没有编译期入口，故经 [ServiceManager.getService] 拿
 * binder 后按"方法名+签名"反射调用，新旧 ROM 的签名差异由各私有解析器探测并
 * 缓存。能力分四组：读屏幕旋转（[getRotation]）、冻结/解冻旋转
 * （[freezeRotation] / [isRotationFrozen] / [thawRotation]）、强制显示器分辨率
 * （[setForcedDisplaySize] / [clearForcedDisplaySize]）与输入法策略
 * （[getDisplayImePolicy] / [setDisplayImePolicy]）。
 * [isKeyguardLocked] / [isKeyguardSecure] / [dismissKeyguard] / [lockNow] /
 * [captureDisplay] 为本工程在 scrcpy 基础上扩展，服务于解锁-截图自动化链。
 *
 * 实例经 [WindowManager.create] 创建；方法面向特权进程工作线程（binder 调用
 * 阻塞）。
 *
 * 从 scrcpy 服务端的 `third/wrappers/WindowManager.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * Reflection wrapper around the hidden `IWindowManager` binder ("window").
 *
 * Hidden APIs have no compile-time surface at targetSdk, so the binder from
 * [ServiceManager.getService] is invoked reflectively by name + signature,
 * with legacy-ROM signature differences probed and cached by the private
 * resolvers. Capabilities in four groups: reading the display rotation
 * ([getRotation]), freezing/unfreezing rotation ([freezeRotation] /
 * [isRotationFrozen] / [thawRotation]), forcing the display size
 * ([setForcedDisplaySize] / [clearForcedDisplaySize]) and the IME policy
 * ([getDisplayImePolicy] / [setDisplayImePolicy]). [isKeyguardLocked] /
 * [isKeyguardSecure] / [dismissKeyguard] / [lockNow] / [captureDisplay] are
 * this project's extensions on top of scrcpy, serving the unlock-screenshot
 * automation chain.
 *
 * Instances come from [WindowManager.create]; methods target
 * privileged-process worker threads (binder calls block).
 *
 * Ported from scrcpy's server `third/wrappers/WindowManager.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
class WindowManager private constructor(private val manager: IInterface) {

    private var getRotationMethod: Method? = null

    private var freezeDisplayRotationMethod: Method? = null
    private var freezeDisplayRotationMethodVersion = 0

    private var isDisplayRotationFrozenMethod: Method? = null
    private var isDisplayRotationFrozenMethodVersion = 0

    private var thawDisplayRotationMethod: Method? = null
    private var thawDisplayRotationMethodVersion = 0

    private var getDisplayImePolicyMethod: Method? = null
    private var setDisplayImePolicyMethod: Method? = null

    private var setForcedDisplaySizeMethod: Method? = null
    private var clearForcedDisplaySizeMethod: Method? = null

    /**
     * 懒解析读取屏幕旋转的反射 Method
     *
     * Lazily resolves the reflective Method reading the display rotation.
     */
    @Throws(NoSuchMethodException::class)
    private fun getGetRotationMethod(): Method {
        var method = getRotationMethod
        if (method == null) {
            val cls = manager.javaClass
            method = try {
                // 该方法自此提交起更名：
                // <https://android.googlesource.com/platform/frameworks/base/+/8ee7285128c3843401d4c4d0412cd66e86ba49e3%5E%21/#F2>
                cls.getMethod("getDefaultDisplayRotation")
            } catch (e: NoSuchMethodException) {
                // 旧版本
                cls.getMethod("getRotation")
            }
            getRotationMethod = method
        }
        return method
    }

    /**
     * 懒解析冻结旋转的反射 Method，按版本记录命中重载
     *
     * Lazily resolves the reflective Method freezing the rotation, recording
     * the matched overload per version.
     */
    @Throws(NoSuchMethodException::class)
    private fun getFreezeDisplayRotationMethod(): Method {
        var method = freezeDisplayRotationMethod
        if (method == null) {
            method = try {
                // Android 15 预览版与 14 QPR3 Beta 为调试追加了 String caller 参数：
                // <https://android.googlesource.com/platform/frameworks/base/+/670fb7f5c0d23cf51ead25538bcb017e03ed73ac%5E%21/>
                manager.javaClass.getMethod("freezeDisplayRotation", Int::class.java, Int::class.java, String::class.java)
                    .also { freezeDisplayRotationMethodVersion = 0 }
            } catch (e: NoSuchMethodException) {
                try {
                    // 此提交新增的方法：
                    // <https://android.googlesource.com/platform/frameworks/base/+/90c9005e687aa0f63f1ac391adc1e8878ab31759%5E%21/>
                    manager.javaClass.getMethod("freezeDisplayRotation", Int::class.java, Int::class.java)
                        .also { freezeDisplayRotationMethodVersion = 1 }
                } catch (e1: NoSuchMethodException) {
                    manager.javaClass.getMethod("freezeRotation", Int::class.java)
                        .also { freezeDisplayRotationMethodVersion = 2 }
                }
            }
            freezeDisplayRotationMethod = method
        }
        return method
    }

    /**
     * 懒解析查询旋转是否冻结的反射 Method，按版本记录命中重载
     *
     * Lazily resolves the reflective Method querying whether the rotation is
     * frozen, recording the matched overload per version.
     */
    @Throws(NoSuchMethodException::class)
    private fun getIsDisplayRotationFrozenMethod(): Method {
        var method = isDisplayRotationFrozenMethod
        if (method == null) {
            method = try {
                // 此提交新增的方法：
                // <https://android.googlesource.com/platform/frameworks/base/+/90c9005e687aa0f63f1ac391adc1e8878ab31759%5E%21/>
                manager.javaClass.getMethod("isDisplayRotationFrozen", Int::class.java)
                    .also { isDisplayRotationFrozenMethodVersion = 0 }
            } catch (e: NoSuchMethodException) {
                manager.javaClass.getMethod("isRotationFrozen")
                    .also { isDisplayRotationFrozenMethodVersion = 1 }
            }
            isDisplayRotationFrozenMethod = method
        }
        return method
    }

    /**
     * 懒解析解冻旋转的反射 Method，按版本记录命中重载
     *
     * Lazily resolves the reflective Method thawing the rotation, recording
     * the matched overload per version.
     */
    @Throws(NoSuchMethodException::class)
    private fun getThawDisplayRotationMethod(): Method {
        var method = thawDisplayRotationMethod
        if (method == null) {
            method = try {
                // Android 15 预览版与 14 QPR3 Beta 为调试追加了 String caller 参数：
                // <https://android.googlesource.com/platform/frameworks/base/+/670fb7f5c0d23cf51ead25538bcb017e03ed73ac%5E%21/>
                manager.javaClass.getMethod("thawDisplayRotation", Int::class.java, String::class.java)
                    .also { thawDisplayRotationMethodVersion = 0 }
            } catch (e: NoSuchMethodException) {
                try {
                    // 此提交新增的方法：
                    // <https://android.googlesource.com/platform/frameworks/base/+/90c9005e687aa0f63f1ac391adc1e8878ab31759%5E%21/>
                    manager.javaClass.getMethod("thawDisplayRotation", Int::class.java)
                        .also { thawDisplayRotationMethodVersion = 1 }
                } catch (e1: NoSuchMethodException) {
                    manager.javaClass.getMethod("thawRotation")
                        .also { thawDisplayRotationMethodVersion = 2 }
                }
            }
            thawDisplayRotationMethod = method
        }
        return method
    }

    /**
     * 懒解析并缓存 `setForcedDisplaySize` 的反射 Method
     *
     * Lazily resolves and caches the reflective `setForcedDisplaySize` Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getSetForcedDisplaySizeMethod(): Method {
        var method = setForcedDisplaySizeMethod
        if (method == null) {
            method = manager.javaClass.getMethod("setForcedDisplaySize", Int::class.java, Int::class.java, Int::class.java)
            setForcedDisplaySizeMethod = method
        }
        return method
    }

    /**
     * 懒解析并缓存 `clearForcedDisplaySize` 的反射 Method
     *
     * Lazily resolves and caches the reflective `clearForcedDisplaySize`
     * Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getClearForcedDisplaySizeMethod(): Method {
        var method = clearForcedDisplaySizeMethod
        if (method == null) {
            method = manager.javaClass.getMethod("clearForcedDisplaySize", Int::class.java)
            clearForcedDisplaySizeMethod = method
        }
        return method
    }

    /**
     * 强制指定显示器的分辨率（隐藏 `IWindowManager.setForcedDisplaySize`）
     *
     * [width] / [height] 单位为像素；失败仅记日志并返回 false。
     *
     * Forces the display size (hidden `IWindowManager.setForcedDisplaySize`).
     *
     * [width] / [height] are in pixels; failures are only logged and return
     * false.
     *
     * @return 反射调用是否成功 / whether the reflective call succeeded
     */
    fun setForcedDisplaySize(displayId: Int, width: Int, height: Int): Boolean {
        return try {
            val method = getSetForcedDisplaySizeMethod()
            method.invoke(manager, displayId, width, height)
            true
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            false
        }
    }

    /**
     * 清除由 [setForcedDisplaySize] 设置的强制分辨率；失败仅记日志并返回 false
     *
     * Clears the forced size set by [setForcedDisplaySize]; failures are only
     * logged and return false.
     *
     * @return 反射调用是否成功 / whether the reflective call succeeded
     */
    fun clearForcedDisplaySize(displayId: Int): Boolean {
        return try {
            val method = getClearForcedDisplaySizeMethod()
            method.invoke(manager, displayId)
            true
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            false
        }
    }

    /**
     * 读取默认显示器的当前旋转值
     *
     * 新版 ROM 走 `getDefaultDisplayRotation`，旧版走 `getRotation`；返回值取
     * `Surface.ROTATION_*`，反射失败按未旋转（0）返回。
     *
     * Reads the current rotation of the default display.
     *
     * Newer ROMs go through `getDefaultDisplayRotation`, legacy ones through
     * `getRotation`; the result is a `Surface.ROTATION_*` value, and a
     * reflection failure returns 0 (unrotated).
     */
    fun getRotation(): Int {
        return try {
            val method = getGetRotationMethod()
            method.invoke(manager) as Int
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            0
        }
    }

    /**
     * 冻结指定显示器的旋转到 [rotation]
     *
     * 按版本选择 `freezeDisplayRotation`（Android 14 QPR3+ 带 caller 调试参数）
     * 或旧版 `freezeRotation`；旧签名仅支持主屏。失败仅记日志。
     *
     * Freezes the rotation of the given display at [rotation].
     *
     * Picks `freezeDisplayRotation` (carrying a debug caller parameter from
     * Android 14 QPR3 on) or the legacy `freezeRotation` per version; the
     * legacy signature only supports the default display. Failures are only
     * logged.
     *
     * @param rotation 要冻结到的 `Surface.ROTATION_*` 值 / the
     *   `Surface.ROTATION_*` value to freeze at
     */
    fun freezeRotation(displayId: Int, rotation: Int) {
        try {
            val method = getFreezeDisplayRotationMethod()
            when (freezeDisplayRotationMethodVersion) {
                0 -> method.invoke(manager, displayId, rotation, "scrcpy#freezeRotation")
                1 -> method.invoke(manager, displayId, rotation)
                else -> {
                    if (displayId != 0) {
                        Ln.e("Secondary display rotation not supported on this device")
                        return
                    }
                    method.invoke(manager, rotation)
                }
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    /**
     * 查询指定显示器的旋转是否已冻结
     *
     * 旧签名 `isRotationFrozen` 仅支持主屏；反射失败返回 false。
     *
     * Returns whether the rotation of the given display is frozen.
     *
     * The legacy `isRotationFrozen` signature only supports the default
     * display; returns false on reflection failure.
     */
    fun isRotationFrozen(displayId: Int): Boolean {
        return try {
            val method = getIsDisplayRotationFrozenMethod()
            when (isDisplayRotationFrozenMethodVersion) {
                0 -> method.invoke(manager, displayId) as Boolean
                else -> {
                    if (displayId != 0) {
                        Ln.e("Secondary display rotation not supported on this device")
                        return false
                    }
                    method.invoke(manager) as Boolean
                }
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            false
        }
    }

    /**
     * 解冻由 [freezeRotation] 冻结的旋转
     *
     * 按版本选择 `thawDisplayRotation` 或旧版 `thawRotation`；旧签名仅支持
     * 主屏。失败仅记日志。
     *
     * Thaws the rotation frozen by [freezeRotation].
     *
     * Picks `thawDisplayRotation` or the legacy `thawRotation` per version;
     * the legacy signature only supports the default display. Failures are
     * only logged.
     */
    fun thawRotation(displayId: Int) {
        try {
            val method = getThawDisplayRotationMethod()
            when (thawDisplayRotationMethodVersion) {
                0 -> method.invoke(manager, displayId, "scrcpy#thawRotation")
                1 -> method.invoke(manager, displayId)
                else -> {
                    if (displayId != 0) {
                        Ln.e("Secondary display rotation not supported on this device")
                        return
                    }
                    method.invoke(manager)
                }
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    /**
     * 懒解析查询输入法策略的反射 Method
     *
     * API 31+ 走 `getDisplayImePolicy`，更早版本用 `shouldShowIme` 近似。
     *
     * Lazily resolves the reflective Method querying the IME policy.
     *
     * API 31+ goes through `getDisplayImePolicy`; earlier versions approximate
     * with `shouldShowIme`.
     */
    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    @Throws(NoSuchMethodException::class)
    private fun getGetDisplayImePolicyMethod(): Method {
        var method = getDisplayImePolicyMethod
        if (method == null) {
            method = if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                manager.javaClass.getMethod("getDisplayImePolicy", Int::class.java)
            } else {
                manager.javaClass.getMethod("shouldShowIme", Int::class.java)
            }
            getDisplayImePolicyMethod = method
        }
        return method
    }

    /**
     * 查询指定显示器的输入法（IME）策略
     *
     * API 31+ 直接返回隐藏 `getDisplayImePolicy` 的结果；更早版本用
     * `shouldShowIme` 近似映射（true → [DISPLAY_IME_POLICY_LOCAL]，false →
     * [DISPLAY_IME_POLICY_FALLBACK_DISPLAY]）。反射失败返回 -1。
     *
     * Queries the IME policy of the given display.
     *
     * API 31+ returns the hidden `getDisplayImePolicy` result directly;
     * earlier versions map `shouldShowIme` approximately (true →
     * [DISPLAY_IME_POLICY_LOCAL], false →
     * [DISPLAY_IME_POLICY_FALLBACK_DISPLAY]). Returns -1 on reflection
     * failure.
     *
     * @return [DISPLAY_IME_POLICY_LOCAL] / [DISPLAY_IME_POLICY_FALLBACK_DISPLAY]
     *   / [DISPLAY_IME_POLICY_HIDE]，失败为 -1 / one of the policy constants,
     *   or -1 on failure
     */
    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    fun getDisplayImePolicy(displayId: Int): Int {
        return try {
            val method = getGetDisplayImePolicyMethod()
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                method.invoke(manager, displayId) as Int
            } else {
                val shouldShowIme = method.invoke(manager, displayId) as Boolean
                if (shouldShowIme) DISPLAY_IME_POLICY_LOCAL else DISPLAY_IME_POLICY_FALLBACK_DISPLAY
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            -1
        }
    }

    /**
     * 懒解析设置输入法策略的反射 Method
     *
     * API 31+ 走 `setDisplayImePolicy`，更早版本走 `setShouldShowIme`。
     *
     * Lazily resolves the reflective Method setting the IME policy.
     *
     * API 31+ goes through `setDisplayImePolicy`; earlier versions through
     * `setShouldShowIme`.
     */
    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    @Throws(NoSuchMethodException::class)
    private fun getSetDisplayImePolicyMethod(): Method {
        var method = setDisplayImePolicyMethod
        if (method == null) {
            method = if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                manager.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
            } else {
                manager.javaClass.getMethod("setShouldShowIme", Int::class.java, Boolean::class.java)
            }
            setDisplayImePolicyMethod = method
        }
        return method
    }

    /**
     * 设置指定显示器的输入法（IME）策略
     *
     * API 31 以下不支持 [DISPLAY_IME_POLICY_HIDE]（仅记警告，不动作）；失败
     * 仅记日志。
     *
     * Sets the IME policy of the given display.
     *
     * [DISPLAY_IME_POLICY_HIDE] is unsupported below API 31 (a warning is
     * logged, nothing happens); failures are only logged.
     *
     * @param displayImePolicy [DISPLAY_IME_POLICY_LOCAL] /
     *   [DISPLAY_IME_POLICY_FALLBACK_DISPLAY] / [DISPLAY_IME_POLICY_HIDE]
     */
    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    fun setDisplayImePolicy(displayId: Int, displayImePolicy: Int) {
        try {
            val method = getSetDisplayImePolicyMethod()
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                method.invoke(manager, displayId, displayImePolicy)
            } else if (displayImePolicy != DISPLAY_IME_POLICY_HIDE) {
                method.invoke(manager, displayId, displayImePolicy == DISPLAY_IME_POLICY_LOCAL)
            } else {
                Ln.w("DISPLAY_IME_POLICY_HIDE is not supported before Android 12")
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    // Android 14+ 截图支持

    private var captureDisplayMethod: Method? = null

    /**
     * 通过隐藏 `IWindowManager.captureDisplay` 截取指定显示器内容
     * （Android 14+）
     *
     * [captureArgs] / [listener] 的真实类型是隐藏类
     * `android.window.ScreenCapture.CaptureArgs` 与
     * `ScreenCapture.ScreenCaptureListener`，调用方须自行反射构造；本方法只
     * 负责路由调用。反射失败或注入异常时抛 [RuntimeException]。
     *
     * Captures the given display through the hidden
     * `IWindowManager.captureDisplay` (Android 14+).
     *
     * The real types of [captureArgs] / [listener] are the hidden classes
     * `android.window.ScreenCapture.CaptureArgs` and
     * `ScreenCapture.ScreenCaptureListener`, which the caller must construct
     * reflectively; this method only routes the call. A reflection failure or
     * a failing invocation raises [RuntimeException].
     *
     * @param displayId 显示器 ID（通常为 0）/ display id (usually 0)
     * @param captureArgs 截图参数（可为 null）/ capture args (may be null)
     * @param listener ScreenCaptureListener 实例 / a ScreenCaptureListener
     *   instance
     * @throws RuntimeException 反射调用失败 / the reflective call failed
     */
    @TargetApi(AndroidVersions.API_34_ANDROID_14)
    fun captureDisplay(displayId: Int, captureArgs: Any?, listener: Any?) {
        try {
            val captureArgsClass = Class.forName("android.window.ScreenCapture\$CaptureArgs")
            val screenCaptureListenerClass = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
            var method = captureDisplayMethod
            if (method == null) {
                method = manager.javaClass.getMethod(
                    "captureDisplay",
                    Int::class.java, captureArgsClass, screenCaptureListenerClass
                )
                method.isAccessible = true
                captureDisplayMethod = method
            }
            method.invoke(manager, displayId, captureArgs, listener)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke captureDisplay", e)
            throw RuntimeException("captureDisplay failed", e)
        }
    }

    // keyguard 相关（本工程扩展，配合解锁自动化链）

    private var isKeyguardLockedMethod: Method? = null
    private var isKeyguardSecureMethod: Method? = null
    private var isKeyguardSecureMethodVersion = 0
    private var dismissKeyguardMethod: Method? = null

    /**
     * 查询当前是否处于锁屏（含无密码的滑动锁屏）
     *
     * 镜像隐藏 `IWindowManager.isKeyguardLocked`；反射不可用时返回 null。
     *
     * Returns whether the keyguard is currently showing (including the
     * credential-less swipe lock).
     *
     * Mirrors the hidden `IWindowManager.isKeyguardLocked`; returns null when
     * reflection is unavailable.
     */
    fun isKeyguardLocked(): Boolean? {
        return try {
            var method = isKeyguardLockedMethod
            if (method == null) {
                method = manager.javaClass.getMethod("isKeyguardLocked")
                isKeyguardLockedMethod = method
            }
            method.invoke(manager) as Boolean
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke isKeyguardLocked", e)
            null
        }
    }

    /**
     * 查询锁屏是否设了凭证（PIN/密码/图案）
     *
     * API 30+ 签名带 [userId]，旧签名忽略该参数；反射不可用时返回 null。
     *
     * Returns whether the keyguard is secured by a credential (PIN, password
     * or pattern).
     *
     * The API 30+ signature takes [userId]; the legacy one ignores it. Returns
     * null when reflection is unavailable.
     */
    fun isKeyguardSecure(userId: Int): Boolean? {
        return try {
            var method = isKeyguardSecureMethod
            if (method == null) {
                method = try {
                    // API 30+ 签名带 userId
                    manager.javaClass.getMethod("isKeyguardSecure", Int::class.java)
                        .also { isKeyguardSecureMethodVersion = 0 }
                } catch (e: NoSuchMethodException) {
                    manager.javaClass.getMethod("isKeyguardSecure")
                        .also { isKeyguardSecureMethodVersion = 1 }
                }
                isKeyguardSecureMethod = method
            }
            if (isKeyguardSecureMethodVersion == 0) {
                method.invoke(manager, userId) as Boolean
            } else {
                method.invoke(manager) as Boolean
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke isKeyguardSecure", e)
            null
        }
    }

    /**
     * 请求解除锁屏（API 26+ 隐藏 `dismissKeyguard`）
     *
     * 无凭证锁屏直接解除，有凭证时系统弹出 bouncer 等待输入；回调传 null 表示
     * 不关心结果。反射失败仅记日志并返回 false。
     *
     * Requests the keyguard to be dismissed (hidden `dismissKeyguard`,
     * API 26+).
     *
     * A credential-less keyguard is dismissed right away; with a credential
     * the system raises the bouncer awaiting input. A null callback means the
     * result is ignored. Reflection failures are only logged and return false.
     *
     * @return 反射调用是否成功；不代表已解锁，需另行轮询 [isKeyguardLocked] /
     *   whether the reflective call succeeded; not a guarantee of unlock —
     *   poll [isKeyguardLocked] separately
     */
    @TargetApi(AndroidVersions.API_26_ANDROID_8_0)
    fun dismissKeyguard(): Boolean {
        return try {
            var method = dismissKeyguardMethod
            if (method == null) {
                val callbackClass = Class.forName("com.android.internal.policy.IKeyguardDismissCallback")
                method = manager.javaClass.getMethod("dismissKeyguard", callbackClass, CharSequence::class.java)
                dismissKeyguardMethod = method
            }
            method.invoke(manager, null, null)
            true
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke dismissKeyguard", e)
            false
        }
    }

    private var lockNowMethod: Method? = null
    private var lockNowMethodVersion = -1

    /**
     * 懒解析 `lockNow` 的反射 Method，按签名记录命中重载
     *
     * Lazily resolves the reflective `lockNow` Method, recording the matched
     * overload per signature.
     */
    @Throws(NoSuchMethodException::class)
    private fun getLockNowMethod(): Method {
        var method = lockNowMethod
        if (method == null) {
            val cls = manager.javaClass
            method = try {
                // 带 options 的重载：lockNow(Bundle options)
                cls.getMethod("lockNow", Bundle::class.java)
                    .also { lockNowMethodVersion = 0 }
            } catch (e: NoSuchMethodException) {
                cls.getMethod("lockNow")
                    .also { lockNowMethodVersion = 1 }
            }
            lockNowMethod = method
        }
        return method
    }

    /**
     * 立即上锁（弹出 keyguard）
     *
     * 镜像隐藏 `IWindowManager.lockNow`；失败仅记日志并返回 false。
     *
     * Locks the device now (raises the keyguard).
     *
     * Mirrors the hidden `IWindowManager.lockNow`; failures are only logged
     * and return false.
     *
     * @return 反射调用是否成功 / whether the reflective call succeeded
     */
    fun lockNow(): Boolean {
        return try {
            val method = getLockNowMethod()
            if (lockNowMethodVersion == 0) {
                method.invoke(manager, Bundle())
            } else {
                method.invoke(manager)
            }
            true
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke lockNow", e)
            false
        }
    }

    internal companion object {
        // DISPLAY_IME_POLICY_* 取值来源：
        // <https://android.googlesource.com/platform/frameworks/base.git/+/2103ff441c66772c80c8560e322dcd9a45be7dcd/core/java/android/view/WindowManager.java#692>

        /** 输入法在显示器本地显示 / The IME renders on the display itself. */
        const val DISPLAY_IME_POLICY_LOCAL = 0

        /** 输入法落到回退屏而不显示在本屏 / The IME falls back to another display. */
        const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1

        /** 输入法在本屏隐藏 / The IME is hidden on the display. */
        const val DISPLAY_IME_POLICY_HIDE = 2

        /**
         * 经 [ServiceManager.getService] 获取 "window" binder 并包装
         *
         * Wraps the "window" binder obtained via [ServiceManager.getService].
         */
        fun create(): WindowManager {
            val manager = ServiceManager.getService("window", "android.view.IWindowManager")
            return WindowManager(manager)
        }
    }
}
