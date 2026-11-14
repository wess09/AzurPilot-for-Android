package com.azurpilot.ghio.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.azurpilot.ghio.settings.AppSettingsGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 后台活动信号与自检的协调器。
 *
 * 本类组合音频、悬浮窗、WakeLock、服务、闹钟、作业与事件回调，以便在组件仍可用时重建它们。
 * 每一项都是尽力而为的活动信号或重试触发器，受 Android 版本、权限、OEM 策略和资源压力约束；
 * 它们不保证进程重启、后台存活或网络访问。伴侣设备服务尤其依赖既有的系统关联，而当前工程不创建关联。
 *
 * [init] 写入 [getInstance]，使没有 DI 路径的接收器和服务能够发起 [onKeepAlivePing]。 [start]
 * 在 [appScope] 收集开关；启用后，心跳、闹钟和系统事件可再次调用自检入口。
 *
 * Coordinator for background-activity signals and self-checks.
 *
 * This class combines audio, an overlay, WakeLock, services, alarms, jobs, and event callbacks so it
 * can recreate components while they remain available. Each is a best-effort activity signal or retry
 * trigger constrained by Android version, permissions, OEM policy, and resource pressure; none
 * guarantees a process restart, background lifetime, or network access. The companion service in
 * particular requires an existing system association, which this repository does not create.
 *
 * [init] writes [getInstance] so receivers and services without a DI path can request
 * [onKeepAlivePing]. [start] collects the toggle on [appScope]; once enabled, heartbeats, alarms,
 * and system events may call the self-check entry point again.
 */
