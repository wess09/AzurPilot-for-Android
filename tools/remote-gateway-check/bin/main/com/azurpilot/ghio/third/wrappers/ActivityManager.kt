package com.azurpilot.ghio.third.wrappers

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.IContentProvider
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface

import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.third.Ln

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 隐藏 binder 服务 `IActivityManager`（"activity"）的反射包装
 *
 * 让特权进程以 shell 身份获取 content provider、启动 Activity、强停应用包。
 * 隐藏 API 在 targetSdk 下没有编译期入口，因此经 `ActivityManagerNative.getDefault`
 * 拿 binder 后按"方法名+签名"反射调用；新旧版本签名不同处由各 `getXxxMethod`
 * 私有解析器探测并缓存。实例经 [create] 创建；方法面向特权进程工作线程
 * （binder 调用阻塞）。
 *
 * 从 scrcpy 服务端的 `third/wrappers/ActivityManager.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * Reflection wrapper around the hidden `IActivityManager` binder ("activity").
 *
 * Lets the privileged process acquire content providers, start activities and
 * force-stop packages as the shell user. Hidden APIs have no compile-time
 * surface at targetSdk, so the binder from `ActivityManagerNative.getDefault`
 * is wrapped and invoked reflectively by name + signature; the private
 * `getXxxMethod` resolvers probe and cache the signatures that differ across
 * Android versions. Instances come from [create]; methods target
 * privileged-process worker threads (binder calls block).
 *
 * Ported from scrcpy's server `third/wrappers/ActivityManager.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
class ActivityManager private constructor(private val manager: IInterface) {

    private var getContentProviderExternalMethod: Method? = null
    private var getContentProviderExternalMethodNewVersion = true
    private var removeContentProviderExternalMethod: Method? = null
    private var startActivityAsUserMethod: Method? = null
    private var forceStopPackageMethod: Method? = null

    /**
     * 懒解析并缓存 `getContentProviderExternal` 的反射 Method（新旧版本签名不同）
     *
     * Lazily resolves and caches the reflective `getContentProviderExternal`
     * Method (signature differs across versions).
     */
    @Throws(NoSuchMethodException::class)
    private fun getGetContentProviderExternalMethod(): Method {
        var method = getContentProviderExternalMethod
        if (method == null) {
            method = try {
                manager.javaClass.getMethod(
                    "getContentProviderExternal", String::class.java, Int::class.java, IBinder::class.java, String::class.java
                )
            } catch (e: NoSuchMethodException) {
                // 旧版本签名
                manager.javaClass.getMethod(
                    "getContentProviderExternal", String::class.java, Int::class.java, IBinder::class.java
                )
                    .also { getContentProviderExternalMethodNewVersion = false }
            }
            getContentProviderExternalMethod = method
        }
        return method
    }

    /**
     * 懒解析并缓存 `removeContentProviderExternal` 的反射 Method
     *
     * Lazily resolves and caches the reflective `removeContentProviderExternal`
     * Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getRemoveContentProviderExternalMethod(): Method {
        var method = removeContentProviderExternalMethod
        if (method == null) {
            method = manager.javaClass.getMethod(
                "removeContentProviderExternal", String::class.java, IBinder::class.java
            )
            removeContentProviderExternalMethod = method
        }
        return method
    }

    /**
     * 以默认用户（userId 0）获取指定名称的 content provider
     *
     * 对应隐藏 `IActivityManager.getContentProviderExternal`：把 provider 以
     * 外部调用者身份"挂"给本进程。反射失败或 provider 不存在时返回 null。
     *
     * Acquires the named content provider for the default user (userId 0).
     *
     * Mirrors the hidden `IActivityManager.getContentProviderExternal`:
     * attaches the provider to this process as an external caller. Returns null
     * when reflection fails or no such provider exists.
     */
    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    fun getContentProviderExternal(name: String, token: IBinder): IContentProvider? {
        return getContentProviderExternal(name, 0, token, null)
    }

    /**
     * 以指定用户获取 content provider（[tag] 用于新版签名的归属标记）
     *
     * 对应隐藏 `IActivityManager.getContentProviderExternal`；内部按版本选用
     * 带或不带 `tag` 参数的重载。
     *
     * Acquires the content provider for the given user ([tag] feeds the
     * attribution slot of the newer signature).
     *
     * Mirrors the hidden `IActivityManager.getContentProviderExternal`; the
     * overload with or without the `tag` parameter is chosen per version.
     *
     * @return provider 实例；holder 为空或反射失败时返回 null / the provider
     *   instance; null when the holder is absent or reflection fails
     */
    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    fun getContentProviderExternal(name: String, userId: Int, token: IBinder, tag: String?): IContentProvider? {
        return try {
            val method = getGetContentProviderExternalMethod()
            val args: Array<Any?> = if (getContentProviderExternalMethodNewVersion) {
                // 新版签名
                arrayOf(name, userId, token, tag)
            } else {
                // 旧版签名
                arrayOf(name, userId, token)
            }
            val providerHolder = method.invoke(manager, *args)
                ?: return null
            val providerField: Field = providerHolder.javaClass.getDeclaredField("provider")
            providerField.isAccessible = true
            providerField.get(providerHolder) as IContentProvider?
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
            null
        }
    }

