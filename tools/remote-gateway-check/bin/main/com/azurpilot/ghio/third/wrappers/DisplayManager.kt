package com.azurpilot.ghio.third.wrappers

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.Handler
import android.view.Display
import android.view.Surface

import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.third.Command
import com.azurpilot.ghio.third.DisplayInfo
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.Size

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.regex.Matcher
import java.util.regex.Pattern

import android.hardware.display.DisplayManager as SystemDisplayManager

/**
 * 隐藏客户端单例 `DisplayManagerGlobal` 的反射包装
 *
 * 隐藏 API 在 targetSdk 下没有编译期入口，本包装按"方法名+签名"反射访问，
 * 提供四类能力：读逻辑显示器信息（[getDisplayInfo] / [getDisplayIds]）、创建
 * 虚拟显示器（[createVirtualDisplay] / [createNewVirtualDisplay]）、开关显示器
 * 电源（[requestDisplayPower]）、监听显示器变化（[registerDisplayListener]）。
 * 实例经 [DisplayManager.create] 创建；特权进程内 Controller 与视频线程都会用
 * （[ServiceManager.getDisplayManager] 已按此加锁），binder 调用阻塞。
 *
 * 从 scrcpy 服务端的 `third/wrappers/DisplayManager.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * Reflection wrapper around the hidden client-side singleton
 * `DisplayManagerGlobal`.
 *
 * Hidden APIs have no compile-time surface at targetSdk, so this wrapper
 * reflects by name + signature and provides four capabilities: reading logical
 * display info ([getDisplayInfo] / [getDisplayIds]), creating virtual displays
 * ([createVirtualDisplay] / [createNewVirtualDisplay]), toggling display power
 * ([requestDisplayPower]), and listening for display changes
 * ([registerDisplayListener]). Instances come from [DisplayManager.create];
 * used in the privileged process from both the controller and video threads
 * ([ServiceManager.getDisplayManager] locks accordingly), binder calls block.
 *
 * Ported from scrcpy's server `third/wrappers/DisplayManager.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
class DisplayManager private constructor(private val manager: Any) {

    fun interface DisplayListener {
        /**
         * 逻辑 [Display] 的属性（尺寸、密度等）变化时回调
         *
         * 镜像隐藏 `DisplayManager.DisplayListener`；动态代理只分发这一种回调，
         * 其余回调（如 displayAdded/displayRemoved）被静默丢弃。
         *
         * Called whenever the properties of a logical [Display], such as size
         * and density, have changed.
         *
         * Mirrors the hidden `DisplayManager.DisplayListener`; the dynamic proxy
         * dispatches only this callback and silently drops the others
         * (displayAdded/displayRemoved etc.).
         *
         * @param displayId 发生变化的逻辑显示器 ID / id of the logical display
         *   that changed
         */
        fun onDisplayChanged(displayId: Int)
    }

    /**
     * 已注册监听器的句柄，包着动态代理实例，专供 [unregisterDisplayListener] 反查
     *
     * Handle to a registered listener wrapping the dynamic proxy, only for
     * [unregisterDisplayListener].
     */
    class DisplayListenerHandle internal constructor(internal val displayListenerProxy: Any)

    private var getDisplayInfoMethod: Method? = null
    private var createVirtualDisplayMethod: Method? = null
    private var requestDisplayPowerMethod: Method? = null

    /**
     * 懒解析并缓存 `getDisplayInfo` 的反射 Method
     *
     * Lazily resolves and caches the reflective `getDisplayInfo` Method.
     */
    @Synchronized
    @Throws(NoSuchMethodException::class)
    private fun getGetDisplayInfoMethod(): Method {
        var method = getDisplayInfoMethod
        if (method == null) {
            method = manager.javaClass.getMethod("getDisplayInfo", Int::class.java)
            getDisplayInfoMethod = method
        }
        return method
    }

    /**
     * 读取逻辑显示器信息
     *
     * 镜像隐藏 `DisplayManagerGlobal.getDisplayInfo`；字段宽高已含旋转。
     * 返回 null（个别设备）时回退到解析 `dumpsys display` 输出（慢路径，会拉起
     * 子进程）。
     *
     * Reads the logical display info.
     *
     * Mirrors the hidden `DisplayManagerGlobal.getDisplayInfo`; the size fields
     * already account for rotation. Falls back to parsing `dumpsys display`
     * output (slow path, spawns a child process) when the binder returns null
     * on some devices.
     *
     * @throws AssertionError 反射失败 / reflection failed
     */
    fun getDisplayInfo(displayId: Int): DisplayInfo? {
        return try {
            val method = getGetDisplayInfoMethod()
            val displayInfo = method.invoke(manager, displayId)
                ?: // binder 返回 null 时的兜底路径
                return getDisplayInfoFromDumpsysDisplay(displayId)
            val cls = displayInfo.javaClass
            val width = cls.getDeclaredField("logicalWidth").getInt(displayInfo)
            val height = cls.getDeclaredField("logicalHeight").getInt(displayInfo)
            val rotation = cls.getDeclaredField("rotation").getInt(displayInfo)
            val layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo)
            val flags = cls.getDeclaredField("flags").getInt(displayInfo)
            val dpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo)
            val uniqueId = cls.getDeclaredField("uniqueId").get(displayInfo) as String?
            DisplayInfo(displayId, Size(width, height), rotation, layerStack, flags, dpi, uniqueId)
        } catch (e: ReflectiveOperationException) {
            throw AssertionError(e)
        }
    }

    /**
     * 列出全部逻辑显示器 ID
     *
     * 镜像隐藏 `DisplayManagerGlobal.getDisplayIds`。
     *
     * Lists all logical display ids.
     *
     * Mirrors the hidden `DisplayManagerGlobal.getDisplayIds`.
     *
     * @throws AssertionError 反射失败 / reflection failed
     */
    fun getDisplayIds(): IntArray? {
        return try {
            manager.javaClass.getMethod("getDisplayIds").invoke(manager) as IntArray?
        } catch (e: ReflectiveOperationException) {
            throw AssertionError(e)
        }
    }

    /**
     * 懒解析并缓存 `DisplayManager.createVirtualDisplay` 的反射 Method
     *
     * Lazily resolves and caches the reflective
     * `DisplayManager.createVirtualDisplay` Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getCreateVirtualDisplayMethod(): Method {
        var method = createVirtualDisplayMethod
        if (method == null) {
            method = SystemDisplayManager::class.java.getMethod(
                "createVirtualDisplay", String::class.java, Int::class.java, Int::class.java, Int::class.java, Surface::class.java
            )
            createVirtualDisplayMethod = method
        }
        return method
    }

    /**
     * 经隐藏静态方法创建镜像 [displayIdToMirror] 的虚拟显示器
     *
     * 对应 `DisplayManager.createVirtualDisplay(name, width, height,
     * displayIdToMirror, surface)`：把指定逻辑显示器的内容投到 [surface]。
     *
     * Creates a virtual display mirroring [displayIdToMirror] through the
     * hidden static method.
     *
     * Mirrors `DisplayManager.createVirtualDisplay(name, width, height,
     * displayIdToMirror, surface)`: projects the given logical display onto
     * [surface].
     *
     * @throws Exception 反射调用失败 / the reflective call failed
     */
    @Throws(Exception::class)
    fun createVirtualDisplay(name: String, width: Int, height: Int, displayIdToMirror: Int, surface: Surface?): VirtualDisplay? {
        val method = getCreateVirtualDisplayMethod()
        return method.invoke(null, name, width, height, displayIdToMirror, surface) as VirtualDisplay?
    }

    /**
     * 经真实 `DisplayManager` 实例创建独立（非镜像）虚拟显示器
     *
     * 隐藏 `DisplayManager(Context)` 构造器 + 公开 createVirtualDisplay；
     * API 34+ 走 [VirtualDisplayConfig] 构建器，把请求刷新率钳到物理屏上限
     * （超出会被系统拒），读不到物理屏时交给系统取默认值。宽高单位为像素，
     * dpi 为逻辑密度。
     *
     * Creates a standalone (non-mirroring) virtual display via a real
     * `DisplayManager` instance.
     *
     * Hidden `DisplayManager(Context)` constructor plus the public
     * createVirtualDisplay; on API 34+ goes through the [VirtualDisplayConfig]
     * builder, clamping the requested refresh rate to the physical display's
     * maximum (the system rejects higher values) and deferring to the system
     * default when the physical display cannot be read. Width/height are in
     * pixels, dpi is the logical density.
     *
     * @throws Exception 反射调用失败 / the reflective call failed
     */
    @Throws(Exception::class)
    fun createNewVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
        flags: Int,
        refreshRate: Float
    ): VirtualDisplay? {
        val ctor: Constructor<SystemDisplayManager> = SystemDisplayManager::class.java.getDeclaredConstructor(
            Context::class.java
        )
        ctor.isAccessible = true
        val dm = ctor.newInstance(FakeContext.get())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val physical = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val maximum = physical?.refreshRate ?: 0f
            // 无法读取物理屏时交给系统；始终在创建时重新检查上限。
            val requested = if (maximum > 0f) minOf(refreshRate, maximum) else 0f
            val config = VirtualDisplayConfig.Builder(name, width, height, dpi)
                .setSurface(surface)
                .setFlags(flags)
                .setRequestedRefreshRate(requested)
                .build()
            return dm.createVirtualDisplay(config)
        }
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags)
    }

    /**
     * 懒解析并缓存 `requestDisplayPower` 的反射 Method
     *
     * Lazily resolves and caches the reflective `requestDisplayPower` Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getRequestDisplayPowerMethod(): Method {
        var method = requestDisplayPowerMethod
        if (method == null) {
            method = manager.javaClass.getMethod("requestDisplayPower", Int::class.java, Boolean::class.java)
            requestDisplayPowerMethod = method
        }
        return method
    }

    /**
     * 开关指定逻辑显示器的电源（API 35+ 隐藏方法）
     *
     * 镜像隐藏 `DisplayManagerGlobal.requestDisplayPower`；反射失败返回 false。
     *
     * Toggles the power of the given logical display (hidden method, API 35+).
     *
     * Mirrors the hidden `DisplayManagerGlobal.requestDisplayPower`; returns
     * false when reflection fails.
     */
    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    fun requestDisplayPower(displayId: Int, on: Boolean): Boolean {
        return try {
            val method = getRequestDisplayPowerMethod()
            method.invoke(manager, displayId, on) as Boolean
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            false
        }
    }

    /**
     * 注册显示器变化监听
     *
     * 镜像隐藏 `registerDisplayListener`，按版本从四参（含 packageName）、三参
     * （含事件位）、两参重载逐级回退。注册失败仅记日志并返回 null —— 此时旋转
     * 与尺寸变化不会再被推送，但不算致命错误。
     *
     * Registers a display-change listener.
     *
     * Mirrors the hidden `registerDisplayListener`, falling back per version
     * from the 4-arg overload (with packageName) to the 3-arg (event flags) and
     * the legacy 2-arg one. A registration failure only logs and returns null —
     * rotation and size updates are then no longer pushed, but it is not fatal.
     *
     * @return 反注册用的句柄；注册失败为 null / handle for unregistration; null
     *   when registration failed
     */
    fun registerDisplayListener(listener: DisplayListener, handler: Handler?): DisplayListenerHandle? {
        return try {
            val displayListenerClass = Class.forName("android.hardware.display.DisplayManager\$DisplayListener")
            val displayListenerProxy = Proxy.newProxyInstance(
                ClassLoader.getSystemClassLoader(),
                arrayOf(displayListenerClass)
            ) { _, method, args ->
                if ("onDisplayChanged" == method.name) {
                    listener.onDisplayChanged(args?.get(0) as Int)
                    null
                } else if ("toString" == method.name) {
                    "DisplayListener"
                } else {
                    null
                }
            }
            try {
                manager.javaClass
                    .getMethod(
                        "registerDisplayListener", displayListenerClass, Handler::class.java, Long::class.java, String::class.java
                    )
                    .invoke(manager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED, FakeContext.PACKAGE_NAME)
            } catch (e: NoSuchMethodException) {
                try {
                    manager.javaClass
                        .getMethod("registerDisplayListener", displayListenerClass, Handler::class.java, Long::class.java)
                        .invoke(manager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED)
                } catch (e2: NoSuchMethodException) {
                    manager.javaClass
                        .getMethod("registerDisplayListener", displayListenerClass, Handler::class.java)
                        .invoke(manager, displayListenerProxy, handler)
                }
            }

            DisplayListenerHandle(displayListenerProxy)
        } catch (e: Exception) {
            // 此时旋转与尺寸变化不会再被推送，但不算致命错误
            Ln.e("Could not register display listener", e)
            null
        }
    }

    /**
     * 反注册显示器变化监听；失败仅记日志
     *
     * Unregisters a display-change listener; failures are only logged.
     */
    fun unregisterDisplayListener(listener: DisplayListenerHandle) {
        try {
            val displayListenerClass = Class.forName("android.hardware.display.DisplayManager\$DisplayListener")
            manager.javaClass.getMethod("unregisterDisplayListener", displayListenerClass)
                .invoke(manager, listener.displayListenerProxy)
        } catch (e: Exception) {
            Ln.e("Could not unregister display listener", e)
        }
    }

    companion object {
        /**
         * 镜像隐藏常量 `DisplayManager.EVENT_FLAG_DISPLAY_CHANGED` / Mirrors the
         * hidden `DisplayManager.EVENT_FLAG_DISPLAY_CHANGED` constant.
         */
        val EVENT_FLAG_DISPLAY_CHANGED = 1L shl 2

        /**
         * 从 `DisplayManagerGlobal.getInstance()` 创建包装实例
         *
         * 取不到进程级 DisplayManagerGlobal 说明运行环境异常，直接抛
         * [AssertionError]。
         *
         * Creates the wrapper from `DisplayManagerGlobal.getInstance()`.
         *
         * A missing process-level DisplayManagerGlobal means a broken runtime
         * environment and raises [AssertionError].
         */
        internal fun create(): DisplayManager {
            try {
                val clazz = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val getInstanceMethod = clazz.getDeclaredMethod("getInstance")
                val dmg = getInstanceMethod.invoke(null)
                return DisplayManager(dmg)
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            }
        }

        /**
         * 解析 `dumpsys display` 输出中的 `mOverrideDisplayInfo` 行
         *
         * 单测可直调（故为 public）：给定完整 dumpsys 文本与目标 displayId，
         * 提取 real 宽高、rotation、density、layerStack 与 FLAG_* 位。
         * [DisplayInfo.uniqueId] 在此路径拿不到，恒为 null。
         *
         * Parses the `mOverrideDisplayInfo` line from `dumpsys display` output.
         *
         * Directly callable from unit tests (hence public): given the full
         * dumpsys text and the target displayId, extracts the real width,
         * height, rotation, density, layerStack and FLAG_* bits.
         * [DisplayInfo.uniqueId] is unobtainable on this path and stays null.
         */
        fun parseDisplayInfo(dumpsysDisplayOutput: String, displayId: Int): DisplayInfo? {
            val regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                    + "rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE
            )
            val m = regex.matcher(dumpsysDisplayOutput)
            if (!m.find()) {
                return null
            }
            val flags = parseDisplayFlags(m.group(1))
            val width = m.group(2)!!.toInt()
            val height = m.group(3)!!.toInt()
            val rotation = m.group(4)!!.toInt()
            val density = m.group(5)!!.toInt()
            val layerStack = m.group(6)!!.toInt()

            return DisplayInfo(displayId, Size(width, height), rotation, layerStack, flags, density, null)
        }

        /**
         * 慢路径：拉起 `dumpsys display` 子进程解析显示器信息
         *
         * 仅在 binder 返回 null 的设备上兜底；失败返回 null 并记日志。
         *
         * Slow path: spawns a `dumpsys display` child process and parses the
         * display info from it.
         *
         * Only a fallback for devices whose binder returns null; returns null
         * and logs on failure.
         */
        private fun getDisplayInfoFromDumpsysDisplay(displayId: Int): DisplayInfo? {
            return try {
                val dumpsysDisplayOutput = Command.execReadOutput("dumpsys", "display")
                parseDisplayInfo(dumpsysDisplayOutput, displayId)
            } catch (e: Exception) {
                Ln.e("Could not get display info from \"dumpsys display\" output", e)
                null
            }
        }

        /**
         * 把 dumpsys 文本里的 FLAG_* 名字还原成 Display 标志位
         *
         * dumpsys 会打出若干 @TestApi 级的标志名，公开 [Display] 类里没有对应
         * 字段，反射不到的静默跳过。
         *
         * Reconstructs Display flags from the FLAG_* names in the dumpsys text.
         *
         * dumpsys prints some @TestApi flag names that have no counterpart field
         * in the public [Display] class; unresolvable ones are silently skipped.
         */
        private fun parseDisplayFlags(text: String?): Int {
            if (text == null) {
                return 0
            }

            var flags = 0
            val regex = Pattern.compile("FLAG_[A-Z_]+")
            val m = regex.matcher(text)
            while (m.find()) {
                val flagString = m.group()
                try {
                    val filed: Field = Display::class.java.getDeclaredField(flagString)
                    flags = flags or filed.getInt(null)
                } catch (e: ReflectiveOperationException) {
                    // dumpsys 报告的部分标志是 @TestApi，公开类里反射不到，静默跳过
                }
            }
            return flags
        }
    }
}
