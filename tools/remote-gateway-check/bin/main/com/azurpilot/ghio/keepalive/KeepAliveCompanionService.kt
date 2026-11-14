package com.azurpilot.ghio.keepalive

import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * 伴侣设备系统回调服务。
 *
 * Android 12+ 可在存在已建立的伴侣设备关联时绑定 [CompanionDeviceService] 并投递设备事件。
 * 本服务不创建关联；当前工程也没有 [android.companion.CompanionDeviceManager] 的关联流程。因此，
 * 没有关联时该服务不会成为保活触发源。声明的伴侣权限也不保证后台执行、电池限制或网络访问豁免。
 * 回调到达时仅触发 [KeepAliveManager.onKeepAlivePing] 的尽力而为自检。
 *
 * Companion-device system callback service.
 *
 * Android 12+ can bind a [CompanionDeviceService] and deliver device events when an existing companion
 * association is present. This service does not create associations, and this repository has no
 * [android.companion.CompanionDeviceManager] association flow. Without an association it is not a
 * keep-alive trigger. Declared companion permissions do not guarantee background execution, battery,
 * or network exemptions. When a callback arrives, it only triggers the best-effort
 * [KeepAliveManager.onKeepAlivePing] self-check.
 */
@RequiresApi(Build.VERSION_CODES.S)
class KeepAliveCompanionService : CompanionDeviceService() {

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveCompanionService: Service created")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
    }

    /**
     * 伴侣设备出现（系统回调）；顺带触发一次保活自检
     *
     * Companion device appeared (system callback); also triggers a keep-alive
     * self-check.
     */
    @Deprecated("Deprecated in Java / API 33")
    override fun onDeviceAppeared(address: String) {
        Timber.d("KeepAliveCompanionService: onDeviceAppeared address=$address")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
    }

    /**
     * 伴侣设备消失（系统回调）；仅记录，不做保活动作
     *
     * Companion device disappeared (system callback); logs only, no keep-alive
     * action.
     */
    @Deprecated("Deprecated in Java / API 33")
    override fun onDeviceDisappeared(address: String) {
        Timber.d("KeepAliveCompanionService: onDeviceDisappeared address=$address")
    }

    override fun onDestroy() {
        Timber.d("KeepAliveCompanionService: Service destroyed")
        super.onDestroy()
    }
}