    /**
     * 释放由 [getContentProviderExternal] 挂载的 provider 引用
     *
     * 对应隐藏 `IActivityManager.removeContentProviderExternal`；失败仅记日志。
     *
     * Releases the provider reference attached by [getContentProviderExternal].
     *
     * Mirrors the hidden `IActivityManager.removeContentProviderExternal`;
     * failures are only logged.
     */
    fun removeContentProviderExternal(name: String, token: IBinder) {
        try {
            val method = getRemoveContentProviderExternalMethod()
            method.invoke(manager, name, token)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Could not invoke method", e)
        }
    }

    /**
     * 懒解析并缓存 `startActivityAsUser` 的反射 Method
     *
     * Lazily resolves and caches the reflective `startActivityAsUser` Method.
     */
    @Throws(NoSuchMethodException::class, ClassNotFoundException::class)
    private fun getStartActivityAsUserMethod(): Method {
        var method = startActivityAsUserMethod
        if (method == null) {
            val iApplicationThreadClass = Class.forName("android.app.IApplicationThread")
            val profilerInfo = Class.forName("android.app.ProfilerInfo")
            method = manager.javaClass.getMethod(
                "startActivityAsUser", iApplicationThreadClass, String::class.java, Intent::class.java, String::class.java, IBinder::class.java, String::class.java,
                Int::class.java, Int::class.java, profilerInfo, Bundle::class.java, Int::class.java
            )
            startActivityAsUserMethod = method
        }
        return method
    }

    /** 以默认用户启动 Activity（无附加选项）/ Starts an activity for the default user (no extra options). */
    fun startActivity(intent: Intent): Int {
        return startActivity(intent, null)
    }

    /**
     * 以 `UserHandle.USER_CURRENT` 身份按隐藏 `startActivityAsUser` 启动 Activity
     *
     * callingPackage 固定为 shell；调用方一般为特权进程的输入/自动化链路。
     *
     * Starts an activity as `UserHandle.USER_CURRENT` via the hidden
     * `startActivityAsUser`.
     *
     * callingPackage is fixed to the shell identity; typically invoked from the
     * privileged process's input/automation chain.
     *
     * @return 框架返回的启动结果码（`ActivityManager.START_*`）；反射失败时返回 0
     *   / the framework start result code (`ActivityManager.START_*`); 0 when
     *   reflection fails
     */
    fun startActivity(intent: Intent, options: Bundle?): Int {
        return try {
            val method = getStartActivityAsUserMethod()
            method.invoke(
                /* this */ manager,
                /* caller */ null,
                /* callingPackage FakeContext.PACKAGE_NAME */ "com.android.shell",
                /* intent */ intent,
                /* resolvedType */ null,
                /* resultTo */ null,
                /* resultWho */ null,
                /* requestCode */ 0,
                /* startFlags */ 0,
                /* profilerInfo */ null,
                /* bOptions */ options,
                /* userId */ /* UserHandle.USER_CURRENT */ -2
            ) as Int
        } catch (e: Throwable) {
            Ln.e("Could not invoke method", e)
            0
        }
    }

    /**
     * 懒解析并缓存 `forceStopPackage` 的反射 Method
     *
     * Lazily resolves and caches the reflective `forceStopPackage` Method.
     */
    @Throws(NoSuchMethodException::class)
    private fun getForceStopPackageMethod(): Method {
        var method = forceStopPackageMethod
        if (method == null) {
            method = manager.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.java)
            forceStopPackageMethod = method
        }
        return method
    }

    /**
     * 强停指定包的全部进程（`UserHandle.USER_CURRENT`）
     *
     * 对应隐藏 `IActivityManager.forceStopPackage`；用于自动化前把目标应用拉回
     * 干净状态。失败仅记日志。
     *
     * Force-stops every process of the package (`UserHandle.USER_CURRENT`).
     *
     * Mirrors the hidden `IActivityManager.forceStopPackage`; used to reset the
     * target app to a clean state before automation. Failures are only logged.
     */
    fun forceStopPackage(packageName: String) {
        try {
            val method = getForceStopPackageMethod()
            method.invoke(manager, packageName, /* userId */ /* UserHandle.USER_CURRENT */ -2)
        } catch (e: Throwable) {
            Ln.e("Could not invoke method", e)
        }
    }

    internal companion object {
        /**
         * 经遗留的 `ActivityManagerNative.getDefault()` 拿 binder 并包装
         *
         * 老版本 Android 未把 ActivityManager 暴露为 AIDL 常规服务，只能走这条
         * 旁路获取；拿不到即进程环境异常，直接抛 [AssertionError]。
         *
         * Wraps the binder from the legacy `ActivityManagerNative.getDefault()`.
         *
         * Older Android versions do not expose the ActivityManager as a regular
         * AIDL service, so this side door is the way in; failure to obtain it
         * means a broken process environment and raises [AssertionError].
         */
        fun create(): ActivityManager {
            try {
                // 旧版本 Android 未把 ActivityManager 通过 AIDL 服务暴露，
                // 只能走 ActivityManagerNative.getDefault()
                val cls = Class.forName("android.app.ActivityManagerNative")
                val getDefaultMethod = cls.getDeclaredMethod("getDefault")
                val am = getDefaultMethod.invoke(null) as IInterface
                return ActivityManager(am)
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            }
        }
    }
}
