package com.azurpilot.ghio.service

import android.content.Context
import android.os.Build
import android.view.Surface
import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.privileged.PrivilegedServicePort
import com.azurpilot.ghio.privileged.PrivilegedServiceState
import com.azurpilot.ghio.privileged.PermissionGateway
import com.azurpilot.ghio.privileged.ServiceBindResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外壳真实状态的唯一来源：特权进程连接态 + 桥可达性 + 虚拟屏 displayId
 *
 * 桥活在特权进程里（BridgeServer 随 RemoteServiceImpl 自起），app 侧够不到它的
 * isRunning()，可达性只能自己周期 ping——协议与 m0 桥同款（行分隔 JSON），
 * 与 AzurPilot 客户端打的是同一个端口
 *
 * displayId 只是「最近一次 startVirtualDisplay 的返回值」：特权进程一断，
 * 屏与桥随之作废，快照整份清零，等下次连接/探测重建
 *
 * The single source of the shell's real state: privileged-process connectivity,
 * bridge reachability and the virtual display's displayId.
 *
 * The bridge lives inside the privileged process (BridgeServer starts with
 * RemoteServiceImpl), so the app side cannot reach its isRunning() —
 * reachability can only come from periodic pings. The protocol matches the m0
 * bridge (newline-delimited JSON), on the same port the AzurPilot client hits.
 *
 * displayId is merely "the most recent startVirtualDisplay return value": once
 * the privileged process drops, display and bridge are void, the snapshot
 * zeroes wholesale, and the next connect/probe rebuilds it.
 */
