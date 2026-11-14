package com.azurpilot.ghio.privileged

import com.azurpilot.ghio.domain.RemoteBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 汇总两个提权后端的可用性与授权状态；[RemoteAccessPort] 的生产实现
 *
 * 后端选择由外部提供：本层不决定它存在哪，也不感知持久化。进程级单例，
 * [initialize] 在启动时注入 backendProvider，并把两个后端的状态变化接到
 * [refresh] 上。
 *
 * Aggregates availability and grant state of both privilege backends; the
 * production implementation of [RemoteAccessPort].
 *
 * Backend selection is supplied from outside: this layer neither decides
 * where it is stored nor touches persistence. Process-level singleton;
 * [initialize] injects the backend provider at startup and wires both
 * backends' state changes into [refresh].
 */
object RemoteAccessCoordinator : RemoteAccessPort {

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener = RemoteAccessStateListener { refresh() }
    private val _state = MutableStateFlow(snapshot())
    @Volatile
    private var backendProvider: (() -> RemoteBackend)? = null

    override val state: StateFlow<RemoteAccessState> = _state.asStateFlow()

    private val backends = mapOf(
        RemoteBackend.ROOT to RootManager,
        RemoteBackend.SHIZUKU to ShizukuManager
    )

    /**
     * 注入后端选择器并装配状态监听；重复调用只刷新不重装
     *
     * 首次调用时订阅两个后端的状态变化；配置后端是 Root 时当场发起一次 su
     * 授权，把授权对话框提前到启动期。
     *
     * Injects the backend selector and wires the state listeners; repeated
     * calls only refresh.
     *
     * The first call subscribes to both backends' state changes; when the
     * configured backend is Root it also triggers the su grant right away,
     * moving the prompt up to startup time.
     */
    fun initialize(backendProvider: () -> RemoteBackend) {
        this.backendProvider = backendProvider
        if (initialized.compareAndSet(false, true)) {
            backends.values.forEach { it.addStateListener(listener) }
            
            scope.launch {
                val backend = configuredBackend()
                if (backend == RemoteBackend.ROOT) {
                    backends.getValue(RemoteBackend.ROOT).requestPermission()
                }
            }
        }
        refresh()
    }

    override fun refresh(): RemoteAccessState {
        val current = snapshot()
        _state.value = current
        return current
    }

    /** 当前快照里该后端是否可用 / whether [backend] is available in the current snapshot */
    fun isAvailable(backend: RemoteBackend): Boolean {
        return state.value.isAvailable(backend)
    }

    /** 当前快照里该后端是否已授权 / whether [backend] is granted in the current snapshot */
    fun isGranted(backend: RemoteBackend): Boolean {
        return state.value.isGranted(backend)
    }

    override suspend fun request(backend: RemoteBackend): Boolean {
        val current = refresh()
        if (current.isGranted(backend)) {
            return true
        }
        if (!current.isAvailable(backend)) {
            return false
        }
        val granted = backends.getValue(backend).requestPermission()
        val refreshed = refresh()
        return granted && refreshed.isGranted(backend)
    }

    /**
     * 当前配置的后端；[initialize] 之前一律回退 Shizuku
     *
     * The configured backend; falls back to Shizuku until [initialize] runs.
     */
    fun configuredBackend(): RemoteBackend {
        return backendProvider?.invoke() ?: RemoteBackend.SHIZUKU
    }

    /** 现场重读两个后端，组一帧快照 / re-reads both backends and assembles one snapshot */
    private fun snapshot(): RemoteAccessState {
        val shizukuAvailable = ShizukuManager.isAvailable()
        val shizukuGranted = ShizukuManager.isGranted()
        val rootAvailable = RootManager.isAvailable()
        val rootGranted = RootManager.isGranted()
        return RemoteAccessState(
            shizukuAvailable = shizukuAvailable,
            shizukuGranted = shizukuGranted,
            rootAvailable = rootAvailable,
            rootGranted = rootGranted,
            configuredBackend = configuredBackend()
        )
    }
}
