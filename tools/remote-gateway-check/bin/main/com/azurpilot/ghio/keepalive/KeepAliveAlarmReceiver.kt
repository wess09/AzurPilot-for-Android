package com.azurpilot.ghio.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * 周期性闹钟心跳接收器
 *
 * 触发源：[KeepAliveAlarmScheduler] 经 AlarmManager 精准闹钟
 * （setExactAndAllowWhileIdle）发出的显式 PendingIntent——静态注册于 manifest
 * （exported=false 且无 intent-filter），这是唯一触发路径。Doze 下
 * AllowWhileIdle 窗口仍允许唤醒 CPU，从而维持 5 分钟级心跳环路。
 *
 * 收到广播后调 [KeepAliveManager.onKeepAlivePing] 对全部保活子系统自检自愈，
 * 再排期下一轮闹钟维持心跳环路。若 [KeepAliveManager.getInstance] 为 null
 * （管理器尚未初始化）则本轮心跳被丢弃，环路终止，需等待下一次重新排期。
 *
 * 运行在主线程；onReceive 必须快速返回，不得做耗时操作。
 *
 * Periodic alarm heartbeat receiver.
 *
 * Trigger source: the explicit PendingIntent sent via an AlarmManager exact alarm
 * (setExactAndAllowWhileIdle) by [KeepAliveAlarmScheduler] — statically registered in
 * the manifest (exported=false, no intent-filter), making this the only trigger path.
 * The AllowWhileIdle window still wakes the CPU inside Doze, sustaining the 5-minute
 * heartbeat loop.
 *
 * Each delivery pings [KeepAliveManager.onKeepAlivePing] to self-check and heal every
 * keep-alive subsystem, then schedules the next alarm to keep the loop running. When
 * [KeepAliveManager.getInstance] is null (manager not yet initialized) the beat is
 * dropped and the loop stops until something reschedules it. Runs on the main thread;
 * onReceive must return quickly.
 */
class KeepAliveAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Timber.d("KeepAliveAlarmReceiver: Alarm heartbeat received")
        val manager = KeepAliveManager.getInstance()
        if (manager != null) {
            manager.onKeepAlivePing()
            manager.scheduleNextAlarm()
        }
    }
}
