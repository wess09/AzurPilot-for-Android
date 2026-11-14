package com.azurpilot.ghio.third.wrappers

import android.os.IInterface

import com.azurpilot.ghio.third.Ln

import java.lang.reflect.Method

/**
 * 隐藏 binder 服务 `IStatusBarService`（"statusbar"）的反射包装
 *
 * 提供展开通知面板（[expandNotificationsPanel]）、展开设置面板
 * （[expandSettingsPanel]）与收起全部面板（[collapsePanels]）三个系统级动作；
 * 隐藏 API 在 targetSdk 下没有编译期入口，故按"方法名+签名"反射调用，旧 ROM 的
 * 签名差异由各私有解析器探测并缓存。实例经 [StatusBarManager.create] 创建；
 * 方法面向特权进程工作线程（binder 调用阻塞）。
 *
 * 从 scrcpy 服务端的 `third/wrappers/StatusBarManager.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * Reflection wrapper around the hidden `IStatusBarService` binder
 * ("statusbar").
 *
 * Provides three system-level actions: expanding the notification panel
 * ([expandNotificationsPanel]), expanding the settings panel
 * ([expandSettingsPanel]) and collapsing all panels ([collapsePanels]). Hidden
 * APIs have no compile-time surface at targetSdk, so calls are made
 * reflectively by name + signature, with legacy-ROM signature differences
 * probed and cached by the private resolvers. Instances come from
 * [StatusBarManager.create]; methods target privileged-process worker threads
 * (binder calls block).
 *
 * Ported from scrcpy's server `third/wrappers/StatusBarManager.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
class StatusBarManager private constructor(private val manager: IInterface) {

    private var expandNotificationsPanelMethod: Method? = null
    private var expandNotificationPanelMethodCustomVersion = false
    private var expandSettingsPanelMethod: Method? = null
    private var expandSettingsPanelMethodNewVersion = true
    private var collapsePanelsMethod: Method? = null

    /**
     * 懒解析 `expandNotificationsPanel`，含部分定制 ROM 的带参变体
     *
     * Lazily resolves `expandNotificationsPanel`, including the parameterized
     * variant on some custom ROMs.
     */
    @Throws(NoSuchMethodException::class)
    private fun getExpandNotificationsPanelMethod(): Method {
        var method = expandNotificationsPanelMethod
        if (method == null) {
            method = try {
                manager.javaClass.getMethod("expandNotificationsPanel")
            } catch (e: NoSuchMethodException) {
                // 部分厂商定制 ROM 的带参变体：<https://github.com/Genymobile/scrcpy/issues/2551>
                manager.javaClass.getMethod("expandNotificationsPanel", Int::class.java)
                    .also { expandNotificationPanelMethodCustomVersion = true }
            }
            expandNotificationsPanelMethod = method
        }
        return method
    }

    /**
     * 懒解析 `expandSettingsPanel`，Android 7 起签名带 String 参数
     *
     * Lazily resolves `expandSettingsPanel`, whose signature carries a String
     * parameter since Android 7.
     */
    @Throws(NoSuchMethodException::class)
    private fun getExpandSettingsPanel(): Method {
        var method = expandSettingsPanelMethod
        if (method == null) {
            method = try {
                // Android 7 起新增 String 参数：
                // <https://android.googlesource.com/platform/frameworks/base.git/+/a9927325eda025504d59bb6594fee8e240d95b01%5E%21/>
                manager.javaClass.getMethod("expandSettingsPanel", String::class.java)
            } catch (e: NoSuchMethodException) {
                // 旧版本签名
                manager.javaClass.getMethod("expandSettingsPanel")
                    .also { expandSettingsPanelMethodNewVersion = false }
            }
            expandSettingsPanelMethod = method
        }
        return method
    }

    /**
     * 懒解析并缓存 `collapsePanels` 的反射 Method
     *
     * Lazily resolves and caches the reflective `collapsePanels` Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getCollapsePanelsMethod(): Method {
        var method = collapsePanelsMethod
        if (method == null) {
            method = manager.javaClass.getMethod("collapsePanels")
            collapsePanelsMethod = method
        }
        return method
    }

    /**
     * 展开通知面板
     *
     * 部分厂商定制 ROM 的 `expandNotificationsPanel` 带 Int 参数（见
     * <https://github.com/Genymobile/scrcpy/issues/2551>），解析器会记住变体并
     * 相应传 0；失败仅记日志。
     *
     * Expands the notification panel.
     *
     * The `expandNotificationsPanel` on some vendor-customized ROMs takes an
     * Int parameter (see <https://github.com/Genymobile/scrcpy/issues/2551>);
     * the resolver remembers the variant and passes 0 accordingly. Failures are
     * only logged.
     */
    fun expandNotificationsPanel() {
        try {
            val method = getExpandNotificationsPanelMethod()
            if (expandNotificationPanelMethodCustomVersion) {
                method.invoke(manager, 0)
            } else {
                method.invoke(manager)
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    /**
     * 展开设置面板
     *
     * Android 7 起 `expandSettingsPanel` 带 String 参数（见
     * <https://android.googlesource.com/platform/frameworks/base.git/+/a9927325eda025504d59bb6594fee8e240d95b01%5E%21/>），
     * 旧签名无参；失败仅记日志。
     *
     * Expands the settings panel.
     *
     * Since Android 7 `expandSettingsPanel` carries a String parameter (see
     * <https://android.googlesource.com/platform/frameworks/base.git/+/a9927325eda025504d59bb6594fee8e240d95b01%5E%21/>);
     * the legacy signature takes none. Failures are only logged.
     */
    fun expandSettingsPanel() {
        try {
            val method = getExpandSettingsPanel()
            if (expandSettingsPanelMethodNewVersion) {
                // 新版签名
                method.invoke(manager, null as Any?)
            } else {
                // 旧版签名
                method.invoke(manager)
            }
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    /**
     * 收起全部系统面板；失败仅记日志
     *
     * Collapses all system panels; failures are only logged.
     */
    fun collapsePanels() {
        try {
            val method = getCollapsePanelsMethod()
            method.invoke(manager)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    internal companion object {
        /**
         * 经 [ServiceManager.getService] 获取 "statusbar" binder 并包装
         *
         * Wraps the "statusbar" binder obtained via [ServiceManager.getService].
         */
        fun create(): StatusBarManager {
            val manager = ServiceManager.getService("statusbar", "com.android.internal.statusbar.IStatusBarService")
            return StatusBarManager(manager)
        }
    }
}
