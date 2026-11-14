package com.azurpilot.ghio.third.wrappers

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Surface

import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.third.Ln

import java.lang.reflect.Method

/**
 * `android.view.SurfaceControl` 隐藏静态方法的反射包装（SurfaceFlinger 显示器控制）
 *
 * 提供物理屏 token 解析（[getBuiltInDisplay] / [getPhysicalDisplayToken] /
 * [getPhysicalDisplayIds]）、显示器电源（[setDisplayPowerMode]）、创建/销毁虚拟
 * 显示器（[createDisplay] / [destroyDisplay]）与投影事务（[openTransaction] →
 * [setDisplayProjection] / [setDisplayLayerStack] / [setDisplaySurface] →
 * [closeTransaction]）。隐藏 API 在 targetSdk 下没有编译期入口，故按"方法名+
 * 签名"反射调用。进程级 object，供特权进程的捕获/镜像链路在事务线程使用。
 *
 * 从 scrcpy 服务端的 `third/wrappers/SurfaceControl.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * Reflection wrapper around the hidden static methods of
 * `android.view.SurfaceControl` (SurfaceFlinger display control).
 *
 * Provides physical display token resolution ([getBuiltInDisplay] /
 * [getPhysicalDisplayToken] / [getPhysicalDisplayIds]), display power
 * ([setDisplayPowerMode]), virtual display creation/destruction
 * ([createDisplay] / [destroyDisplay]) and projection transactions
 * ([openTransaction] → [setDisplayProjection] / [setDisplayLayerStack] /
 * [setDisplaySurface] → [closeTransaction]). Hidden APIs have no compile-time
 * surface at targetSdk, so calls are made reflectively by name + signature.
 * Process-level object used by the privileged process's capture/mirroring
 * chain on its transaction thread.
 *
 * Ported from scrcpy's server `third/wrappers/SurfaceControl.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
@SuppressLint("PrivateApi")
object SurfaceControl {

    // 电源模式常量取值来源：
    // <https://android.googlesource.com/platform/frameworks/base.git/+/pie-release-2/core/java/android/view/SurfaceControl.java#305>

    /** 镜像 `SurfaceControl.POWER_MODE_OFF` / Mirrors `SurfaceControl.POWER_MODE_OFF`. */
    const val POWER_MODE_OFF = 0

    /** 镜像 `SurfaceControl.POWER_MODE_NORMAL` / Mirrors `SurfaceControl.POWER_MODE_NORMAL`. */
    const val POWER_MODE_NORMAL = 2

    /**
     * SurfaceControl 类引用，缺失即运行环境异常
     *
     * Reference to the SurfaceControl class; a missing class means a broken
     * runtime.
     */
    val CLASS: Class<*> = try {
        Class.forName("android.view.SurfaceControl")
    } catch (e: ClassNotFoundException) {
        throw AssertionError(e)
    }

    private var getBuiltInDisplayMethod: Method? = null
    private var setDisplayPowerModeMethod: Method? = null
    private var getPhysicalDisplayTokenMethod: Method? = null
    private var getPhysicalDisplayIdsMethod: Method? = null

    /**
     * 开启一个全局 SurfaceFlinger 事务，把后续 set 系列调用攒起来
     *
     * 须与 [closeTransaction] 成对出现；失败抛 [AssertionError]。
     *
     * Opens a global SurfaceFlinger transaction batching the following set
     * calls.
     *
     * Must be paired with [closeTransaction]; failure raises
     * [AssertionError].
     */
    fun openTransaction() {
        try {
            CLASS.getMethod("openTransaction").invoke(null)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    /**
     * 提交并结束由 [openTransaction] 开启的事务；失败抛 [AssertionError]
     *
     * Commits and ends the transaction opened by [openTransaction]; failure
     * raises [AssertionError].
     */
    fun closeTransaction() {
        try {
            CLASS.getMethod("closeTransaction").invoke(null)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    /**
     * 设置指定显示器的投影参数（须在 [openTransaction] / [closeTransaction]
     * 事务内调用）；失败抛 [AssertionError]
     *
     * 镜像隐藏 `SurfaceControl.setDisplayProjection`；[orientation] 取
     * `Surface.ROTATION_*`，[layerStackRect] 为 layerStack 坐标系下的源矩形，
     * [displayRect] 为合成输出的目标矩形。
     *
     * Sets the display projection (must run inside an [openTransaction] /
     * [closeTransaction] transaction); failure raises [AssertionError].
     *
     * Mirrors the hidden `SurfaceControl.setDisplayProjection`; [orientation]
     * is a `Surface.ROTATION_*` value, [layerStackRect] is the source rectangle
     * in layer-stack space and [displayRect] the destination rectangle of the
     * composition output.
     */
    fun setDisplayProjection(displayToken: IBinder?, orientation: Int, layerStackRect: Rect?, displayRect: Rect?) {
        try {
            CLASS.getMethod("setDisplayProjection", IBinder::class.java, Int::class.java, Rect::class.java, Rect::class.java)
                .invoke(null, displayToken, orientation, layerStackRect, displayRect)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    /**
     * 把显示器绑定到指定 layerStack（须在事务内调用）；失败抛 [AssertionError]
     *
     * 镜像隐藏 `SurfaceControl.setDisplayLayerStack`；layerStack 决定哪些图层
     * 会被合成到该显示器上。
     *
     * Binds the display to the given layerStack (must run inside a
     * transaction); failure raises [AssertionError].
     *
     * Mirrors the hidden `SurfaceControl.setDisplayLayerStack`; the layer stack
     * decides which layers get composited onto the display.
     */
    fun setDisplayLayerStack(displayToken: IBinder?, layerStack: Int) {
        try {
            CLASS.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.java)
                .invoke(null, displayToken, layerStack)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    /**
     * 把产出画面内容的 [Surface] 接到显示器上（须在事务内调用）；失败抛
     * [AssertionError]
     *
     * 镜像隐藏 `SurfaceControl.setDisplaySurface`；虚拟屏镜像靠它把合成结果
     * 送到捕获侧。
     *
     * Attaches the [Surface] producing the display content (must run inside a
     * transaction); failure raises [AssertionError].
     *
     * Mirrors the hidden `SurfaceControl.setDisplaySurface`; virtual-display
     * mirroring relies on it to route the composition result to the capture
     * side.
     */
    fun setDisplaySurface(displayToken: IBinder?, surface: Surface?) {
        try {
            CLASS.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java)
                .invoke(null, displayToken, surface)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    /**
     * 在 SurfaceFlinger 侧创建虚拟显示器并返回其 token
     *
     * 镜像隐藏 `SurfaceControl.createDisplay`；[secure] 为 true 时创建安全
     * 显示器（内容不可截屏/录屏）。token 供 [setDisplaySurface] 等事务方法与
     * [destroyDisplay] 使用。
     *
     * Creates a virtual display in SurfaceFlinger and returns its token.
     *
     * Mirrors the hidden `SurfaceControl.createDisplay`; a true [secure]
     * creates a secure display (content cannot be screenshotted or recorded).
     * The token feeds [setDisplaySurface] and friends as well as
     * [destroyDisplay].
     *
     * @throws Exception 反射调用失败 / the reflective call failed
     */
    @Throws(Exception::class)
    fun createDisplay(name: String, secure: Boolean): IBinder {
        return CLASS.getMethod("createDisplay", String::class.java, Boolean::class.java)
            .invoke(null, name, secure) as IBinder
    }

    /**
     * 懒解析内建显示器 token 获取方法，按 API 29 边界选签名
     *
     * Lazily resolves the built-in display token getter, switching signatures
     * at the API 29 boundary.
     */
    @Throws(NoSuchMethodException::class)
    private fun getGetBuiltInDisplayMethod(): Method {
        var method = getBuiltInDisplayMethod
        if (method == null) {
            // 该方法签名在 Android 10 变更：
            // <https://github.com/Genymobile/scrcpy/issues/586>
            method = if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
                CLASS.getMethod("getBuiltInDisplay", Int::class.java)
            } else {
                CLASS.getMethod("getInternalDisplayToken")
            }
            getBuiltInDisplayMethod = method
        }
        return method
    }

    /**
     * 探测本机能否解析出内建显示器 token 方法
     *
     * Returns whether the built-in display token getter can be resolved on
     * this device.
     */
    fun hasGetBuildInDisplayMethod(): Boolean {
        return try {
            getGetBuiltInDisplayMethod()
            true
        } catch (e: NoSuchMethodException) {
            false
        }
    }

    /**
     * 解析内建（主）物理屏的 token；失败返回 null
     *
     * API 29 以下走 `getBuiltInDisplay(0)`，API 29+ 走
     * `getInternalDisplayToken`。
     *
     * Resolves the token of the built-in (main) physical display; returns null
     * on failure.
     *
     * Below API 29 goes through `getBuiltInDisplay(0)`; from API 29 on through
     * `getInternalDisplayToken`.
     */
    fun getBuiltInDisplay(): IBinder? {
        return try {
            val method = getGetBuiltInDisplayMethod()
            if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
                // 即 getBuiltInDisplay(0)
                method.invoke(null, 0) as IBinder?
            } else {
                // 即 getInternalDisplayToken()
                method.invoke(null) as IBinder?
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            null
        }
    }

    /**
     * 懒解析并缓存 `getPhysicalDisplayToken` 的反射 Method
     *
     * Lazily resolves and caches the reflective `getPhysicalDisplayToken`
     * Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getGetPhysicalDisplayTokenMethod(): Method {
        var method = getPhysicalDisplayTokenMethod
        if (method == null) {
            method = CLASS.getMethod("getPhysicalDisplayToken", Long::class.java)
            getPhysicalDisplayTokenMethod = method
        }
        return method
    }

    /**
     * 把物理屏 ID 解析成 SurfaceFlinger 用的 token（API 29+ 隐藏方法）；失败
     * 返回 null
     *
     * Resolves a physical display id into the token used by SurfaceFlinger
     * (hidden method, API 29+); returns null on failure.
     */
    fun getPhysicalDisplayToken(physicalDisplayId: Long): IBinder? {
        return try {
            val method = getGetPhysicalDisplayTokenMethod()
            method.invoke(null, physicalDisplayId) as IBinder?
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            null
        }
    }

    /**
     * 懒解析并缓存 `getPhysicalDisplayIds` 的反射 Method
     *
     * Lazily resolves and caches the reflective `getPhysicalDisplayIds` Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getGetPhysicalDisplayIdsMethod(): Method {
        var method = getPhysicalDisplayIdsMethod
        if (method == null) {
            method = CLASS.getMethod("getPhysicalDisplayIds")
            getPhysicalDisplayIdsMethod = method
        }
        return method
    }

    /**
     * 探测本机能否解析出物理屏 ID 列表方法（API 29+ 隐藏方法）
     *
     * Returns whether the physical display id list getter (hidden method,
     * API 29+) can be resolved on this device.
     */
    fun hasGetPhysicalDisplayIdsMethod(): Boolean {
        return try {
            getGetPhysicalDisplayIdsMethod()
            true
        } catch (e: NoSuchMethodException) {
            false
        }
    }

    /**
     * 列出全部物理屏 ID（API 29+ 隐藏方法）；失败返回 null
     *
     * Lists all physical display ids (hidden method, API 29+); returns null on
     * failure.
     */
    fun getPhysicalDisplayIds(): LongArray? {
        return try {
            val method = getGetPhysicalDisplayIdsMethod()
            method.invoke(null) as LongArray?
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            null
        }
    }

    /**
     * 懒解析并缓存 `setDisplayPowerMode` 的反射 Method
     *
     * Lazily resolves and caches the reflective `setDisplayPowerMode` Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getSetDisplayPowerModeMethod(): Method {
        var method = setDisplayPowerModeMethod
        if (method == null) {
            method = CLASS.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.java)
            setDisplayPowerModeMethod = method
        }
        return method
    }

    /**
     * 设置显示器电源模式（如 [POWER_MODE_OFF] / [POWER_MODE_NORMAL]）；失败
     * 返回 false
     *
     * 镜像隐藏 `SurfaceControl.setDisplayPowerMode`；直接作用于 SurfaceFlinger
     * 的显示器，不等同于 PowerManager 的全局亮灭屏。
     *
     * Sets the display power mode (e.g. [POWER_MODE_OFF] /
     * [POWER_MODE_NORMAL]); returns false on failure.
     *
     * Mirrors the hidden `SurfaceControl.setDisplayPowerMode`; it acts on the
     * SurfaceFlinger display directly and is not the same as PowerManager's
     * global wake/sleep.
     */
    fun setDisplayPowerMode(displayToken: IBinder?, mode: Int): Boolean {
        return try {
            val method = getSetDisplayPowerModeMethod()
            method.invoke(null, displayToken, mode)
            true
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            false
        }
    }

    /**
     * 销毁由 [createDisplay] 创建的虚拟显示器；失败抛 [AssertionError]
     *
     * 镜像隐藏 `SurfaceControl.destroyDisplay`。
     *
     * Destroys the virtual display created by [createDisplay]; failure raises
     * [AssertionError].
     *
     * Mirrors the hidden `SurfaceControl.destroyDisplay`.
     */
    fun destroyDisplay(displayToken: IBinder?) {
        try {
            CLASS.getMethod("destroyDisplay", IBinder::class.java).invoke(null, displayToken)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }
}
