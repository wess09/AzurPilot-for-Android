package com.azurpilot.ghio.privileged

import android.content.Context
import android.os.Build
import com.azurpilot.adb.AdbClient
import com.azurpilot.adb.AdbKey
import com.azurpilot.adb.AdbMdns
import com.azurpilot.adb.AdbPairingClient
import com.azurpilot.adb.PreferenceAdbKeyStore
import com.azurpilot.ghio.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File

/**
 * 管理内置 ADB 身份、无线配对和本次开机的离线连接；进程级单例。
 *
 * 网络和密钥操作在 IO 线程串行执行。只有认证并确认 shell 身份后才公布授权状态，
 * 普通 App 身份不被当作已授权。首次配对和 USB 调试确认仍由 Android 系统处理。
 *
 * Manages the embedded ADB identity, wireless pairing, and offline connection for this boot.
 *
 * This process singleton serializes network and key operations on IO threads. It publishes a
 * grant only after authenticating and verifying the shell identity. Android still handles
 * initial pairing and USB debugging confirmation.
 */
object EmbeddedAdbManager {
    /**
     * 启动界面的操作结果。
     *
     * Operation result displayed by the activation screen.
     */
    enum class Status {
        /**
         * 尚未启动。
         *
         * Not started.
         */
        Idle,
        /**
         * 正在连接或配对。
         *
         * Connecting or pairing.
         */
        Working,
        /**
         * 已认证本机 shell。
         *
         * Local shell authenticated.
         */
        Ready,
        /**
         * 连接失败，需要重新激活。
         *
         * Connection failed; reactivation required.
         */
        Failed,
    }

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.IO)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(Status.Idle)
    /**
     * 供主线程 UI 观察的结果。
     *
     * Result observable by main-thread UI.
     */
    val status = _status.asStateFlow()
    private val connectPort = MutableStateFlow(-1)
    private lateinit var context: Context
    private var discovery: AdbMdns? = null
    private val identity: AdbKey by lazy {
        AdbKey(
            PreferenceAdbKeyStore(context.getSharedPreferences("embedded_adb", Context.MODE_PRIVATE)),
            "azurpilot",
        )
    }
    @Volatile private var activePort = -1

    /**
     * 最近一次认证成功的本机端口；只读快照，不执行网络操作。
     *
     * Last authenticated local port; no network IO.
     */
    val isReady: Boolean get() = activePort > 0
    private val enabledFile: File get() = File(context.noBackupFilesDir, "embedded_adb_enabled")

    /**
     * 在主线程装配发现器；仅对用户启用过的内置路径尝试静默重连。
     *
     * Initializes discovery on the main thread and silently reconnects only a previously enabled
     * embedded path.
     */
    fun initialize(appContext: Context, reconnect: Boolean) {
        if (::context.isInitialized) return
        context = appContext.applicationContext
        if (Build.VERSION.SDK_INT >= 30) {
            discovery = AdbMdns(context, AdbMdns.TLS_CONNECT) { connectPort.value = it }
        }
        if (reconnect) scope.launch {
            if (enabledFile.exists()) activate(interactive = false)
        }
    }

    /**
     * 在主线程开始发现本机 TLS 端口。
     *
     * Starts local TLS discovery on the main thread.
     */
    fun startDiscovery() {
        runCatching { discovery?.start() }.onFailure { Timber.w(it, "Local ADB discovery failed") }
    }

    /**
     * 在主线程停止发现，避免后台持续扫描。
     *
     * Stops discovery on the main thread to avoid background scans.
     */
    fun stopDiscovery() {
        discovery?.stop()
    }

    /**
     * 在 IO 线程完成配对，并立即启动内置连接；返回值不包含额外 App 授权步骤。
     *
     * Pairs on an IO thread and activates the embedded connection immediately, without an
     * additional app grant step.
     */
    suspend fun pair(port: Int, code: String): Boolean = operation {
        check(Build.VERSION.SDK_INT >= 30)
        AdbPairingClient("127.0.0.1", port, code, identity).use { check(it.start()) }
        activateInternal(interactive = true, manualPort = null)
    }

    /**
     * 优先离线连接 5555，再尝试无线调试端口；交互启动可触发系统 USB 调试确认。
     *
     * Prefers offline port 5555 before wireless debugging. Interactive activation may trigger
     * the system USB debugging confirmation. The optional port is the connection port, not the
     * pairing port.
     */
    suspend fun activate(interactive: Boolean = true, manualPort: Int? = null): Boolean = operation {
        activateInternal(interactive, manualPort)
    }

    /**
     * 在 IO 线程创建已经认证的本机连接；调用者用 use 负责关闭。
     *
     * Opens an authenticated local connection on IO; callers close it with use.
     */
    fun openClient(): AdbClient {
        check(isReady) { "Embedded ADB is not active" }
        return client(activePort, 5_000, false).also {
            try { it.connect() } catch (error: Throwable) { it.close(); throw error }
        }
    }

    /**
     * 启动器发现连接失效时清除授权快照。
     *
     * Clears the grant snapshot when the launcher finds a stale connection.
     */
    fun invalidate() {
        activePort = -1
        _status.value = Status.Failed
        ShizukuManager.refreshEmbeddedState()
    }

    private suspend fun operation(block: suspend () -> Unit): Boolean = withContext(AppDispatchers.IO) {
        mutex.withLock {
            _status.value = Status.Working
            try {
                block()
                _status.value = Status.Ready
                true
            } catch (error: CancellationException) {
                _status.value = if (isReady) Status.Ready else Status.Idle
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Embedded ADB activation failed")
                invalidate()
                false
            } catch (error: LinkageError) {
                Timber.w(error, "Embedded pairing library unavailable")
                invalidate()
                false
            }
        }
    }

    private suspend fun activateInternal(interactive: Boolean, manualPort: Int?) {
        val offline = runCatching {
            authenticate(5555, if (interactive) 30_000 else 1_500, interactive)
        }.isSuccess
        if (!offline) {
            val port = manualPort ?: withContext(Dispatchers.Main) {
                startDiscovery()
                try {
                    withTimeoutOrNull(8_000) { connectPort.first { it > 0 } }
                } finally {
                    stopDiscovery()
                }
            } ?: error("Enable wireless debugging and pair this app first")
            require(port in 1..65535)
            authenticate(port, 5_000, false)
            // adbd 自带服务可修改端口；shell setprop 会被新系统 SELinux 拒绝。
            client(port, 5_000, false).use {
                it.connect()
                runCatching { it.tcpipCommand(5555) }
                    .onFailure { error -> Timber.d(error, "adbd restarted during tcpip request") }
            }
            delay(1_000)
            // 首次 RSA 回环授权需要系统确认；静默重连不得反复弹出确认框。
            authenticate(5555, if (interactive) 30_000 else 3_000, interactive)
        }
        enabledFile.writeText("1")
        activePort = 5555
        ShizukuManager.refreshEmbeddedState()
        withContext(Dispatchers.Main) {
            if (RemoteAccessCoordinator.configuredBackend() == com.azurpilot.ghio.domain.RemoteBackend.SHIZUKU) {
                RemoteServiceManager.bind()
            }
        }
    }

    private fun authenticate(port: Int, timeout: Int, allowPublicKey: Boolean) {
        client(port, timeout, allowPublicKey).use {
            it.connect()
            val output = StringBuilder()
            it.shellCommand("id -u") { bytes ->
                check(output.length + bytes.size <= 128) { "Unexpected ADB identity output" }
                output.append(bytes.toString(Charsets.UTF_8))
            }
            check(output.toString().trim() == "2000") { "ADB must run as shell (uid=2000)" }
        }
    }

    private fun client(port: Int, timeout: Int, allowPublicKey: Boolean) =
        AdbClient("127.0.0.1", port, identity, timeout, allowPublicKey)
}
