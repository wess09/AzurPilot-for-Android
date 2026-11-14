package com.azurpilot.ghio.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * 系统事件静态广播接收器
 *
 * 静态注册（exported=true）监听：开机完成 [Intent.ACTION_BOOT_COMPLETED]、重启
 * [Intent.ACTION_REBOOT]、用户解锁 [Intent.ACTION_USER_PRESENT] 与电源插拔
 * （[Intent.ACTION_POWER_CONNECTED] / [Intent.ACTION_POWER_DISCONNECTED]）。
 * 收到任一广播即触发 [KeepAliveManager.onKeepAlivePing] 全面自检自愈。亮灭屏广播
 * 属隐式广播豁免范围、无法静态注册，由 [KeepAliveManager] 的动态接收器负责。
 *
 * 若 [KeepAliveManager.getInstance] 为 null（如开机时主进程尚未初始化），则直接
 * 启动 [KeepAliveStickyService]，借助 START_STICKY 的系统重建带动进程自愈。
 *
 * 运行在主线程；onReceive 必须快速返回。
 *
 * Static system-event broadcast receiver.
 *
 * Statically registered (exported=true) for boot completion [Intent.ACTION_BOOT_COMPLETED],
 * reboot [Intent.ACTION_REBOOT], user unlock [Intent.ACTION_USER_PRESENT], and power plug
 * events ([Intent.ACTION_POWER_CONNECTED] / [Intent.ACTION_POWER_DISCONNECTED]). Any
 * delivery pings [KeepAliveManager.onKeepAlivePing] for a full self-check and self-heal.
 * Screen on/off broadcasts fall under the implicit-broadcast exemptions and cannot be
 * registered statically; [KeepAliveManager]'s dynamic receiver covers them.
 *
 * When [KeepAliveManager.getInstance] is null (for example at boot before the app has
 * initialized), this starts [KeepAliveStickyService] directly so the START_STICKY rebuild
 * drives process recovery. Runs on the main thread; onReceive must return quickly.
 */
class KeepAliveBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.d("KeepAliveBroadcastReceiver: Received system broadcast action=$action")

        val manager = KeepAliveManager.getInstance()
        if (manager != null) {
            manager.onKeepAlivePing()
        } else {
            // 若主控未初始化，尝试启动粘性服务触发进程自愈
            KeepAliveStickyService.start(context)
        }
    }
}
