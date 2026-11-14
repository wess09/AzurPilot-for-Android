package com.azurpilot.ghio.privileged

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.azurpilot.adb.AdbMdns
import com.azurpilot.ghio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 在系统配对码页面显示通知输入框，避免返回 App 导致配对码失效。
 *
 * 主线程拥有发现器与通知，配对任务委托 IO 管理器；三分钟无操作或用户取消后退出。
 * 服务不导出，只有显式通知 PendingIntent 能提交配对码。
 *
 * Shows a notification reply while the system pairing-code dialog remains open. The main
 * thread owns discovery and notifications; the IO manager handles pairing. Stops after three
 * idle minutes or cancellation. This nonexported service accepts codes through explicit
 * notification PendingIntents.
 */
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discovery: AdbMdns? = null
    private var port = -1
    private var pairing = false

    /**
     * 创建无声音的高优先级配对通知频道。
     *
     * Creates a silent, high-priority pairing notification channel.
     */
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.adb_pair_notification), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            },
        )
        scope.launch { delay(180_000); stopSelf() }
    }

    /**
     * 接收开始、取消和输入动作；不重放带配对码的 Intent。
     *
     * Handles start, cancellation, and replies without redelivering code-bearing Intents.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 30 || intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION, notification(R.string.adb_pair_searching))
        if (intent?.action == REPLY && !pairing) {
            val replyPort = intent.getIntExtra(PORT, -1)
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString().orEmpty()
            if (replyPort in 1..65535 && code.matches(Regex("[0-9]{6}"))) {
                pairing = true
                discovery?.stop()
                scope.launch {
                    val success = EmbeddedAdbManager.pair(replyPort, code)
                    if (success) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        getSystemService(NotificationManager::class.java).notify(
                            RESULT_NOTIFICATION, notification(R.string.adb_ready),
                        )
                        stopSelf()
                    } else {
                        pairing = false
                        port = -1
                        show(R.string.adb_pair_failed)
                        startSearch()
                    }
                }
                show(R.string.adb_working)
            } else {
                show(R.string.adb_pair_failed)
                startSearch()
            }
        } else if (!pairing) startSearch()
        return START_NOT_STICKY
    }

    private fun startSearch() {
        val current = discovery ?: AdbMdns(this, AdbMdns.TLS_PAIRING) { discovered ->
            if (!pairing) {
                port = discovered
                show(if (port > 0) R.string.adb_pair_enter_code else R.string.adb_pair_searching)
            }
        }.also { discovery = it }
        current.start()
    }

    /**
     * 关闭发现器与配对任务。
     *
     * Stops discovery and pairing tasks.
     */
    override fun onDestroy() {
        discovery?.stop()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 不提供绑定接口。
     *
     * Provides no binding interface.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun show(message: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(message))
    }

    private fun notification(message: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, AdbActivationActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.adb_title))
            .setContentText(getString(message))
            .setStyle(Notification.BigTextStyle().bigText(getString(message)))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.dialog_cancel), cancel).build())
        if (port > 0 && !pairing) {
            val reply = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(REPLY).putExtra(PORT, port), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, getString(R.string.adb_pair_code), reply)
                .addRemoteInput(RemoteInput.Builder(CODE).setLabel(getString(R.string.adb_pair_code)).build()).build())
        }
        return builder.build()
    }

    private companion object {
        const val CHANNEL = "embedded_adb_pairing"
        const val NOTIFICATION = 25549
        const val RESULT_NOTIFICATION = 25550
        const val REPLY = "reply"
        const val STOP = "stop"
        const val CODE = "pairing_code"
        const val PORT = "pairing_port"
    }
}