class HostState(
    private val context: Context,
    private val servicePort: PrivilegedServicePort,
    private val permissionGateway: PermissionGateway,
    private val scope: CoroutineScope,
    private val appSettings: AppSettingsManager,
) {

    private val _snapshot = MutableStateFlow(HostSnapshot())

    /** 外壳状态快照 / The shell's state snapshot. */
    val snapshot: StateFlow<HostSnapshot> = _snapshot.asStateFlow()

    /** 周期与按需探测共用一把锁，防并发探测挤在同一端口上 */
    private val probeMutex = Mutex()
    private val envMutex = Mutex()
    // Prevent repeated backend churn when a vendor ROM cannot create a display
    // even after a Root retry. The user can still select a backend manually.
    private var rootFallbackAttempted = false
    private val pingSeq = AtomicInteger(0)

    /**
     * 订阅特权连接态并起周期桥探测；桥态每 [BRIDGE_PROBE_INTERVAL_MS] 一拍
     *
     * Subscribes to privileged connection state and starts periodic bridge
     * probing on a [BRIDGE_PROBE_INTERVAL_MS] cadence.
     */
    fun start() {
        scope.launch {
            servicePort.serviceState.collect { state ->
                _snapshot.update {
                    if (state == PrivilegedServiceState.Connected) {
                        it.copy(privilegedConnected = true)
                    } else {
                        HostSnapshot(environmentIssue = it.environmentIssue)
                    }
                }
            }
        }
        scope.launch(AppDispatchers.IO) {
            while (true) {
                probeBridgeNow()
                delay(BRIDGE_PROBE_INTERVAL_MS)
            }
        }
    }

    /**
     * 立即 ping 一次桥并把结果落快照
     *
     * Probes the bridge once right away and lands the result in the snapshot.
     *
     * @return 桥是否可达 / whether the bridge is reachable
     */
    suspend fun probeBridgeNow(): Boolean = probeMutex.withLock {
        val reachable = runCatching { pingBridge() }
            .onFailure { Timber.d("bridge probe failed: %s", it.message) }
            .getOrDefault(false)
        _snapshot.update { it.copy(bridgeReachable = reachable) }
        reachable
    }

    /**
     * 「开始」链路：确保特权连接就绪 → setup() → startVirtualDisplay()
     * 桥随特权进程自起，无需显式操作
     *
     * 幂等：屏已存在直接成功；断线重连后（快照已清零）可再次触发
     *
     * The "start" chain: ensure the privileged connection, setup(), then
     * startVirtualDisplay(). The bridge comes up with the privileged process —
     * no explicit step needed.
     *
     * Idempotent: an existing display succeeds at once; after a reconnect (the
     * snapshot zeroed) it can fire again.
     *
     * @return 屏是否建起（或本来就在）/ whether the display came up (or was
     *   already there)
     */
    suspend fun ensureEnvironmentStarted(): Boolean {
        envMutex.withLock {
            if (_snapshot.value.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE) return@withLock
            val service = servicePort.serviceOrNull() ?: run {
                // 用户可能关闭过首启引导。启动环境必须走统一权限入口，未授权时
                // 直接弹出 Shizuku 授权，不能只 bind 后静默落成 Error。
                when (val result = permissionGateway.bindService()) {
                    ServiceBindResult.AlreadyConnected,
                    ServiceBindResult.Started -> Unit
                    else -> {
                        Timber.w("ensureEnvironmentStarted: privileged bind rejected: %s", result)
                        return@withLock
                    }
                }
                runCatching {
                    withTimeout(CONNECT_WAIT_MS) {
                        servicePort.serviceState.first { it != PrivilegedServiceState.Connecting }
                    }
                }
                servicePort.serviceOrNull() ?: run {
                    Timber.w("ensureEnvironmentStarted: privileged not connected")
                    return@withLock
                }
            }
            runCatching { service.setup(null, null, BuildConfig.DEBUG) }
                .onFailure { Timber.w(it, "setup failed") }
            appSettings.loaded.first { it }
            var displayId = startDisplay(service)
            // Cloud ROMs sometimes run the Shizuku remote service as uid=0.
            // DisplayManagerService rejects com.android.shell for that UID.
            // Retry using the already-authorized Root launcher, which runs as
            // shell (uid=2000) on affected Android 13 devices (issue #11).
            if (displayId == DefaultDisplayConfig.DISPLAY_NONE &&
                !rootFallbackAttempted &&
                servicePort.currentBackend == RemoteBackend.SHIZUKU &&
                runCatching { service.processUid() }.getOrNull() == 0
            ) {
                rootFallbackAttempted = true
                displayId = retryRootBackendForRootShizuku()
            }
            if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
                Timber.w("startVirtualDisplay returned DISPLAY_NONE")
                return@withLock
            }
            Timber.i("Environment started, displayId=%s", displayId)
            _snapshot.update { it.copy(vdDisplayId = displayId, environmentIssue = null) }
            RunForegroundService.start(context)
        }
        // 建完屏（或本来就有屏）顺手刷一次桥态，UI 不必干等下个探测周期
        probeBridgeNow()
        return _snapshot.value.vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE
    }

    private fun startDisplay(service: RemoteService): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.setVirtualDisplayRefreshRate(appSettings.virtualDisplayRefreshRate.value)
        }
        service.startVirtualDisplay()
    }.getOrElse {
        Timber.e(it, "startVirtualDisplay failed")
        DefaultDisplayConfig.DISPLAY_NONE
    }

    /**
     * Use the Root backend only if authorization already exists. Never request
     * su access unexpectedly or silently switch on unrelated display errors.
     * A failed retry restores the user's original Shizuku selection.
     */
    private suspend fun retryRootBackendForRootShizuku(): Int {
        permissionGateway.refresh()
        if (!permissionGateway.state.value.rootGranted) {
            _snapshot.update { it.copy(environmentIssue = HostEnvironmentIssue.ROOT_BACKEND_REQUIRED) }
            Timber.e(
                "Virtual display unavailable: Shizuku service runs as root (uid=0), " +
                    "but com.android.shell belongs to uid=2000. " +
                    "Authorize the Root backend in Settings > Run & Security to retry."
            )
            return DefaultDisplayConfig.DISPLAY_NONE
        }
        Timber.w("Shizuku service uid=0 cannot own com.android.shell; retrying Root backend")
        var displayId = DefaultDisplayConfig.DISPLAY_NONE
        try {
            permissionGateway.setBackend(RemoteBackend.ROOT)
            appSettings.startupBackend.first { it == RemoteBackend.ROOT }
            // Allow PermissionManager's backend observer to process the change
            // before the explicit bind, then replace any stale Shizuku service.
            yield()
            servicePort.unbind()
            permissionGateway.refresh()
            when (val result = permissionGateway.bindService()) {
                ServiceBindResult.Started, ServiceBindResult.AlreadyConnected -> {
                    val rootService = withTimeout(CONNECT_WAIT_MS) {
                        servicePort.serviceState.first {
                            it == PrivilegedServiceState.Connected &&
                                servicePort.currentBackend == RemoteBackend.ROOT
                        }
                        requireNotNull(servicePort.serviceOrNull()) {
                            "Root backend reported connected without a service"
                        }
                    }
                    val uid = rootService.processUid()
                    if (uid == android.os.Process.SHELL_UID) {
                        runCatching { rootService.setup(null, null, BuildConfig.DEBUG) }
                            .onFailure { Timber.w(it, "Root backend setup failed") }
                        displayId = startDisplay(rootService)
                    } else {
                        Timber.e("Root backend uid=%s is not shell; cannot safely retry virtual display", uid)
                    }
                }
                else -> Timber.e("Root fallback bind rejected: %s", result)
            }
        } catch (e: Exception) {
            Timber.e(e, "Root backend virtual display fallback failed")
        }
        if (displayId == DefaultDisplayConfig.DISPLAY_NONE) {
            _snapshot.update { it.copy(environmentIssue = HostEnvironmentIssue.ROOT_FALLBACK_FAILED) }
            Timber.w("Root fallback failed; restoring Shizuku backend")
            runCatching { permissionGateway.setBackend(RemoteBackend.SHIZUKU) }
                .onFailure { Timber.e(it, "Could not restore Shizuku backend") }
        } else {
            Timber.i("Root backend recovered virtual display, displayId=%s", displayId)
        }
        return displayId
    }

    /**
     * 「停止」链路：只停虚拟屏；特权进程与桥留着，下次开始不用重连
     *
     * The "stop" chain: only the virtual display stops; the privileged process
     * and bridge stay, so the next start needs no reconnect.
     */
    suspend fun stopEnvironment() {
        envMutex.withLock {
            servicePort.serviceOrNull()?.let { service ->
                runCatching { service.stopVirtualDisplay() }
                    .onFailure { Timber.w(it, "stopVirtualDisplay failed") }
            }
            _snapshot.update { it.copy(vdDisplayId = DefaultDisplayConfig.DISPLAY_NONE) }
        }
    }

    /**
     * 预览面挂载/摘除：虚拟屏页 SurfaceView 的 Surface 交给特权进程渲染画面
     * （native bridge_preview 通道，零拷贝）。特权断线时静默失败——
     * 页面切走时靠 DisposableEffect 补一次摘面
     *
     * Mounts/unmounts the preview surface: the virtual-display page's
     * SurfaceView hands its Surface to the privileged process for rendering
     * (the native bridge_preview channel, zero-copy). Silent failure when the
     * privileged process is gone — the page's DisposableEffect re-detaches as
     * a safety net.
     */
    fun attachPreviewSurface(surface: Surface) {
        runCatching { servicePort.serviceOrNull()?.setMonitorSurface(surface) }
            .onFailure { Timber.w(it, "attachPreviewSurface failed") }
    }

    /** 摘掉预览面 / Detaches the preview surface. */
    fun detachPreviewSurface() {
        runCatching { servicePort.serviceOrNull()?.setMonitorSurface(null) }
            .onFailure { Timber.w(it, "detachPreviewSurface failed") }
    }

    /**
     * 虚拟屏页上的手动操作：坐标由 UI 换算到虚拟屏坐标系后传入，
     * 直通 AIDL 同名方法（oneway，内部带虚拟屏 displayId 注入，见 RemoteServiceImpl）。
     * 高频（一次滑动几十条），失败静默——特权断线时快照清零，注入也随之失去目标
     *
     * Manual actions on the virtual-display page: the UI converts coordinates
     * into the virtual display's space, then passes them straight through to
     * the same-named AIDL methods (oneway, injecting with the display's
     * displayId internally — see RemoteServiceImpl).
     *
     * High frequency (dozens per swipe), silent on failure — when the
     * privileged process drops, the snapshot zeroes and injection loses its
     * target with it.
     */
    fun touchDown(x: Int, y: Int) {
        runCatching { servicePort.serviceOrNull()?.touchDown(x, y) }
            .onFailure { Timber.w(it, "touchDown failed") }
    }

    fun touchMove(x: Int, y: Int) {
        runCatching { servicePort.serviceOrNull()?.touchMove(x, y) }
            .onFailure { Timber.w(it, "touchMove failed") }
    }

    fun touchUp(x: Int, y: Int) {
        runCatching { servicePort.serviceOrNull()?.touchUp(x, y) }
            .onFailure { Timber.w(it, "touchUp failed") }
    }

    /**
     * m0 桥协议最小客户端：一行请求一行响应，判 "pong":true
     *
     * A minimal m0-bridge protocol client: one request line, one response
     * line; success is a `"pong":true`.
     */
    private fun pingBridge(): Boolean {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress(InetAddress.getByName(BRIDGE_HOST), BRIDGE_PORT),
                BRIDGE_CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = BRIDGE_READ_TIMEOUT_MS
            val request = """{"id":${pingSeq.incrementAndGet()},"method":"ping"}"""
            socket.getOutputStream().write((request + "\n").toByteArray(Charsets.UTF_8))
            val line = readLine(BufferedInputStream(socket.getInputStream()))
            return JSONObject(line).optBoolean("pong", false)
        }
    }

    /**
     * 读一行响应；行超 [MAX_REPLY_LINE] 即断，防对端异常时内存被拖爆
     *
     * Reads one reply line; anything past [MAX_REPLY_LINE] aborts, so a broken
     * peer cannot blow up memory.
     *
     * @throws IllegalStateException 连接被对端关闭或行超长 / when the peer
     *   closes the connection or the line overruns the cap
     */
    private fun readLine(input: BufferedInputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            check(b >= 0) { "bridge closed connection" }
            if (b == '\n'.code) break
            line.write(b)
            check(line.size() <= MAX_REPLY_LINE) { "reply line too long" }
        }
        return line.toString(Charsets.UTF_8.name())
    }

    private companion object {
        /** 桥只听回环 / The bridge listens on loopback only. */
        const val BRIDGE_HOST = "127.0.0.1"

        /** 桥端口：与 AzurPilot 客户端打的同一个 / The bridge port — the same one the AzurPilot client hits. */
        const val BRIDGE_PORT = 22301

        /** 周期探测间隔 / The periodic probe interval. */
        const val BRIDGE_PROBE_INTERVAL_MS = 4_000L
        const val BRIDGE_CONNECT_TIMEOUT_MS = 1_500
        const val BRIDGE_READ_TIMEOUT_MS = 2_000

        /** 等特权服务连接完成的上限 / The ceiling for waiting on the privileged connection. */
        const val CONNECT_WAIT_MS = 12_000L

        /** 单行响应上限，超过即视为对端坏了 / The reply line cap; beyond it the peer is broken. */
        const val MAX_REPLY_LINE = 4 * 1024
    }
}

