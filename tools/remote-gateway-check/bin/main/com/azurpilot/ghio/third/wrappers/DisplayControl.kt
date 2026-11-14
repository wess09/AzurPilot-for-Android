package com.azurpilot.ghio.third.wrappers

import android.annotation.SuppressLint
import android.os.IBinder
import android.system.Os

import androidx.annotation.RequiresApi

import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.third.Ln

import java.lang.reflect.Method

/**
 * 隐藏系统类 `com.android.server.display.DisplayControl` 的反射包装（API 34+）
 *
 * Android 14 起物理屏 token 改由 system_server 侧的 DisplayControl 静态方法提供
 * （[getPhysicalDisplayIds] / [getPhysicalDisplayToken]）。该类不在 boot
 * classpath 上：init 时用 `ClassLoaderFactory.createClassLoader` 以
 * `SYSTEMSERVERCLASSPATH` 拼一个 system_server 类加载器加载它，并预加载
 * `libandroid_servers.so`（其 JNI 依赖）。
 *
 * 进程级单例，供特权进程的视频/捕获链路解析物理屏。init 失败不抛
 * （[CLASS] 置 null），调用方法时才失败并返回 null。
 *
 * 从 scrcpy 服务端的 `third/wrappers/DisplayControl.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * Reflection wrapper around the hidden system class
 * `com.android.server.display.DisplayControl` (API 34+).
 *
 * Since Android 14 the physical display tokens come from the static methods of
 * DisplayControl, which lives on the system_server side
 * ([getPhysicalDisplayIds] / [getPhysicalDisplayToken]). The class is not on
 * the boot classpath: `init` builds a system_server class loader over
 * `SYSTEMSERVERCLASSPATH` via `ClassLoaderFactory.createClassLoader`, loads the
 * class through it, and preloads `libandroid_servers.so` (its JNI dependency).
 *
 * Process-level object used by the privileged process's video/capture chain to
 * resolve physical displays. Initialization failures are swallowed ([CLASS]
 * becomes null) and surface later as null returns from the methods.
 *
 * Ported from scrcpy's server `third/wrappers/DisplayControl.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
@SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
@RequiresApi(AndroidVersions.API_34_ANDROID_14)
object DisplayControl {

    private val CLASS: Class<*>?

    init {
        var displayControlClass: Class<*>? = null
        try {
            val classLoaderFactoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod(
                "createClassLoader", String::class.java, String::class.java, String::class.java,
                ClassLoader::class.java, Int::class.java, Boolean::class.java, String::class.java
            )

            val systemServerClasspath = Os.getenv("SYSTEMSERVERCLASSPATH")
            val classLoader = createClassLoaderMethod.invoke(
                null, systemServerClasspath, null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null
            ) as ClassLoader

            displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl")

            val loadMethod = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            loadMethod.isAccessible = true
            loadMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers")
        } catch (e: Throwable) {
            Ln.e("Could not initialize DisplayControl", e)
            // 这里不抛异常，等到方法被调用时再失败
        }
        CLASS = displayControlClass
    }

    private var getPhysicalDisplayTokenMethod: Method? = null
    private var getPhysicalDisplayIdsMethod: Method? = null

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
            method = CLASS!!.getMethod("getPhysicalDisplayToken", Long::class.java)
            getPhysicalDisplayTokenMethod = method
        }
        return method
    }

    /**
     * 把物理屏 ID 解析成 SurfaceFlinger 用的 token
     *
     * 对应隐藏 `DisplayControl.getPhysicalDisplayToken`；反射失败返回 null。
     *
     * Resolves a physical display id into the token used by SurfaceFlinger.
     *
     * Mirrors the hidden `DisplayControl.getPhysicalDisplayToken`; returns null
     * when reflection fails.
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
            method = CLASS!!.getMethod("getPhysicalDisplayIds")
            getPhysicalDisplayIdsMethod = method
        }
        return method
    }

    /**
     * 列出全部物理屏 ID
     *
     * 对应隐藏 `DisplayControl.getPhysicalDisplayIds`；init 阶段类加载失败或
     * 反射失败时返回 null。
     *
     * Lists all physical display ids.
     *
     * Mirrors the hidden `DisplayControl.getPhysicalDisplayIds`; returns null
     * when the init-time class load or the reflection failed.
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
}
