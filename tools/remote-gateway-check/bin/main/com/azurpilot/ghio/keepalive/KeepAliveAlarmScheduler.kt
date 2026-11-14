package com.azurpilot.ghio.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import timber.log.Timber

/**
 * 精准闹钟调度器
 *
 * 利用 [AlarmManager.setExactAndAllowWhileIdle] 突破系统 Doze 休眠限制，
 * 建立 [DEFAULT_INTERVAL_MS]（5 分钟）周期的不可休眠心跳唤醒环路。manifest 已声明
 * SCHEDULE_EXACT_ALARM；精准调度失败（如权限被用户撤回）时退化为非精准的
 * [AlarmManager.set]，心跳仍在但可能被 Doze 推迟。
 *
 * An exact alarm scheduler for keep-alive.
 *
 * Uses [AlarmManager.setExactAndAllowWhileIdle] to bypass Doze, maintaining a
 * wake-up heartbeat loop of [DEFAULT_INTERVAL_MS] (5 minutes). SCHEDULE_EXACT_ALARM
 * is declared in the manifest; when exact scheduling fails (for example the permission
 * was revoked by the user) it degrades to the inexact [AlarmManager.set] — the
 * heartbeat survives, though Doze may defer it.
 */
class KeepAliveAlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 构造指向 [KeepAliveAlarmReceiver] 的显式 PendingIntent / Builds the explicit PendingIntent targeting [KeepAliveAlarmReceiver]. */
    private fun getPendingIntent(): PendingIntent {
        val intent = Intent(context, KeepAliveAlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /**
     * 排期下一轮唤醒闹钟；重复调用覆盖同一 PendingIntent（FLAG_UPDATE_CURRENT）而非叠加
     *
     * Schedules the next wake-up alarm; repeat calls replace the same PendingIntent
     * (FLAG_UPDATE_CURRENT) rather than stack.
     *
     * @param delayMs 距触发的延迟 / delay before firing; defaults to
     *   [DEFAULT_INTERVAL_MS]
     */
    fun scheduleNext(delayMs: Long = DEFAULT_INTERVAL_MS) {
        try {
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            val pendingIntent = getPendingIntent()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Timber.d("KeepAliveAlarmScheduler: Next wake-up alarm scheduled in ${delayMs / 1000}s")
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveAlarmScheduler: Failed to schedule exact alarm; falling back")
            runCatching {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs,
                    getPendingIntent()
                )
            }
        }
    }

    /**
     * 取消未触发的唤醒闹钟；未排期时亦安全
     *
     * Cancels the pending wake-up alarm; safe when nothing is scheduled.
     */
    fun cancel() {
        try {
            alarmManager.cancel(getPendingIntent())
            Timber.d("KeepAliveAlarmScheduler: Wake-up alarm cancelled")
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveAlarmScheduler: Failed to cancel alarm")
        }
    }

    companion object {
        /** PendingIntent 请求码；固定值用于覆盖同一张闹钟 / Fixed PendingIntent request code used to replace the same alarm. */
        private const val REQUEST_CODE = 8848

        /** 心跳间隔：5 分钟 / Heartbeat interval: 5 minutes. */
        const val DEFAULT_INTERVAL_MS = 5 * 60 * 1000L
    }
}
