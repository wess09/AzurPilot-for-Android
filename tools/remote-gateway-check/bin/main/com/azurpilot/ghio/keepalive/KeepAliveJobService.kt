package com.azurpilot.ghio.keepalive

import android.app.job.JobParameters
import android.app.job.JobService
import timber.log.Timber

/**
 * 周期性 JobScheduler 回调服务。
 *
 * JobScheduler 可在满足系统约束时启动此服务，并可能创建应用进程。当前实现仅在该进程已初始化
 * [KeepAliveManager] 时调用 [KeepAliveManager.onKeepAlivePing]；冷启动时管理器为 null，作业只会
 * 记录触发而不执行修复。调度频率和实际投递时间由系统与 OEM 策略决定。
 *
 * onStartJob 返回 false，因为本实现没有异步工作；onStopJob 返回 true，请求系统在约束允许时重排。
 *
 * Periodic JobScheduler callback service.
 *
 * JobScheduler can start this service when system constraints permit and may create the application
 * process. The current implementation calls [KeepAliveManager.onKeepAlivePing] only when that process
 * has already initialized [KeepAliveManager]; on a cold start the manager is null, so the job only logs
 * its delivery and performs no repair. System and OEM policy determine scheduling frequency and actual
 * delivery time.
 *
 * onStartJob returns false because this implementation has no asynchronous work. onStopJob returns
 * true to request rescheduling when constraints allow it.
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Timber.d("KeepAliveJobService: Job triggered by system JobScheduler")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
        // 自检同步完成即可返回 false，无需系统保持 wakelock 等待异步结果
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Timber.d("KeepAliveJobService: Job stopped")
        // 返回 true 请求系统在约束允许时重新调度本次作业
        return true
    }
}