/**
 * 外壳状态的一份不可变快照
 *
 * One immutable snapshot of the shell's state.
 *
 * @property privilegedConnected 特权服务是否已连 / whether the privileged
 *   service is connected
 * @property bridgeReachable 桥最近一次探测是否可达 / whether the bridge answered
 *   the last probe
 * @property vdDisplayId 虚拟屏 displayId；无屏为 [DefaultDisplayConfig.DISPLAY_NONE]
 *   / the virtual display's displayId; [DefaultDisplayConfig.DISPLAY_NONE] when
 *   there is none
 */
data class HostSnapshot(
    val privilegedConnected: Boolean = false,
    val bridgeReachable: Boolean = false,
    val vdDisplayId: Int = DefaultDisplayConfig.DISPLAY_NONE,
    val environmentIssue: HostEnvironmentIssue? = null,
) {
    /**
     * 环境整体活着：屏在且桥通；悬浮球与 FGS 的「活着」判据
     *
     * The environment as a whole is alive: display present and bridge
     * reachable — the "alive" criterion for the floating ball and the FGS.
     */
    val environmentUp: Boolean
        get() = bridgeReachable && vdDisplayId != DefaultDisplayConfig.DISPLAY_NONE
}

/** Actionable virtual-display failure exposed to the status panel. */
enum class HostEnvironmentIssue {
    ROOT_BACKEND_REQUIRED,
    ROOT_FALLBACK_FAILED,
}