class KeepAliveManager(
    private val context: Context,
    private val appSettings: AppSettingsGateway,
    private val appScope: CoroutineScope,
) {

    private val audioPlayer = KeepAliveAudioPlayer()
    private val pixelOverlay = KeepAlivePixelOverlay(context)
    private val wakeLock = KeepAliveWakeLock(context)
    private val alarmScheduler = KeepAliveAlarmScheduler(context)
    private val jobScheduler = KeepAliveJobScheduler(context)

    /** 静音音频是否在播 / Whether the silent audio is playing. */
    val isAudioPlaying: StateFlow<Boolean> = audioPlayer.isPlaying

    /** 1px 浮窗是否已挂载 / Whether the 1px overlay is attached. */
    val isPixelOverlayAttached: StateFlow<Boolean> = pixelOverlay.isAttached

    /** WakeLock 是否持有 / Whether the wake lock is held. */
    val isWakeLockHeld: StateFlow<Boolean> = wakeLock.isHeld

    private val _isAccessibilityConnected = MutableStateFlow(false)

    /** 无障碍守护是否已连接系统 / Whether the accessibility daemon is connected to the system. */
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private var heartbeatJob: Job? = null
    private var lastEventTime = 0L

    // 动态注册的屏幕亮灭与解锁广播接收器；这些动作无法静态注册，只能在运行期注册
    private var dynamicReceiver: BroadcastReceiver? = null

    companion object {
        @Volatile
        private var instance: KeepAliveManager? = null

        /**
         * 进程级单例取用；管理器构造前为 null
         *
         * Returns the process-level singleton; null before the manager is constructed.
         */
        fun getInstance(): KeepAliveManager? = instance
    }

    init {
        // 写入进程级单例：接收器与服务没有 DI 注入路径，只能经 getInstance 取回
        instance = this
    }

    /**
     * 启动总控：在 [appScope] 上监听保活开关，切换时全量启用 / 停用所有子系统
     *
     * Starts the orchestrator: observes the keep-alive toggle on [appScope] and
     * enables / disables all subsystems wholesale on change.
     */
    fun start() {
        appScope.launch {
            appSettings.keepAliveEnabled
                .collect { enabled ->
                    Timber.d("KeepAliveManager: keepAliveEnabled changed to $enabled")
                    if (enabled) {
                        enableAll()
                    } else {
                        disableAll()
                    }
                }
        }
    }

    /**
     * 全量启用所有子系统；各子系统自身幂等，重复调用安全
     *
     * Enables every subsystem; each is idempotent, so repeat calls are safe.
     */
    private fun enableAll() {
        audioPlayer.start()

        pixelOverlay.attach()

        wakeLock.acquire()

        KeepAliveStickyService.start(context)

        KeepAliveLocalService.start(context)
        KeepAliveDaemonService.start(context)

        jobScheduler.schedule()

        scheduleNextAlarm()

        registerDynamicReceiver()

        startHeartbeat()

        Timber.i("KeepAliveManager: All keep-alive subsystems ENABLED successfully")
    }

    private fun disableAll() {
        stopHeartbeat()
        unregisterDynamicReceiver()
        alarmScheduler.cancel()
        jobScheduler.cancel()
        KeepAliveDaemonService.stop(context)
        KeepAliveLocalService.stop(context)
        KeepAliveStickyService.stop(context)
        wakeLock.release()
        audioPlayer.stop()
        pixelOverlay.detach()
        Timber.i("KeepAliveManager: All keep-alive subsystems DISABLED")
    }

    /**
     * 启动 30 秒周期心跳协程（[Dispatchers.Default]）：仅在保活开关开启时 ping
     *
     * Starts the 30-second heartbeat coroutine ([Dispatchers.Default]); pings only
     * while the keep-alive toggle is on.
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = appScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(30_000L) // 每 30 秒自检一次
                if (appSettings.keepAliveEnabled.value) {
                    onKeepAlivePing()
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * 动态注册屏幕亮灭与解锁广播：这些动作在隐式广播豁免清单里，manifest 静态注册收不到
     *
     * Dynamically registers screen on / off and unlock broadcasts; these actions are on
     * the implicit-broadcast exemption list, so static manifest registration never
     * receives them.
     */
    private fun registerDynamicReceiver() {
        if (dynamicReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent?) {
                    Timber.d("KeepAliveManager: Dynamic broadcast action=${intent?.action}")
                    onKeepAlivePing()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            context.registerReceiver(receiver, filter)
            dynamicReceiver = receiver
            Timber.d("KeepAliveManager: Dynamic screen broadcast receiver registered")
        } catch (e: Exception) {
            Timber.w(e, "KeepAliveManager: Failed to register dynamic broadcast receiver")
        }
    }

    private fun unregisterDynamicReceiver() {
        dynamicReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
            dynamicReceiver = null
            Timber.d("KeepAliveManager: Dynamic screen broadcast receiver unregistered")
        }
    }

    /**
     * 排期下一轮精准闹钟；保活开关关闭时不排期（关闭路径由 disableAll 统一 cancel）
     *
     * Schedules the next exact alarm; skipped while the keep-alive toggle is off
     * (the off path cancels via disableAll).
     *
     * @param delayMs 距触发的延迟 / delay before firing; defaults to
     *   [KeepAliveAlarmScheduler.DEFAULT_INTERVAL_MS]
     */
    fun scheduleNextAlarm(delayMs: Long = KeepAliveAlarmScheduler.DEFAULT_INTERVAL_MS) {
        if (!appSettings.keepAliveEnabled.value) return
        alarmScheduler.scheduleNext(delayMs)
    }

    /**
     * 全面保活自检与自愈入口：由伴侣设备事件、无障碍事件、精准闹钟、系统广播与
     * 30 秒心跳触发
     *
     * 幂等：各子系统先检查后补挂，重复 ping 无副作用；保活开关关闭时直接返回
     *
     * Full keep-alive self-check and self-heal entry point, triggered by companion
     * events, accessibility events, exact alarms, system broadcasts, and the
     * 30-second heartbeat.
     *
     * Idempotent: each subsystem is checked then repaired, so repeated pings are
     * harmless; returns immediately when the keep-alive toggle is off.
     */
    fun onKeepAlivePing() {
        if (!appSettings.keepAliveEnabled.value) return

        audioPlayer.ensurePlaying()

        if (!pixelOverlay.isAttached.value && pixelOverlay.canDrawOverlays()) {
            pixelOverlay.attach()
        }

        if (!wakeLock.isHeld.value) {
            wakeLock.acquire()
        }

        KeepAliveStickyService.start(context)
        KeepAliveLocalService.start(context)
        KeepAliveDaemonService.start(context)
    }

    /**
     * 无障碍守护已连上系统的回调；连接状态置位并触发一次全面自检
     *
     * Callback for the accessibility daemon connecting to the system; sets the
     * connected state and triggers a full self-check.
     */
    fun onAccessibilityConnected() {
        _isAccessibilityConnected.value = true
        Timber.d("KeepAliveManager: Accessibility service connected")
        onKeepAlivePing()
    }

    /**
     * 无障碍守护断开连接的回调；仅置位状态，重拉依赖 system_server 的无障碍自动重连
     *
     * Callback for the accessibility daemon disconnecting; only updates the state,
     * relying on system_server's automatic accessibility reconnect for revival.
     */
    fun onAccessibilityDisconnected() {
        _isAccessibilityConnected.value = false
        Timber.d("KeepAliveManager: Accessibility service disconnected")
    }

    /**
     * 无障碍事件节流入口：30 秒窗口内的多次事件只触发一次全面自检
     *
     * Throttled accessibility-event entry: collapses bursts within a 30-second
     * window into a single full self-check.
     */
    fun onAccessibilityEvent() {
        val now = System.currentTimeMillis()
        if (now - lastEventTime > 30_000L) {
            lastEventTime = now
            onKeepAlivePing()
        }
    }
}
