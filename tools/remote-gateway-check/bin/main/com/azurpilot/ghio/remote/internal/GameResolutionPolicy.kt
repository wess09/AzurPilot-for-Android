package com.azurpilot.ghio.remote.internal

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 关闭单个包的屏幕兼容缩放；调用方在特权线程串行执行并在配置变化后强停旧进程。
 *
 * APS 的持久配置和动态配置分别控制兼容尺寸与运行期缩放，必须分别读取并验证。
 * 不调整帧率、纹理、亮度或全局低分辨率开关；无 APS 服务的设备只处理标准兼容模式。
 *
 * Disables screen compatibility scaling for one package. The caller serializes privileged-thread
 * calls and force-stops the old process after a configuration change.
 *
 * Persistent and dynamic APS settings control compatibility dimensions and runtime scaling
 * separately, so both are read and verified. Frame rate, texture, brightness, and global
 * low-resolution settings are untouched. Devices without APS use only standard compatibility mode.
 */
internal object GameResolutionPolicy {
    /**
     * 将目标包的静态和动态 APS 比例恢复为 1；返回是否修改了配置。
     *
     * Restores the package's persistent and dynamic APS ratios to 1, returning whether they changed.
     *
     * @throws IllegalStateException ROM 拒绝修改或回读仍有缩放 / when the ROM rejects a change or
     *   readback still indicates scaling
     */
    fun disableApsScaling(manager: Any?, packageName: String): Boolean {
        if (manager == null) return false
        val info = manager.call("getPackageApsInfo", packageName)
        val configured = (info?.call("getResolutionRatio") as? Number)?.toFloat()
        val effective = (manager.call("getResolution", packageName) as Number).toFloat()
        check(effective.isFinite()) { "Invalid APS resolution ratio for $packageName: $effective" }
        var changed = false
        if ((configured != null && configured > 0f && configured != 1f) ||
            (effective > 0f && effective != 1f)
        ) {
            val status = manager.call("setResolution", packageName, 1f, false) as Int
            check(status == 0) { "APS refused native resolution for $packageName (status=$status)" }
            val after = (manager.call("getResolution", packageName) as Number).toFloat()
            val saved = manager.call("getPackageApsInfo", packageName)
            check(after.isFinite() && (after <= 0f || after == 1f) &&
                (saved == null || (saved.call("getResolutionRatio") as Number).toFloat() == 1f)
            ) { "APS still scales $packageName after setResolution: $after" }
            changed = true
        }

        // 旧版 APS 没有动态接口；只允许缺少方法，权限拒绝和服务故障必须上报。
        val dynamicGetter = manager.javaClass.methods.firstOrNull {
            it.name == "getDynamicResolutionRatio" && it.parameterTypes.contentEquals(
                arrayOf(String::class.java)
            )
        }?.also { it.isAccessible = true } ?: return changed
        val dynamic = (invokeManager(dynamicGetter, manager, packageName) as Number).toFloat()
        check(dynamic.isFinite()) { "Invalid dynamic APS ratio for $packageName: $dynamic" }
        if (dynamic > 0f && dynamic != 1f) {
            val status = manager.call("setDynamicResolutionRatio", packageName, 1f) as Int
            val after = (invokeManager(dynamicGetter, manager, packageName) as Number).toFloat()
            // APS 可在更新成功后返回 -5（游戏尚未注册回调）；必须以回读比例确认。
            check((status == 0 || status == -5) && after == 1f) {
                "APS refused native dynamic resolution for $packageName (status=$status, ratio=$after)"
            }
            changed = true
        }
        return changed
    }

    /**
     * 关闭标准屏幕兼容模式；已关闭或无需兼容时不写入，返回是否修改了配置。
     *
     * Disables standard screen compatibility mode without rewriting disabled or unnecessary modes,
     * returning whether the configuration changed.
     */
    fun disableScreenCompat(manager: Any, packageName: String): Boolean {
        val mode = manager.call("getPackageScreenCompatMode", packageName) as Int
        check(mode == 0 || mode == 1 || mode == -2) {
            "Cannot use native screen dimensions for $packageName (compatMode=$mode)"
        }
        if (mode != 1) return false
        manager.call("setPackageScreenCompatMode", packageName, 0)
        val after = manager.call("getPackageScreenCompatMode", packageName) as Int
        check(after == 0 || after == -2) {
            "Screen compatibility remains enabled for $packageName (compatMode=$after)"
        }
        return true
    }

    private fun Any.call(name: String, vararg args: Any): Any? {
        val types = args.map {
            when (it) {
                is Int -> Int::class.javaPrimitiveType!!
                is Float -> Float::class.javaPrimitiveType!!
                is Boolean -> Boolean::class.javaPrimitiveType!!
                else -> it.javaClass
            }
        }.toTypedArray()
        val method = javaClass.getMethod(name, *types).also { it.isAccessible = true }
        return invokeManager(method, this, *args)
    }

    private fun invokeManager(method: Method, manager: Any, vararg args: Any): Any? = try {
        method.invoke(manager, *args)
    } catch (error: InvocationTargetException) {
        // 桥错误须保留实际权限拒绝原因，不能只向用户返回反射包装异常。
        throw error.targetException
    }
}
