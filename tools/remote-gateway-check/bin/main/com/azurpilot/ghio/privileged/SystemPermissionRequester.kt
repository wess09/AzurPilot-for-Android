package com.azurpilot.ghio.privileged

import android.app.Activity
import android.content.Context
import com.azurpilot.ghio.constant.PrivilegedGrant
import com.azurpilot.ghio.service.AccessibilityHelperService
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * 首页权限卡展示的系统权限
 *
 * 特权进程上线即代授全集；这几项是特权进程没起来时给用户手点的兜底。
 * app 进程一死，特权进程的看门狗就自杀并释放虚拟屏——实测 MIUI 的
 * `ProcessManager: SwipeUpClean` 会按 Adj=905 直接 force-stop。
 *
 * System permissions surfaced on the home permission card.
 *
 * The privileged process grants the full set as soon as it is up; these are
 * the fallback the user taps manually while it is not. When the app process
 * dies, the privileged process's watchdog kills itself and releases the
 * virtual display — MIUI's `ProcessManager: SwipeUpClean` was observed to
 * force-stop outright at Adj=905.
 *
 * @param grantBit [PrivilegedGrant] 里对应的代授位 / the matching grant bit in [PrivilegedGrant]
 */
enum class SystemPermission(val grantBit: Int) {
    /** 通知权限 / notification permission */
    Notification(PrivilegedGrant.NOTIFICATION),

    /** 电池优化白名单 / battery-optimization whitelist */
    BatteryWhitelist(PrivilegedGrant.BATTERY),

    /** 悬浮窗 / system alert window */
    Overlay(PrivilegedGrant.OVERLAY),

    /** 外部存储（MANAGE_EXTERNAL_STORAGE）/ external storage (MANAGE_EXTERNAL_STORAGE) */
    Storage(PrivilegedGrant.STORAGE),

    /** 本 app 的无障碍服务 / this app's accessibility service */
    Accessibility(PrivilegedGrant.ACCESSIBILITY),
}

/**
 * 探测与请求保活系统权限；需要 Activity 的动作由 Route 层执行
 *
 * 无状态 object。走 XXPermissions 而不是自己拼 Intent：MIUI 的电池优化白名单
 * 判定与系统页跳转都有偏差。
 *
 * Probes and requests the keep-alive system permissions; actions needing an
 * Activity are executed by the Route layer.
 *
 * A stateless object. XXPermissions over hand-rolled Intents: MIUI's
 * battery-optimization whitelist checks and system-page jumps both misbehave.
 */
object SystemPermissionRequester {

    /**
     * 本地读权限状态，不弹任何界面；读取异常按未授权处理
     *
     * Reads the permission state locally without any UI; read errors count as
     * not granted.
     */
    fun isGranted(context: Context, permission: SystemPermission): Boolean = runCatching {
        XXPermissions.isGrantedPermission(context, permission.toPlatform())
    }.onFailure { Timber.w(it, "Failed to read permission state: $permission") }.getOrDefault(false)

    /**
     * 请求单个权限；已授权直接返回 true
     *
     * Requests a single permission; returns true immediately when granted.
     *
     * @return 用户授没授成 / whether the user granted it
     */
    suspend fun request(activity: Activity, permission: SystemPermission): Boolean {
        if (isGranted(activity, permission)) return true
        return suspendCancellableCoroutine { cont ->
            XXPermissions.with(activity)
                .permission(permission.toPlatform())
                .request { granted, _ -> cont.resume(granted.isNotEmpty()) }
        }
    }

    /**
     * 业务枚举 → XXPermissions 平台描述；无障碍绑定到本 app 的服务类
     *
     * Maps the enum to an XXPermissions descriptor; accessibility binds to
     * this app's service class.
     */
    private fun SystemPermission.toPlatform(): IPermission = when (this) {
        SystemPermission.Notification -> PermissionLists.getPostNotificationsPermission()
        SystemPermission.BatteryWhitelist ->
            PermissionLists.getRequestIgnoreBatteryOptimizationsPermission()
        SystemPermission.Overlay -> PermissionLists.getSystemAlertWindowPermission()
        SystemPermission.Storage -> PermissionLists.getManageExternalStoragePermission()
        SystemPermission.Accessibility ->
            PermissionLists.getBindAccessibilityServicePermission(AccessibilityHelperService::class.java)
    }
}
