package com.azurpilot.ghio.privileged
import com.azurpilot.ghio.AppDispatchers

import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.azurpilot.ghio.constant.PrivilegedGrant
import com.azurpilot.ghio.service.AccessibilityHelperService
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.i18n.uiTextFromFramework
import com.azurpilot.ghio.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * 汇总提权状态并代发授权动作；ViewModel 侧唯一的提权入口
 *
 * 代授集合固定为 [PrivilegedGrant.ALL] 全集，不按运行模式挑：用不上的位代授是空操作，
 * 少授反而会在用户切模式时漏掉某一项
 *
 * [RemoteAccessPort] 只汇总两个后端的可用性，不感知持久化；
 * 后端存在哪、什么时候刷新、授权后要不要绑定，都由这一层决定
 *
 * 生命周期：注册为 ProcessLifecycleOwner 的观察者，回前台即 refresh——
 * Shizuku 授权没有回调，只能回来时重读
 *
 * 两个 Port 由构造注入而不是直接点名 object：进程级单例该在 Koin 里装配，
 * 而不是让每个调用点各自去 import 一个全局
 *
 * Aggregates privilege state and forwards grant actions; the ViewModel side's
 * single privilege entry point.
 *
 * The grant set is fixed to the full [PrivilegedGrant.ALL] set, unfiltered by
 * run mode: granting unused bits is a no-op, while granting fewer would drop
 * one when the user switches modes.
 *
 * [RemoteAccessPort] only aggregates both backends' availability and knows
 * nothing of persistence; where the backend choice lives, when to refresh,
 * and whether to bind after a grant are all decided here.
 *
 * Lifecycle: registers as a ProcessLifecycleOwner observer and refreshes on
 * every return to the foreground — Shizuku grants have no callback, so the
 * re-read can only happen on resume.
 *
 * Both ports arrive via constructor injection rather than naming objects
 * directly: process-level singletons belong to Koin wiring, not to every call
 * site importing a global.
 */
