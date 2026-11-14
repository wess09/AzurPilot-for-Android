package com.azurpilot.ghio.privileged
import com.azurpilot.ghio.AppDispatchers

import android.content.Context
import android.os.IBinder
import android.os.Process
import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.domain.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 特权进程连接状态机的唯一持有者；[PrivilegedServicePort] 的生产实现
 *
 * 按当前配置后端驱动 [ShizukuRemoteServiceConnector] 或 [RootRemoteServiceConnector]，
 * 自己持有 binder、linkToDeath 与全部状态迁移；对外只投影不带 binder 的
 * [PrivilegedServiceState]。
 *
 * 状态迁移：Disconnected → Connecting → Connected；Connected 收敛到 Died
 * （binder 死亡 / 服务进程消失）或 Disconnected（主动 unbind）；失败进 Error。
 * 迟到的连接事件与换代后的死亡通知一律按身份比对丢弃。
 *
 * 进程级单例 object，须先 [initialize] 再 [bind]；连接器回调可能来自 binder
 * 线程或协程，所有状态迁移统一收在 [lock] 内。
 *
 * The sole owner of the privileged-process connection state machine; the
 * production implementation of [PrivilegedServicePort].
 *
 * Drives [ShizukuRemoteServiceConnector] or [RootRemoteServiceConnector]
 * according to the configured backend, owning the binder, linkToDeath, and
 * every state transition; only the binder-less [PrivilegedServiceState]
 * projection leaves this object.
 *
 * Transitions: Disconnected → Connecting → Connected; Connected settles into
 * Died (binder death / service process gone) or Disconnected (explicit
 * unbind); failures land in Error. Stale connection events and death
 * notifications from a superseded binder are dropped by identity comparison.
 *
 * A process-level singleton object: call [initialize] before [bind]. Connector
 * callbacks may arrive on binder threads or coroutines; every state
 * transition happens under [lock].
 */
object RemoteServiceManager : PrivilegedServicePort {

    /**
     * 内部连接态；[Connected] 揣着 binder，绝不能漏出本 object
     *
     * Internal connection state; [Connected] carries the binder and must never
     * leave this object.
     */
    sealed class ServiceState {
        /** 尚未绑定或已主动解绑 / not bound yet, or actively unbound */
        data object Disconnected : ServiceState()

        /** 绑定进行中 / binding in progress */
        data object Connecting : ServiceState()

        /** 特权进程崩了或被 ROM 杀了 / the privileged process crashed or was killed by the ROM */
        data object Died : ServiceState()

        /** 已连接，持有服务面 / connected, holding the service face */
        data class Connected(val service: RemoteService) : ServiceState()

        /** 连接失败，[exception] 为原因 / connecting failed; [exception] says why */
        data class Error(val exception: Throwable) : ServiceState()
    }

    /**
     * 连接兜底超时；主要覆盖无内建超时的 Shizuku 路径（Root 连接器自带 15s 先行）
     *
     * Connect backstop timeout; mainly covers the Shizuku path which has no
     * built-in timeout of its own (the Root connector brings a 15 s one).
     */
    private const val CONNECT_TIMEOUT_MS = 20_000L

    /**
     * [useService] 等连接就绪的上限；比 [CONNECT_TIMEOUT_MS] 短，超时兜底仍由后者负责收敛状态
     *
     * Upper bound for [useService] waiting on a connection; shorter than
     * [CONNECT_TIMEOUT_MS], which still owns settling the state on timeout.
     */
    private const val USE_SERVICE_TIMEOUT_MS = 12_000L

    // 状态迁移（boundBackend / currentBinder / _state）统一在此锁内完成
    private val lock = Any()

