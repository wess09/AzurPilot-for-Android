package com.azurpilot.ghio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.azurpilot.ghio.MainActivity
import com.azurpilot.ghio.R
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * 虚拟屏存续期间 + proot 会话（AzurPilot 内置环境）活跃期间把 app 进程钉成前台
 *
 * 不是为了显示状态——是为了活着：app 进程一死，特权进程的看门狗随即自杀并释放虚拟屏，
 * proot 长跑会话也随之失去父进程（wrapper 靠 stdin 管道破裂自尽，但 WebView/控制面已没人持有），
 * 表现成「环境跑一半自己没了」。实测 MIUI 的 ProcessManager 会对 Adj=905 的空进程
 * 直接 force-stop（`SwipeUpClean: force-stop <pkg> Adj=905`），前台服务是唯一挡得住的一层
 *
 * 观察源是 [HostState.snapshot] 与 [ProotHost.state]：虚拟屏在（displayId 有效）或
 * proot 会话活跃（准备/更新/启动/运行）就常驻，两边都撤了自己走。
 * 桥可达性只上文案，不作为退出判据——桥短暂抖动不该把保活撤掉
 *
 * 只提供 [start] 不提供外部 stop：`startForegroundService` 之后若 `stopService` 抢在
 * onCreate 之前到达，系统会因 startForeground 未调用直接杀进程。终态退出由本服务自己
 * 观察两份状态完成
 *
 * Pins the app process to the foreground while the virtual display lives and
 * while the proot session (the bundled AzurPilot environment) is active.
 *
 * Not about showing status — about staying alive: when the app process dies,
 * the privileged process's watchdog kills itself and releases the display, and
 * the long-running proot session loses its parent as well (the wrapper exits
 * on a broken stdin pipe, but nobody holds the WebView/control surfaces
 * anymore) — the user sees "the environment vanished halfway through". In
 * practice MIUI's ProcessManager force-stops empty processes at Adj=905
 * outright (`SwipeUpClean: force-stop <pkg> Adj=905`); a foreground service is
 * the only layer that holds.
 *
 * The observed sources are [HostState.snapshot] and [ProotHost.state]: pinned
 * while the display is up (a valid displayId) or the proot session is active
 * (preparing/updating/starting/running), self-retiring when both are gone.
 * Bridge reachability only feeds the text, never the exit criterion — a brief
 * bridge flap must not tear down the keep-alive.
 *
 * Only [start] exists, no external stop: if `stopService` arrives before
 * onCreate after a `startForegroundService`, the system kills the process for
 * the un-called startForeground. The terminal exit is handled by this service
 * watching the two states itself.
 */
class RunForegroundService : Service() {

    private val hostState: HostState by inject()
    private val prootHost: ProotHost by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 先 startForeground 再判终态，顺序不能反；终态即自退，否则起观察协程
     *
     * Calls startForeground before the terminal check — the order must not
     * flip; retires at once on the terminal state, otherwise starts observing.
     */
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 必须先 startForeground 再判终态：慢一步就是 ForegroundServiceDidNotStartInTimeException
        val initial = hostState.snapshot.value to prootHost.state.value
        startAsForeground(buildNotification(initial.first, initial.second))
        if (isTerminal(initial.first, initial.second)) {
            stopNow()
            return
        }
        observe()
    }

    /**
     * 系统可能只走 onStartCommand（不经 onCreate 的重启路径）：FGS 提升要在这里再保一次
     *
     * The system may deliver only onStartCommand (the recreate path without
     * onCreate): the FGS promotion is re-secured here.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统可能只走 onStartCommand；FGS 提升要在这里再保一次
        val snapshot = hostState.snapshot.value
        val proot = prootHost.state.value
        startAsForeground(buildNotification(snapshot, proot))
        if (isTerminal(snapshot, proot)) {
            stopNow()
        } else {
            observe()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observeJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /** 幂等起观察协程 / Starts the observer coroutine idempotently. */
    private fun observe() {
        if (observeJob?.isActive == true) return
        observeJob = serviceScope.launch { observeSnapshot() }
    }

    /** 合流两份状态：终态即退，否则刷新通知文案 / Combines the two states: exits on the terminal one, else refreshes the notification text. */
    private suspend fun observeSnapshot() {
        combine(hostState.snapshot, prootHost.state, ::Pair).collectLatest { (snapshot, proot) ->
            if (isTerminal(snapshot, proot)) {
                stopNow()
                return@collectLatest
            }
            notify(buildNotification(snapshot, proot))
        }
    }

    /**
     * 终态：虚拟屏撤了且 proot 会话也不在活跃阶段，保活没有存在意义
     *
     * Terminal: the virtual display is gone and the proot session is in no
     * active phase — the keep-alive has nothing left to do.
     */
    private fun isTerminal(snapshot: HostSnapshot, proot: ProotSnapshot): Boolean =
        snapshot.vdDisplayId == DefaultDisplayConfig.DISPLAY_NONE && !proot.sessionActive

    /** 撤前台并自停 / Drops the foreground and stops itself. */
    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 建通知渠道（幂等）；重要性建成就改不了，沿用 run_execution
     *
     * Creates the notification channel (idempotent); a channel's importance is
     * immutable once set, so run_execution is reused.
     */
    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_run),
            // LOW：常驻不该出声。MIN 进不了状态栏，部分 ROM 还当成前台服务不成立
            // 重要性建成就改不了，沿用 run_execution
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_run_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 按系统版本带 SPECIAL_USE 类型起前台（API 34+ 必须指定类型）
     *
     * Promotes to foreground with the SPECIAL_USE type on API 34+, where a
     * type is mandatory.
     */
    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 组通知文案，两级：屏在→按桥可达性；只剩 proot 会话→对应文案
     *
     * Builds the notification text, two levels: display present → by bridge
     * reachability; proot session only → its own text.
     */
    private fun buildNotification(snapshot: HostSnapshot, proot: ProotSnapshot): Notification {
        val content = if (snapshot.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE) {
            val contentRes = if (snapshot.bridgeReachable) {
                R.string.notification_host_content_ok
            } else {
                R.string.notification_host_content_degraded
            }
            getString(contentRes, snapshot.vdDisplayId)
        } else {
            // 只剩 proot 会话在岗（内置 AzurPilot 环境跑着但还没建屏）
            getString(R.string.notification_host_content_proot)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_host_title))
            .setContentText(content)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setRequestPromotedOngoing(notificationManager.canRequestPromotedOngoing())
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    /** 通知权限被拒时 notify/cancel 会抛 SecurityException，不能让它掀翻 FGS 主线程 */
    private fun notify(notification: Notification) {
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Timber.w(it, "Failed to update host notification") }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    /** 回主界面的点击意图 / The tap intent back into MainActivity. */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** 渠道 id；重要性建成就改不了 / The channel id; its importance is immutable once created. */
        private const val CHANNEL_ID = "run_execution"
        private const val NOTIFICATION_ID = 1001

        /**
         * 拉起保活服务；失败只记日志（系统限制启动时不至于崩调用方）
         *
         * Spawns the keep-alive service; failures only log, so a system-side
         * restriction never crashes the caller.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, RunForegroundService::class.java))
            }.onFailure { Timber.w(it, "Failed to start foreground service") }
        }
    }
}

/**
 * 16 以下没有实时动态开关，请求会被忽略
 *
 * Below API 36 there is no live promoted-ongoing toggle and the request is
 * simply ignored.
 */
private fun NotificationManager.canRequestPromotedOngoing(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return true
    return canPostPromotedNotifications()
}
