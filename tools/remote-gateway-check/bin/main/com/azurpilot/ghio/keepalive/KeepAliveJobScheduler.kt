package com.azurpilot.ghio.keepalive

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import timber.log.Timber

/**
 * 系统作业调度器
 *
 * 调度系统级 15 分钟周期作业（JobScheduler 周期下限即 15 分钟），由 system_server
 * 持久化（[JobInfo.Builder.setPersisted]，重启后仍恢复）并在周期到达时拉起进程，
 * 即使主进程已被杀也能复活。manifest 已声明 RECEIVE_BOOT_COMPLETED 支撑持久化恢复。
 *
 * Schedules a 15-minute periodic system job (the JobScheduler minimum period), persisted
 * by system_server ([JobInfo.Builder.setPersisted], restored across reboots) and used to
 * resurrect the app process when the period elapses, even after the main process has
 * been killed. RECEIVE_BOOT_COMPLETED is declared in the manifest to support the
 * persisted restore.
 */
class KeepAliveJobScheduler(private val context: Context) {

    private val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private val componentName = ComponentName(context, KeepAliveJobService::class.java)

    /**
     * 排期周期作业；重复调用覆盖同 [JOB_ID] 的旧作业，失败仅记录不抛出
     *
     * Schedules the periodic job; repeat calls replace the job with the same
     * [JOB_ID]. Failures are logged, not thrown.
     */
    fun schedule() {
        try {
            val builder = JobInfo.Builder(JOB_ID, componentName)
                // 15 分钟周期 (Android JobScheduler 周期性任务最短间隔为 15 分钟)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setRequiresBatteryNotLow(false)
                builder.setRequiresCharging(false)
            }

            val result = jobScheduler.schedule(builder.build())
            if (result == JobScheduler.RESULT_SUCCESS) {
                Timber.d("KeepAliveJobScheduler: Job scheduled successfully")
            } else {
                Timber.w("KeepAliveJobScheduler: Job schedule returned failure code $result")
            }
        } catch (e: Exception) {
            Timber.e(e, "KeepAliveJobScheduler: Failed to schedule Job")
        }
    }

    /**
     * 取消周期作业；未排期时亦安全
     *
     * Cancels the periodic job; safe when nothing is scheduled.
     */
    fun cancel() {
        try {
            jobScheduler.cancel(JOB_ID)
            Timber.d("KeepAliveJobScheduler: Job cancelled")
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveJobScheduler: Failed to cancel Job")
        }
    }

    companion object {
        /** 作业 ID；固定值用于覆盖 / 取消同一作业 / Fixed job id used to replace or cancel the same job. */
        private const val JOB_ID = 9527
    }
}