    private val currentBinder = AtomicReference<IBinder>()
    // 由 lock 保护
    private var currentDeathRecipient: BindingDeathRecipient? = null
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Disconnected)

    private val timeoutScope = CoroutineScope(AppDispatchers.Default + SupervisorJob())
    private val connectAttempt = AtomicInteger(0)

    private val connectors: Map<RemoteBackend, RemoteServiceConnectorBackend> = mapOf(
        RemoteBackend.SHIZUKU to ShizukuRemoteServiceConnector,
        RemoteBackend.ROOT to RootRemoteServiceConnector
    )

    @Volatile
    private var boundBackend: RemoteBackend? = null

    /**
     * 对外只给不带 binder 的投影：[ServiceState.Connected] 揣着 [RemoteService]，
     * 漏出这一层就等于把 IPC 句柄发给了 UI
     *
     * Exposes only the binder-less projection: [ServiceState.Connected]
     * carries [RemoteService], and leaking it past this layer hands an IPC
     * handle to the UI.
     */
    override val serviceState: StateFlow<PrivilegedServiceState> = _state
        .map { it.toPrivilegedServiceState() }
        .stateIn(timeoutScope, SharingStarted.Eagerly, PrivilegedServiceState.Disconnected)

    override val currentBackend: RemoteBackend? get() = boundBackend

    // 携带绑定时的 binder，迟到的死亡通知靠身份比对丢弃
    private class BindingDeathRecipient(val binder: IBinder) : IBinder.DeathRecipient {
        override fun binderDied() = onBinderDied(this)
    }

    /**
     * 两个连接器共用的回调漏斗：进锁后先按 [boundBackend] 丢弃过期事件，
     * 再做状态迁移并落 ServiceBootLogger 时间线
     *
     * The callback funnel shared by both connectors: under the lock it first
     * drops stale events against [boundBackend], then performs the state
     * transitions and appends the ServiceBootLogger timeline.
     */
    private val connectorCallbacks = object : RemoteServiceConnectorBackend.Callbacks {
        override fun onConnected(backend: RemoteBackend, binder: IBinder) {
            val service: RemoteService
            synchronized(lock) {
                if (boundBackend != backend) {
                    ServiceBootLogger.event("CB_ON_CONNECTED_STALE", "backend=$backend bound=$boundBackend")
                    Timber.w("Ignoring stale %s connection", backend)
                    return
                }
                ServiceBootLogger.event("CB_ON_CONNECTED", "backend=$backend")
                clearCurrentBinderLocked()
                val recipient = BindingDeathRecipient(binder)
                try {
                    binder.linkToDeath(recipient, 0)
                } catch (e: Exception) {
                    // binder 送达时已死亡
                    ServiceBootLogger.event("CB_ON_CONNECTED_DEAD", "backend=$backend ${e.message}")
                    Timber.e(e, "RemoteService binder dead on arrival: %s", backend)
                    boundBackend = null
                    _state.value = ServiceState.Error(e)
                    return
                }
                currentBinder.set(binder)
                currentDeathRecipient = recipient
                service = RemoteService.Stub.asInterface(binder)
                _state.value = ServiceState.Connected(service)
                ServiceBootLogger.event("BINDER_CONNECTED", "backend=$backend linkToDeath ok")
            }
            runCatching { service.heartbeat(Process.myPid()) }
                .onFailure { Timber.w(it, "heartbeat failed") }
            runCatching {
                synchronized(lock) {
                    if (currentBinder.get() === binder) SharedFrameHost.start(service)
                }
            }
                .onFailure { Timber.w(it, "Shared-memory capture setup failed; using TCP") }
        }

        override fun onDisconnected(backend: RemoteBackend) {
            synchronized(lock) {
                if (boundBackend != backend) {
                    return
                }
                ServiceBootLogger.event("CB_ON_DISCONNECTED", "backend=$backend")
                Timber.i("RemoteService disconnected: %s", backend)
                // 被动断开由服务进程死亡触发，Connected 态下收敛为 Died 保证终态确定
                val wasConnected = _state.value is ServiceState.Connected
                clearCurrentBinderLocked()
                boundBackend = null
                if (wasConnected) {
                    ServiceBootLogger.event("STATE_DIED")
                    _state.value = ServiceState.Died
                } else {
                    ServiceBootLogger.event("STATE_DISCONNECTED")
                    _state.value = ServiceState.Disconnected
                }
            }
        }

        override fun onError(backend: RemoteBackend, throwable: Throwable) {
            synchronized(lock) {
                if (boundBackend != backend) {
                    return
                }
                ServiceBootLogger.event("CB_ON_ERROR", "backend=$backend ${throwable.javaClass.simpleName}: ${throwable.message}")
                Timber.e(throwable, "RemoteService connection failed: %s", backend)
                clearCurrentBinderLocked()
                boundBackend = null
                _state.value = ServiceState.Error(throwable)
            }
        }
    }

    /**
     * 进程级初始化：起诊断日志、探测 Sui、装配授权协调器与 Root 连接器
     *
     * 须在应用启动时先于任何 [bind] 调用一次；重复调用由各子模块自行去重。
     *
     * Process-level initialization: boots the diagnostic logger, probes Sui,
     * and wires the authorization coordinator and the Root connector.
     *
     * Must be called once at app startup before any [bind]; repeated calls are
     * deduplicated by the submodules themselves.
     */
    fun initialize(
        context: Context,
        backendProvider: () -> RemoteBackend,
    ) {
        ServiceBootLogger.init()
        EmbeddedAdbServiceConnector.initialize(context)
        EmbeddedAdbManager.initialize(context, backendProvider() == RemoteBackend.SHIZUKU)
        ShizukuManager.initSui(context.packageName)
        RemoteAccessCoordinator.initialize(backendProvider)
        RootRemoteServiceConnector.initialize(context)
    }

    /**
     * 收敛 binder 死亡通知：身份比对后丢弃换代 binder 的迟到通知，
     * 活着的则收敛为 [ServiceState.Died]
     *
     * Settles a binder death notification: identity comparison drops the late
     * notice from a superseded binder, otherwise the state settles into
     * [ServiceState.Died].
     */
    private fun onBinderDied(recipient: BindingDeathRecipient) {
        synchronized(lock) {
            if (currentBinder.get() !== recipient.binder) {
                ServiceBootLogger.event("BINDER_DIED_STALE")
                return
            }
            ServiceBootLogger.event("BINDER_DIED")
            Timber.w("RemoteService binder died")
            clearCurrentBinderLocked()
            boundBackend = null
            ServiceBootLogger.event("STATE_DIED")
            _state.value = ServiceState.Died
        }
    }

    /**
     * 解除死亡通知注册并清空 [currentBinder]；须持 [lock] 调用
     *
     * Unregisters the death recipient and clears [currentBinder]; call with
     * [lock] held.
     */
    private fun clearCurrentBinderLocked() {
        SharedFrameHost.stop()
        val binder = currentBinder.getAndSet(null)
        val recipient = currentDeathRecipient
        currentDeathRecipient = null
        if (binder != null && recipient != null) {
            runCatching {
                binder.unlinkToDeath(recipient, 0)
            }.onFailure {
                Timber.w(it, "unlinkToDeath failed")
            }
        }
    }

    /**
     * 按当前配置后端发起绑定
     *
     * 未授权直接落 [ServiceState.Error]，不发起连接；同后端已连接或连接中直接跳过；
     * 换后端先解绑旧的。本方法返回只代表进入 [ServiceState.Connecting]，
     * 真正的 Connected 由连接器回调带入。
     *
     * Starts binding on the currently configured backend.
     *
     * Without the grant it settles straight into [ServiceState.Error] without
     * connecting; a live connection or in-flight binding on the same backend is skipped; a
     * backend switch unbinds the old one first. Returning only means the state
     * entered [ServiceState.Connecting] — actual Connected arrives through the
     * connector callbacks.
     */
    override fun bind() {
        val backend = RemoteAccessCoordinator.refresh().configuredBackend
        if (!RemoteAccessCoordinator.isGranted(backend)) {
            val exception = IllegalStateException("${backend.display} permission not granted")
            ServiceBootLogger.event("BIND_DENIED", "backend=$backend not granted")
            Timber.w(exception)
            synchronized(lock) {
                boundBackend = null
                _state.value = ServiceState.Error(exception)
            }
            return
        }

        val attempt: Int
        synchronized(lock) {
            if (_state.value is ServiceState.Connected && boundBackend == backend &&
                currentBinder.get()?.isBinderAlive == true
            ) {
                ServiceBootLogger.event("BIND_SKIP", "already connected backend=$backend")
                return
            }
            if (_state.value is ServiceState.Connecting && boundBackend == backend) {
                ServiceBootLogger.event("BIND_SKIP", "already connecting backend=$backend")
                return
            }

            if (boundBackend != null) {
                Timber.i("Unbinding old service before binding new one")
                unbindLocked()
            }

            boundBackend = backend
            attempt = connectAttempt.incrementAndGet()
            ServiceBootLogger.event("BIND", "backend=$backend attempt=$attempt")
            _state.value = ServiceState.Connecting
            ServiceBootLogger.event("CONNECTING", "backend=$backend attempt=$attempt")
            connectors.getValue(backend).connect(connectorCallbacks)
        }
        startConnectTimeout(attempt, backend)
    }

    /**
     * 连接超时兜底，主要覆盖无超时机制的 Shizuku 路径（Root 连接器自带 15s 超时先行）
     *
     * Connect-timeout backstop, mainly for the Shizuku path which has no
     * timeout mechanism of its own (the Root connector brings its own 15 s).
     */
    private fun startConnectTimeout(attempt: Int, backend: RemoteBackend) {
        timeoutScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            synchronized(lock) {
                if (connectAttempt.get() != attempt ||
                    _state.value !is ServiceState.Connecting ||
                    boundBackend != backend
                ) {
                    return@launch
                }
                ServiceBootLogger.event(
                    "CONNECT_TIMEOUT",
                    "still CONNECTING after ${CONNECT_TIMEOUT_MS}ms (backend=$backend attempt=$attempt) — service process likely failed to start or did not return binder, see service_boot_debug.log"
                )
                runCatching {
                    connectors.getValue(backend).disconnect(currentBinder.get())
                }.onFailure {
                    Timber.w(it, "disconnect after connect timeout failed")
                }
                clearCurrentBinderLocked()
                boundBackend = null
                _state.value = ServiceState.Error(
                    TimeoutException("connect timeout after ${CONNECT_TIMEOUT_MS}ms (backend=$backend)")
                )
            }
        }
    }

    /**
     * 拆当前连接并清 [boundBackend]；须持 [lock] 调用
     *
     * Tears down the current binding and clears [boundBackend]; call with
     * [lock] held.
     */
    private fun unbindLocked() {
        val backend = boundBackend ?: return
        connectors.getValue(backend).disconnect(currentBinder.get())
        clearCurrentBinderLocked()
        boundBackend = null
    }

    /**
     * 主动解绑；幂等，且不覆盖待消费的 [ServiceState.Died] 信号
     *
     * Explicit unbind; idempotent, and it never overwrites a pending
     * [ServiceState.Died] signal.
     */
    override fun unbind() {
        synchronized(lock) {
            if (_state.value == ServiceState.Disconnected && boundBackend == null) {
                return
            }
            unbindLocked()
            // 主动解绑不覆盖待消费的 Died 信号
            if (_state.value != ServiceState.Died) {
                ServiceBootLogger.event("STATE_DISCONNECTED")
                _state.value = ServiceState.Disconnected
            }
        }
    }

    /**
     * 拿服务面；未连接则先 [bind] 并挂起等待终态
     *
     * Fetches the service face; binds first and suspends until a terminal
     * state when not connected.
     *
     * @param timeoutMs 等待连接就绪的上限（毫秒）/ upper bound for waiting on the connection, in ms
     * @return 已连接的服务面 / the connected service face
     * @throws Throwable 连接以 [ServiceState.Error] 收场时，原样抛出其携带的异常
     *   / rethrown as-is when the connection ends in [ServiceState.Error]
     * @throws TimeoutCancellationException 超时仍未连接 / still unconnected after the timeout
     */
    suspend fun getInstance(timeoutMs: Long = 10_000): RemoteService {
        serviceOrNull()?.let { return it }

        bind()
        return try {
            withTimeout(timeoutMs) {
                _state.first { it is ServiceState.Connected || it is ServiceState.Error }
                    .let { currentState ->
                        when (currentState) {
                            is ServiceState.Connected -> currentState.service
                            is ServiceState.Error -> throw currentState.exception
                            else -> error("Unexpected state: $currentState")
                        }
                    }
            }
        } catch (e: TimeoutCancellationException) {
            ServiceBootLogger.event("GET_INSTANCE_TIMEOUT", "after ${timeoutMs}ms state=${_state.value}")
            throw e
        }
    }

    override fun serviceOrNull(): RemoteService? {
        val current = _state.value
        return if (current is ServiceState.Connected) current.service else null
    }

    /**
     * 确保授权与连接就绪后，把服务面交给 [action]
     *
     * 先刷新授权；缺授权就当场发起，仍拿不到则抛异常。当前绑定的后端与配置
     * 不一致时先重绑。调用方无需关心当前是哪条后端路径。
     *
     * Ensures the grant and the connection before handing the service face to
     * [action].
     *
     * Refreshes authorization first; requests the grant on the spot when it is
     * missing and throws if it still fails. Rebinds first when the current
     * binding is on a backend other than the configured one. Callers need not
     * know which backend path is in play.
     *
     * @throws IllegalStateException 授权拿不到 / the grant cannot be obtained
     * @throws Throwable 连接失败或超时（见 [getInstance]）/ connecting fails or times out (see [getInstance])
     */
    override suspend fun <R> useService(action: suspend (RemoteService) -> R): R {
        var accessState = RemoteAccessCoordinator.refresh()
        var backend = accessState.configuredBackend
        if (!accessState.isGranted(backend)) {
            val granted = RemoteAccessCoordinator.request(backend)
            accessState = RemoteAccessCoordinator.refresh()
            backend = accessState.configuredBackend
            if (!granted || !accessState.isGranted(backend)) {
                throw IllegalStateException("${backend.display} permission not granted")
            }
        }

        if (boundBackend != null && boundBackend != backend) {
            Timber.i("Rebinding remote service from %s to %s", boundBackend, backend)
            unbind()
        }

        val service = getInstance(USE_SERVICE_TIMEOUT_MS)
        return action(service)
    }
}

/**
 * 内部态 → 对外投影；只丢信息（binder 与异常对象），不加信息
 *
 * Internal state → external projection; it only drops information (the binder
 * and the exception object), never adds any.
 */
private fun RemoteServiceManager.ServiceState.toPrivilegedServiceState() = when (this) {
    is RemoteServiceManager.ServiceState.Connected -> PrivilegedServiceState.Connected
    RemoteServiceManager.ServiceState.Connecting -> PrivilegedServiceState.Connecting
    RemoteServiceManager.ServiceState.Died -> PrivilegedServiceState.Died
    RemoteServiceManager.ServiceState.Disconnected -> PrivilegedServiceState.Disconnected
    is RemoteServiceManager.ServiceState.Error -> PrivilegedServiceState.Error
}