class PermissionManager(
    context: Context,
    private val appSettings: AppSettingsManager,
    private val servicePort: PrivilegedServicePort,
    private val accessPort: RemoteAccessPort,
) : PermissionGateway, DefaultLifecycleObserver {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isGranting = MutableStateFlow(false)
    override val isGranting: StateFlow<Boolean> = _isGranting.asStateFlow()

    override val state: StateFlow<RemoteAccessState> = accessPort.state

    override val serviceState: StateFlow<PrivilegedServiceState> = servicePort.serviceState

    /**
     * 看门狗状态：service 连上时 2s 轮询 RemoteService.watchdogState()，断开回 IDLE。
     * flatMapLatest 随连接态切换——断开即停轮询，避免空转
     *
     * 每轮现取服务面而不是扣住连上那一刻的 binder：binder 死了 serviceOrNull 即 null，
     * 轮询当场收摊，不必等连接态那条流转过来
     *
     * Watchdog state: polls RemoteService.watchdogState() every 2 s while the
     * service is connected, back to IDLE when disconnected. flatMapLatest
     * follows the connection state — polling stops the moment it drops, with
     * no idle spinning.
     *
     * Each round grabs the service face fresh instead of holding the binder
     * captured at connect time: once the binder dies serviceOrNull turns null
     * and the polling loop shuts down on the spot, without waiting for the
     * connection-state flow to catch up.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override val watchdogState: StateFlow<WatchdogState> = servicePort.serviceState
        .flatMapLatest { state ->
            if (state != PrivilegedServiceState.Connected) {
                flowOf(WatchdogState.IDLE)
            } else {
                flow {
                    while (true) {
                        val service = servicePort.serviceOrNull() ?: break
                        emit(
                            WatchdogState.fromAidl(
                                runCatching { service.watchdogState() }.getOrDefault(0),
                            ),
                        )
                        delay(2_000)
                    }
                    emit(WatchdogState.IDLE)
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, WatchdogState.IDLE)

    /**
     * serviceState 的布尔投影；驱动「连上即代授」的订阅
     *
     * Boolean projection of serviceState; drives the "grant as soon as
     * connected" subscription.
     */
    private val serviceConnected: StateFlow<Boolean> = serviceState
        .map { it == PrivilegedServiceState.Connected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _systemPermissions = MutableStateFlow(readSystemPermissions())
    override val systemPermissions: StateFlow<SystemPermissionState> = _systemPermissions.asStateFlow()

    // readiness 的输入都是流，refresh() 用自增计数踢它重算
    private val refreshTrigger = MutableStateFlow(0)

    /**
     * Shizuku 引导结论；随授权态、跳过开关与手动刷新即时重算。
     * WhileSubscribed(5s)：UI 不看就不付探测那次 IPC 的代价
     *
     * The Shizuku onboarding verdict; recomputed on grant state, the skip
     * flag and manual refresh. WhileSubscribed(5 s): the
     * probe's IPC cost is only paid while some UI is watching.
     */
    override val readiness: StateFlow<ShizukuReadiness> = combine(
        accessPort.state,
        appSettings.skipShizukuCheck,
        refreshTrigger,
    ) { remoteState, skipCheck, _ ->
        resolveReadiness(remoteState, skipCheck)
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ShizukuReadiness(),
    )

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // drop(1)：首值是启动时的当前后端，不该被当成"用户切了后端"而去解绑
        scope.launch {
            appSettings.startupBackend.drop(1).distinctUntilChanged().collect {
                // 绑定是按后端建的，换了后端不断开会连着错的特权进程
                servicePort.unbind()
                refresh()
            }
        }
        // 授权到手就把特权进程连上，用户不必再手动点一次
        scope.launch {
            accessPort.state
                .map { it.isGranted(it.configuredBackend) }
                .distinctUntilChanged()
                .filter { it }
                .collect { servicePort.bind() }
        }
        // 特权进程一上线就代授，省掉用户逐个点系统页
        scope.launch {
            serviceConnected.filter { it }.collect { grantViaPrivileged(PrivilegedGrant.ALL) }
        }
    }

    /**
     * 从 Shizuku 的授权界面切回来时状态已变，但没有回调会通知我们
     *
     * State has already changed by the time the user returns from Shizuku's
     * grant screen, and no callback tells us.
     */
    override fun onResume(owner: LifecycleOwner) {
        refresh()
    }

    /**
     * 重读授权快照、系统权限与 Shizuku 引导结论
     *
     * Re-reads the grant snapshot, system permissions, and the Shizuku
     * onboarding verdict.
     */
    override fun refresh() {
        accessPort.refresh()
        _systemPermissions.value = readSystemPermissions()
        refreshTrigger.update { it + 1 }
    }

    /**
     * 权限卡上那行按钮的快路径：特权进程在线就地代授，不跳系统页
     *
     * 只认那一位授没授成——全集代授里别的位失败与这次点击无关
     *
     * Fast path for the permission-card button: grants in place through the
     * privileged process when online, no system-page detour.
     *
     * Only the requested bit's success counts — other bits failing within the
     * full-set grant are irrelevant to this tap.
     */
    override suspend fun quickGrant(permission: SystemPermission): Boolean {
        val bit = permission.grantBit
        val granted = grantViaPrivileged(bit) ?: return false
        return granted and bit != 0
    }

    /**
     * 用特权身份给自己授权，返回特权进程报回的已授位；特权进程不在线返回 null
     *
     * 这是保活权限的主路径，首页那几个手点入口只是特权进程没起来时的兜底。
     * 走 shell/root 身份直接改 AppOps 与 deviceidle 白名单，用户看不到任何系统弹窗
     *
     * [requested] 上线那次是 [PrivilegedGrant.ALL] 全集（对齐 参考实现，不按运行模式挑），
     * [quickGrant] 传单个位
     *
     * Grants this app privileges with the privileged identity and returns the
     * bits the privileged process reports as granted; null when it is offline.
     *
     * This is the main path for keep-alive permissions; the manual entries on
     * the home screen are only a fallback for when the privileged process is
     * down. Uses the shell/root identity to write AppOps and the deviceidle
     * whitelist directly, with no system dialog visible to the user.
     *
     * On bring-up the request is the full [PrivilegedGrant.ALL] set (aligned
     * with the reference implementation, unfiltered by run mode);
     * [quickGrant] passes a single bit.
     */
    private suspend fun grantViaPrivileged(requested: Int): Int? {
        val granted = withContext(AppDispatchers.IO) {
            runCatching {
                servicePort.serviceOrNull()?.grantPermissions(
                    appContext.packageName,
                    appContext.applicationInfo.uid,
                    requested,
                )
            }.onFailure { Timber.w(it, "Privileged permission grant failed") }.getOrNull()
        }
        Timber.i("Privileged grant result requested=%s granted=%s", requested, granted)
        // 无障碍是异步绑定的，代授返回成功不代表服务已经连上
        if (granted != null && granted and PrivilegedGrant.ACCESSIBILITY != 0) {
            withTimeoutOrNull(ACCESSIBILITY_BIND_TIMEOUT_MS.milliseconds) {
                AccessibilityHelperService.isConnected.first { it }
            } ?: Timber.w("Accessibility service did not connect within timeout after grant")
        }
        refresh()
        return granted
    }

    /**
     * 这些项没有变更回调，只能在 onResume 与手动 refresh 时重读
     *
     * There is no change callback for these, so they are re-read only on
     * onResume and manual refresh.
     */
    private fun readSystemPermissions() = SystemPermissionState(
        notification = SystemPermissionRequester.isGranted(appContext, SystemPermission.Notification),
        batteryWhitelist = SystemPermissionRequester.isGranted(
            appContext,
            SystemPermission.BatteryWhitelist,
        ),
        overlay = SystemPermissionRequester.isGranted(appContext, SystemPermission.Overlay),
        storage = SystemPermissionRequester.isGranted(appContext, SystemPermission.Storage),
        accessibility = SystemPermissionRequester.isGranted(appContext, SystemPermission.Accessibility),
    )

    /**
     * 向当前配置后端发起授权；进行中置 [isGranting]
     *
     * Requests the grant from the configured backend; flips [isGranting] while
     * in flight.
     *
     * @return 授权是否到手 / whether the grant was obtained
     */
    override suspend fun requestRemoteAccess(): Boolean {
        val current = accessPort.refresh()
        if (current.isGranted(current.configuredBackend)) return true
        if (current.configuredBackend == RemoteBackend.SHIZUKU && !current.shizukuAvailable) {
            openShizuku(appContext)
            return false
        }
        _isGranting.value = true
        return try {
            accessPort.request(current.configuredBackend)
        } finally {
            _isGranting.value = false
            refresh()
        }
    }

    /**
     * 手动拉起特权进程
     *
     * 自动路径（授权到手即 bind）覆盖不了两种情况：特权进程崩过一次，或用户在系统里
     * 撤掉又重新给了授权。那时状态卡在 Died/Disconnected，没有这个入口就只能重启 app
     *
     * Manually brings up the privileged process.
     *
     * The automatic path (bind as soon as the grant lands) misses two cases:
     * the privileged process crashed once, or the user revoked and re-granted
     * in the system. The state is then stuck at Died/Disconnected, and without
     * this entry point only an app restart would recover.
     */
    override suspend fun bindService(): ServiceBindResult {
        if (serviceState.value == PrivilegedServiceState.Connected) return ServiceBindResult.AlreadyConnected
        val current = accessPort.refresh()
        val backend = current.configuredBackend
        if (!current.isAvailable(backend)) {
            if (backend == RemoteBackend.SHIZUKU) openShizuku(appContext)
            return ServiceBindResult.BackendUnavailable(backend)
        }
        if (!current.isGranted(backend) && !requestRemoteAccess()) {
            return ServiceBindResult.AuthRejected(backend)
        }
        return runCatching { servicePort.bind() }
            .fold(
                onSuccess = { ServiceBindResult.Started },
                onFailure = {
                    Timber.e(it, "Failed to bind privileged process manually")
                    ServiceBindResult.Failed(uiTextFromFramework(it.message))
                },
            )
    }

    /** 解绑特权进程 / unbinds the privileged process */
    override fun unbindService() = servicePort.unbind()

    /**
     * 持久化切换后端；绑定层的解绑与刷新由 startupBackend 的订阅跟进
     *
     * Persists the backend switch; the binding layer's unbind and refresh
     * follow from the startupBackend subscription.
     */
    override suspend fun setBackend(backend: RemoteBackend) {
        if (appSettings.startupBackend.value == backend) return
        appSettings.setStartupBackend(backend)
    }

    /**
     * 持久化「跳过 Shizuku 探测」；此后 readiness 恒为 Ready
     *
     * Persists "skip the Shizuku probe"; readiness then stays Ready.
     */
    override suspend fun skipShizukuCheck() {
        appSettings.setSkipShizukuCheck(true)
    }

    /**
     * 在主线程打开内置启动页；不要求安装外部 Shizuku。
     *
     * Opens the embedded activation page on the main thread without requiring an external
     * Shizuku installation.
     */
    fun openShizuku(context: Context): Boolean = runCatching {
        context.startActivity(Intent(context, AdbActivationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { Timber.w(it, "Failed to open embedded activation") }.getOrDefault(false)

    /**
     * 按认证状态选择引导；内置已授权优先，外部服务仍可走原授权流程。
     *
     * Chooses guidance from authentication state. An embedded grant takes priority, while an
     * external service can still use the original grant flow.
     */
    private fun resolveReadiness(
        remoteState: RemoteAccessState,
        skipCheck: Boolean,
    ): ShizukuReadiness {
        val stage = when {
            skipCheck -> ShizukuReadinessStage.Ready
            remoteState.configuredBackend != RemoteBackend.SHIZUKU -> ShizukuReadinessStage.Ready
            remoteState.shizukuGranted -> ShizukuReadinessStage.Ready
            ShizukuManager.isSui -> ShizukuReadinessStage.SuiAvailable
            remoteState.shizukuAvailable -> ShizukuReadinessStage.NeedAuth
            else -> ShizukuReadinessStage.BuiltInSetup
        }
        return ShizukuReadiness(stage, remoteState.rootAvailable)
    }

    private companion object {
        /**
         * 系统绑定无障碍服务是异步的，3 秒等不到就当没连上，不卡住授权流程
         *
         * The system binds the accessibility service asynchronously; after 3 s
         * without a connection it is treated as unbound, never blocking the
         * grant flow.
         */
        const val ACCESSIBILITY_BIND_TIMEOUT_MS = 3_000L

    }
}
