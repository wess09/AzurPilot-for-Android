package com.azurpilot.ghio.keepalive

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import timber.log.Timber

/**
 * START_STICKY 粘性守护服务
 *
 * 触发源：[KeepAliveManager]、各接收器与双进程守护服务的 start() 显式拉起
 * （manifest 中 exported=false）。onStartCommand 返回 [Service.START_STICKY]：
 * 服务因内存压力被杀后，系统在资源允许时以 null intent 自动重建本服务，带动宿主
 * 进程复活。
 *
 * 自检只在 onCreate 做一次：[KeepAliveManager.onKeepAlivePing] 内部会重新
 * startService 本服务，若 onStartCommand 也回调它便形成自我触发环路
 * （见方法内注释），因此 onStartCommand 只返回 START_STICKY，
 * 进程重建后的自愈由 onCreate 承担。
 *
 * Sticky background daemon service.
 *
 * Trigger source: explicit start() calls from [KeepAliveManager], the receivers, and
 * the dual-process watchdog services (exported=false in the manifest). onStartCommand
 * returns [Service.START_STICKY]: after the service is killed under memory pressure,
 * the system recreates it automatically with a null intent once resources allow,
 * reviving the host process.
 *
 * The self-check runs in onCreate only: [KeepAliveManager.onKeepAlivePing] restarts this
 * service through startService, so invoking it from onStartCommand as well would form a
 * self-triggering loop (see the inline comment). onStartCommand only returns
 * START_STICKY, leaving the post-rebuild self-heal to onCreate.
 */
class KeepAliveStickyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("KeepAliveStickyService: Service created")
        KeepAliveManager.getInstance()?.onKeepAlivePing()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("KeepAliveStickyService: onStartCommand flags=$flags startId=$startId")
        // 这里刻意不再回调 onKeepAlivePing()：该方法内部会重新 startService 本服务，
        // 于是形成「startService → onStartCommand → startService」的自我触发环路，
        // 主线程被持续占满（应用 ANR、界面只剩一张白底），
        // 同时环路里每轮都会 startService 双进程守护，令其 bindService 连接无限累积。
        // 进程被系统重建时的自愈已由 onCreate() 承担，onStartCommand 无需重复触发。
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.d("KeepAliveStickyService: Service destroyed")
        super.onDestroy()
    }

    companion object {
        /** 显式拉起粘性服务；失败（如后台启动服务限制）仅记录 / Starts the sticky service explicitly; failures (e.g. background-start limits) are logged. */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, KeepAliveStickyService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveStickyService: Failed to start service")
            }
        }

        /** 停止粘性服务；未启动时亦安全 / Stops the sticky service; safe when not running. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KeepAliveStickyService::class.java))
            }.onFailure {
                Timber.w(it, "KeepAliveStickyService: Failed to stop service")
            }
        }
    }
}
