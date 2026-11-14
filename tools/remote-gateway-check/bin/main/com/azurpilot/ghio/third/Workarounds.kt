package com.azurpilot.ghio.third

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper

import com.azurpilot.ghio.constant.AndroidVersions

import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 为特权进程伪造一套"App 运行环境"的补丁集合
 *
 * app_process 拉起的特权进程没有 Application、没有 main Looper，而 framework
 * 大量代码（DisplayManagerGlobal、Context 获取等）默认这些都在。本类在类加载的
 * `init` 里一次性构造 [Looper] + 伪造 `android.app.ActivityThread`
 * （`sCurrentActivityThread` / `mSystemThread=true`），后续 [apply] 再按设备
 * 补齐 ConfigurationController、AppBindData、初始 Application。
 *
 * 线程归属：类加载与 [apply] 必须发生在进程主线程（Looper 归属不可转移）。
 * `init` 失败直接抛 [AssertionError] —— 特权进程连补丁环境都拿不到，无法继续。
 *
 * 从 scrcpy 服务端的 `third/Workarounds.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * A set of workarounds faking an "app environment" for the privileged process.
 *
 * A privileged process spawned via app_process has no Application and no main
 * Looper, yet plenty of framework code (DisplayManagerGlobal, context
 * acquisition) assumes both exist. The class `init` builds the [Looper] plus a
 * fabricated `android.app.ActivityThread` (`sCurrentActivityThread` /
 * `mSystemThread=true`) in one shot; [apply] then fills in the
 * ConfigurationController, AppBindData and initial Application as the device
 * requires.
 *
 * Thread affinity: class loading and [apply] must happen on the process main
 * thread (a Looper's thread affinity cannot be moved). An `init` failure throws
 * [AssertionError] — without the patched environment the privileged process
 * cannot continue.
 *
 * Ported from scrcpy's server `third/Workarounds.java` (Apache-2.0,
 * Genymobile/scrcpy).
 */
@SuppressLint("PrivateApi,BlockedPrivateApi,SoonBlockedPrivateApi,DiscouragedPrivateApi")
object Workarounds {

    private val ACTIVITY_THREAD_CLASS: Class<*>
    private val ACTIVITY_THREAD: Any

    init {
        try {
            prepareMainLooper()

            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread")
            val activityThreadConstructor: Constructor<*> = ACTIVITY_THREAD_CLASS.getDeclaredConstructor()
            activityThreadConstructor.isAccessible = true
            ACTIVITY_THREAD = activityThreadConstructor.newInstance()

            val sCurrentActivityThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread")
            sCurrentActivityThreadField.isAccessible = true
            sCurrentActivityThreadField.set(null, ACTIVITY_THREAD)

            val mSystemThreadField = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread")
            mSystemThreadField.isAccessible = true
            mSystemThreadField.setBoolean(ACTIVITY_THREAD, true)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    /**
     * 给当前线程准备一个"main" Looper
     *
     * 与 `Looper.prepareMainLooper()` 的区别仅在 quitAllowed 传 true —— 但
     * ActivityThread 伪造流程需要手动设置 `sMainLooper`，公开 API 做不到这点。
     * 已有 Looper 的线程直接跳过。
     *
     * Prepares a "main" Looper for the current thread.
     *
     * The only difference from `Looper.prepareMainLooper()` is that
     * quitAllowed is true — the ActivityThread fabrication needs to assign
     * `sMainLooper` manually, which the public API does not allow. Threads that
     * already have a Looper are skipped.
     */
    fun prepareMainLooper() {
        if (Looper.myLooper() != null) {
            return
        }
        // 与 Looper.prepareMainLooper() 等价，但 quitAllowed 传 true
        Looper.prepare()
        synchronized(Looper::class.java) {
            try {
                val field = Looper::class.java.getDeclaredField("sMainLooper")
                field.isAccessible = true
                field.set(null, Looper.myLooper())
            } catch (e: ReflectiveOperationException) {
                throw AssertionError(e)
            }
        }
    }

    /**
     * 应用设备相关的运行时补丁（进程启动早期调用一次）
     *
     * 依次补齐：ConfigurationController（仅 API 31+，且必须先于 fillAppContext
     * 执行 —— 它是拿到有效 system context 的前提；部分 Samsung 机型上
     * `DisplayManagerGlobal.getDisplayInfoLocked()` 会调
     * `ActivityThread.currentActivityThread().getConfiguration()`，需要非空的
     * ConfigurationController，见 <https://github.com/Genymobile/scrcpy/issues/4467>）；
     * AppBindData（ONYX 设备上跳过 —— 填了会破坏投屏，
     * 见 <https://github.com/Genymobile/scrcpy/issues/5182>）；初始 Application。
     * 全部尽力而为，单项失败只记调试日志。
     *
     * Applies the device-specific runtime patches (once, early in process
     * startup).
     *
     * In order: the ConfigurationController (API 31+ only, and it must precede
     * [getSystemContext] — on some Samsung devices
     * `DisplayManagerGlobal.getDisplayInfoLocked()` calls
     * `ActivityThread.currentActivityThread().getConfiguration()`, which needs a
     * non-null ConfigurationController, see
     * <https://github.com/Genymobile/scrcpy/issues/4467>); AppBindData (skipped
     * on ONYX devices, where it breaks video mirroring, see
     * <https://github.com/Genymobile/scrcpy/issues/5182>); the initial
     * Application. All best-effort: an individual failure only logs at debug.
     */
    fun apply() {
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
            // ConfigurationController 是 Android 12 才引入的，低版本不做尝试
            fillConfigurationController()
        }

        // ONYX 设备上 fillAppInfo() 会破坏投屏：
        // <https://github.com/Genymobile/scrcpy/issues/5182>
        val mustFillAppInfo = !Build.BRAND.equals("ONYX", ignoreCase = true)

        if (mustFillAppInfo) {
            fillAppInfo()
        }

        fillAppContext()
    }

    /**
     * 伪造 `ActivityThread.AppBindData` 并挂到 `mBoundApplication`，让
     * `currentActivityThread().getAppInfo()` 类查询能拿到 shell 包名
     *
     * 尽力而为：失败只记调试日志（补丁环境缺一块总比整个进程起不来强）。
     *
     * Fakes an `ActivityThread.AppBindData` and binds it to `mBoundApplication`,
     * so queries like `currentActivityThread().getAppInfo()` resolve to the
     * shell package.
     *
     * Best-effort: failure only logs at debug (a partially patched environment
     * beats a process that never starts).
     */
    private fun fillAppInfo() {
        try {
            val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindDataConstructor = appBindDataClass.getDeclaredConstructor()
            appBindDataConstructor.isAccessible = true
            val appBindData = appBindDataConstructor.newInstance()

            val applicationInfo = ApplicationInfo()
            applicationInfo.packageName = FakeContext.PACKAGE_NAME

            val appInfoField = appBindDataClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(appBindData, applicationInfo)

            val mBoundApplicationField = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication")
            mBoundApplicationField.isAccessible = true
            mBoundApplicationField.set(ACTIVITY_THREAD, appBindData)
        } catch (throwable: Throwable) {
            // 这是尽力而为的补丁，失败不算错误
            Ln.d("Could not fill app info: " + throwable.message)
        }
    }

    /**
     * 用 [FakeContext] 实例化一个裸 Application 并挂到 `mInitialApplication`
     *
     * 尽力而为：失败只记调试日志。
     *
     * Instantiates a bare Application around [FakeContext] and binds it as
     * `mInitialApplication`.
     *
     * Best-effort: failure only logs at debug.
     */
    private fun fillAppContext() {
        try {
            val app = Instrumentation.newApplication(Application::class.java, FakeContext.get())

            val mInitialApplicationField = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication")
            mInitialApplicationField.isAccessible = true
            mInitialApplicationField.set(ACTIVITY_THREAD, app)
        } catch (throwable: Throwable) {
            // 这是尽力而为的补丁，失败不算错误
            Ln.d("Could not fill app context: " + throwable.message)
        }
    }

    /**
     * 给伪造的 ActivityThread 补一个 ConfigurationController（API 31+）
     *
     * 尽力而为：失败只记调试日志。
     *
     * Attaches a ConfigurationController to the fabricated ActivityThread
     * (API 31+).
     *
     * Best-effort: failure only logs at debug.
     */
    private fun fillConfigurationController() {
        try {
            val configurationControllerClass = Class.forName("android.app.ConfigurationController")
            val activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal")

            val configurationControllerConstructor = configurationControllerClass
                .getDeclaredConstructor(activityThreadInternalClass)
            configurationControllerConstructor.isAccessible = true
            val configurationController = configurationControllerConstructor.newInstance(ACTIVITY_THREAD)

            val configurationControllerField = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController")
            configurationControllerField.isAccessible = true
            configurationControllerField.set(ACTIVITY_THREAD, configurationController)
        } catch (throwable: Throwable) {
            Ln.d("Could not fill configuration: " + throwable.message)
        }
    }

    /**
     * 取 ActivityThread 的 system context，作为 [FakeContext] 的基座
     *
     * 依赖 [apply] 前置补丁；失败返回 null（[FakeContext] 以空基座继续运行）。
     *
     * Returns the ActivityThread system context, used as the [FakeContext] base.
     *
     * Depends on the patches applied by [apply]; returns null on failure
     * ([FakeContext] then runs on a null base).
     */
    internal fun getSystemContext(): Context? {
        return try {
            val getSystemContextMethod = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext")
            getSystemContextMethod.invoke(ACTIVITY_THREAD) as Context
        } catch (throwable: Throwable) {
            // 这是尽力而为的补丁，失败不算错误
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            pw.flush()
            Ln.d("Could not get system context: " + sw)
            null
        }
    }
}
