package com.azurpilot.ghio.remote.internal

import android.os.Build
import android.provider.Settings
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln

/**
 * 目标 app 的权限与后台豁免代授：AppOps、运行时权限、电池白名单、无障碍服务一个入口全包
 *
 * 跑在特权进程内，以 shell/root 身份直接写 IAppOpsService / IPackageManager /
 * IDeviceIdleController 与 Settings.Secure，绕过用户确认弹窗。
 * 每项 grant 独立 try/catch：失败记日志返回 false，绝不向上抛，调用方逐项决定降级。
 *
 * Grants the target app's permissions and background exemptions: AppOps, runtime permissions,
 * battery whitelist, and accessibility services from one entry point.
 *
 * Runs inside the privileged process; writes IAppOpsService / IPackageManager /
 * IDeviceIdleController and Settings.Secure directly under the shell/root identity, skipping
 * user-confirmation dialogs. Each grant has its own try/catch: failures log and return false,
 * never propagating — callers degrade per item.
 */
object PermissionGrantHelper {
    private const val TAG = "PermissionGrantHelper"

    /**
     * 直写 Settings.Secure 启用无障碍服务（shell 身份可写，无需走系统设置页）
     *
     * 已启用则幂等返回 true；否则把 serviceId 以 `:` 追加进
     * ENABLED_ACCESSIBILITY_SERVICES 并置 ACCESSIBILITY_ENABLED=1。
     *
     * Enables the accessibility service by writing Settings.Secure directly (writable with the
     * shell identity, no settings page needed).
     *
     * Idempotently returns true when already enabled; otherwise appends serviceId with `:` to
     * ENABLED_ACCESSIBILITY_SERVICES and sets ACCESSIBILITY_ENABLED=1.
     */
    fun grantAccessibilityService(serviceId: String): Boolean {
        if (serviceId == "") {
            return false
        }
        return try {
            val contentResolver = FakeContext.get().contentResolver
            val existingServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            if (existingServices.contains(serviceId)) {
                Ln.i("$TAG: Accessibility service already enabled: $serviceId")
                return true
            }

            val newServices = if (existingServices.isEmpty()) {
                serviceId
            } else {
                "$existingServices:$serviceId"
            }

            Settings.Secure.putString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                newServices
            )
            Settings.Secure.putInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Ln.i("$TAG: Accessibility service enabled: $serviceId")
            true
        } catch (e: Exception) {
            Ln.e("$TAG: Failed to enable accessibility service: $e")
            false
        }
    }


    /**
     * 授悬浮窗权限：AppOps OP_SYSTEM_ALERT_WINDOW(24) 置 MODE_ALLOWED
     *
     * Grants the floating-window permission: AppOps OP_SYSTEM_ALERT_WINDOW(24) set to
     * MODE_ALLOWED.
     */
    fun grantFloatingWindowPermission(packageName: String, uid: Int): Boolean {
        return try {
            RemoteUtils.appOpsService.setMode(24, uid, packageName, 0) // MODE_ALLOWED = 0
            Ln.i("$TAG: Floating window permission granted for $packageName")
            true
        } catch (e: Exception) {
            Ln.e("$TAG: Failed to grant floating window permission: $e")
            false
        }
    }


    /**
     * 授通知权限：AppOps OP_POST_NOTIFICATION(11) 置 MODE_ALLOWED；
     * Android 13+ 再补 POST_NOTIFICATIONS 运行时权限（补失败不影响整体返回值）
     *
     * Grants notification permission: AppOps OP_POST_NOTIFICATION(11) set to MODE_ALLOWED; on
     * Android 13+ the POST_NOTIFICATIONS runtime permission is additionally granted (its
     * failure does not change the return value).
     */
    fun grantNotificationPermission(packageName: String, uid: Int): Boolean {
        return try {
            RemoteUtils.appOpsService.setMode(11, uid, packageName, 0) // MODE_ALLOWED = 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    RemoteUtils.packageManager.grantRuntimePermission(
                        packageName,
                        "android.permission.POST_NOTIFICATIONS",
                        0
                    )
                }.onFailure {
                    Ln.w("$TAG: Failed to grant POST_NOTIFICATIONS runtime permission: $it")
                }
            }

            Ln.i("$TAG: Notification permission granted for $packageName")
            true
        } catch (e: Exception) {
            Ln.e("$TAG: Failed to grant notification permission: $e")
            false
        }
    }


    /**
     * 授电池优化豁免：把包加入 deviceidle 的省电白名单
     *
     * Grants the battery-optimization exemption by adding the package to the deviceidle
     * power-save whitelist.
     */
    fun grantBatteryOptimizationExemption(packageName: String): Boolean {
        return try {
            RemoteUtils.deviceIdleController.addPowerSaveWhitelistApp(packageName)
            Ln.i("$TAG: Battery optimization exemption granted for $packageName")
            true
        } catch (e: Exception) {
            Ln.e("$TAG: Failed to grant battery optimization exemption: $e")
            false
        }
    }


    /**
     * 解除后台限制：AppOps 后台两个 op + 待机桶拉到 active + 关 phantom process killer
     *
     * OP_RUN_IN_BACKGROUND(63) 与 OP_RUN_ANY_IN_BACKGROUND(65) 置 MODE_ALLOWED；
     * `am set-standby-bucket active`、`am set-inactive false`；Android 14+ 追加
     * `am set-bg-restriction-level unrestricted`。
     *
     * Lifts background restrictions: the two AppOps background ops, the standby bucket pulled
     * to active, and the phantom process killer disabled.
     *
     * OP_RUN_IN_BACKGROUND(63) and OP_RUN_ANY_IN_BACKGROUND(65) set to MODE_ALLOWED;
     * `am set-standby-bucket active` and `am set-inactive false`; on Android 14+ additionally
     * `am set-bg-restriction-level unrestricted`.
     */
    fun grantBackgroundUnrestricted(packageName: String, uid: Int): Boolean {
        return try {
            RemoteUtils.appOpsService.setMode(63, uid, packageName, 0)
            RemoteUtils.appOpsService.setMode(65, uid, packageName, 0)

            RemoteUtils.shellExec("am set-standby-bucket $packageName active")
            RemoteUtils.shellExec("am set-inactive $packageName false")

            // set-bg-restriction-level 仅 Android 14+ 可用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                RemoteUtils.shellExec("am set-bg-restriction-level $packageName unrestricted")
            }

            disablePhantomProcessKiller()

            Ln.i("$TAG: Background unrestricted for $packageName")
            true
        } catch (e: Exception) {
            Ln.e("$TAG: Failed to grant background unrestricted: $e")
            false
        }
    }

    /**
     * 禁用 Android 12+ 的 Phantom Process Killer
     *
     * 主手段关整条「扫描 + 记帐 + 裁剪」链，副手段把裁剪阈值顶到天花板；
     * Android 12 以下无事可做，直接返回 true。
     *
     * Disables the Android 12+ phantom process killer.
     *
     * The primary lever switches off the whole scan + accounting + trim chain; the secondary
     * lever pins the trim threshold to the ceiling. Below Android 12 there is nothing to do
     * and it returns true.
     */
    fun disablePhantomProcessKiller(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return try {
            // Spike C（2026-09-15 真机实证）：主手段=关整条"扫描+记帐+裁剪"链；
            // 副手段=裁剪阈值顶到天花板。旧键 settings_config_disable_monitor_phantom_procs /
            // phantom_process_killer_enable 实测无效（开跑 2 秒即被裁），不再写
            RemoteUtils.shellExec("settings put global settings_enable_monitor_phantom_procs false")
            RemoteUtils.shellExec("device_config put activity_manager max_phantom_processes 2147483647")
            Ln.i("$TAG: Phantom process killer disabled")
            true
        } catch (e: Exception) {
            Ln.e("$TAG: Failed to disable phantom process killer: $e")
            false
        }
    }

    /**
     * 授存储权限：Android 11+ 直接给 MANAGE_EXTERNAL_STORAGE 的 AppOps；
     * 旧系统走 READ/WRITE_EXTERNAL_STORAGE 运行时权限
     *
     * Grants storage permission: on Android 11+ the MANAGE_EXTERNAL_STORAGE AppOps directly;
     * on older releases the READ/WRITE_EXTERNAL_STORAGE runtime permissions.
     */
    fun grantStoragePermission(packageName: String, uid: Int): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                RemoteUtils.appOpsService.setMode(92, uid, packageName, 0) // MODE_ALLOWED = 0
                Ln.i("$TAG: MANAGE_EXTERNAL_STORAGE granted for $packageName via AppOps")
            } else {
                RemoteUtils.packageManager.grantRuntimePermission(
                    packageName,
                    "android.permission.READ_EXTERNAL_STORAGE",
                    0
                )
                RemoteUtils.packageManager.grantRuntimePermission(
                    packageName,
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    0
                )
                Ln.i("$TAG: Storage permission granted for $packageName")
            }
            true
        } catch (e: Exception) {
            Ln.e("$TAG: Failed to grant storage permission: $e", e)
            false
        }
    }
}
