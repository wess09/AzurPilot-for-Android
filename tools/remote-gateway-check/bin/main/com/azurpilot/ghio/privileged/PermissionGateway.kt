package com.azurpilot.ghio.privileged

import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.i18n.UiText
import kotlinx.coroutines.flow.StateFlow

/**
 * 汇总提权状态并代发授权动作，是 ViewModel 看到的唯一提权面
 *
 * 抽接口只为可测：单测里换成记录调用的替身，不去碰 Shizuku binder 与
 * ProcessLifecycleOwner。生产实现是 [PermissionManager]，由 Koin 显式注入，
 * 不做默认参数。
 *
 * Aggregates privilege state and forwards grant actions; the single privilege
 * facade the ViewModel sees.
 *
 * The interface exists for testability: unit tests substitute a recording fake
 * instead of touching the Shizuku binder or ProcessLifecycleOwner. The
 * production implementation is [PermissionManager], injected explicitly by
 * Koin with no default parameters.
 */
interface PermissionGateway {
    /** 两后端可用性与授权的快照 / availability-and-grant snapshot of both backends */
    val state: StateFlow<RemoteAccessState>

    /**
     * [requestRemoteAccess] 进行中，UI 据此转圈
     *
     * True while [requestRemoteAccess] is in flight; the UI shows progress.
     */
    val isGranting: StateFlow<Boolean>

    /**
     * Shizuku 引导结论（装/跑/授权/官方版冲突）
     *
     * The Shizuku onboarding verdict (install, run, grant, official-build
     * conflict).
     */
    val readiness: StateFlow<ShizukuReadiness>

    /**
     * 特权进程连接态；binder 不出这一层（见 [PrivilegedServiceState]）
     *
     * Privileged-process connection state; the binder never leaves this layer
     * (see [PrivilegedServiceState]).
     */
    val serviceState: StateFlow<PrivilegedServiceState>

    /**
     * 目标 app 在虚拟屏上的看门狗状态（特权进程轮询，app 侧只读）
     *
     * Watchdog state of the target app on the virtual display (polled inside
     * the privileged process, read-only from the app side).
     */
    val watchdogState: StateFlow<WatchdogState>

    /**
     * 保活相关的系统权限快照；读数走本地探测，不经提权后端
     *
     * Snapshot of keep-alive-related system permissions; read locally, never
     * through the privilege backends.
     */
    val systemPermissions: StateFlow<SystemPermissionState>

    /**
     * 向当前配置的后端发起提权授权
     *
     * Requests privilege authorization from the configured backend.
     *
     * @return 授权是否到手 / whether the grant was obtained
     */
    suspend fun requestRemoteAccess(): Boolean

    /**
     * 权限卡的快路径：单个保活权限就地代授，不跳系统页
     *
     * The permission-card fast path: grants one keep-alive permission in place,
     * without jumping to the system page.
     *
     * @return 该位是否授成 / whether that bit was granted
     */
    suspend fun quickGrant(permission: SystemPermission): Boolean

    /** 持久化切换提权后端 / persists the privilege-backend switch */
    suspend fun setBackend(backend: RemoteBackend)

    /** 持久化「跳过 Shizuku 探测」 / persists the skip-the-Shizuku-probe choice */
    suspend fun skipShizukuCheck()

    /**
     * 重读后端快照、系统权限与 Shizuku 引导结论
     *
     * Re-reads the backend snapshot, system permissions, and the Shizuku
     * verdict.
     */
    fun refresh()

    /**
     * 手动拉起特权进程；缺授权时顺带发起一次授权
     *
     * Manually brings up the privileged process; requests authorization first
     * when it is missing.
     */
    suspend fun bindService(): ServiceBindResult

    /** 解绑特权进程 / unbinds the privileged process */
    fun unbindService()
}

/**
 * 特权进程连接态的对外投影
 *
 * 不直接暴露 `RemoteServiceManager.ServiceState`：那个 sealed class 的 Connected 带着
 * `RemoteService` binder，漏进 UiState 就等于让 UI 拿到了 IPC 句柄
 *
 * [Died] 与 [Disconnected] 必须分开：前者是特权进程崩了或被 ROM 杀了，后者是还没连过，
 * 合成一个 Boolean 之后界面上这两种情况长得一模一样
 *
 * External projection of the privileged-process connection state.
 *
 * `RemoteServiceManager.ServiceState` is not exposed directly: its Connected
 * variant carries the `RemoteService` binder, and leaking that into a UiState
 * amounts to handing an IPC handle to the UI.
 *
 * [Died] and [Disconnected] must stay distinct: the former means the
 * privileged process crashed or was killed by the ROM, the latter means no
 * connection has been made yet; collapsed into one Boolean, the UI could not
 * tell them apart.
 */
enum class PrivilegedServiceState {
    /** 尚未绑定或已主动解绑 / not bound yet, or actively unbound */
    Disconnected,

    /** 绑定进行中 / binding in progress */
    Connecting,

    /** 已连接，服务面可用 / connected, service face usable */
    Connected,

    /** 特权进程崩了或被 ROM 杀了 / the privileged process crashed or was killed by the ROM */
    Died,

    /** 绑定失败（未授权、超时、binder 异常等） / binding failed (no grant, timeout, binder error, ...) */
    Error,
}

/**
 * [PermissionGateway.bindService] 的结局；文案由 UI 层挑，这里只给分类
 *
 * Outcome of [PermissionGateway.bindService]; the UI layer picks the copy,
 * this only classifies it.
 */
sealed interface ServiceBindResult {
    /**
     * 绑定已发起——连上是异步的，看 [PermissionGateway.serviceState]
     *
     * Binding started — connecting is asynchronous; watch
     * [PermissionGateway.serviceState].
     */
    data object Started : ServiceBindResult

    /** 已经连着，无需再绑 / already connected; no need to bind again */
    data object AlreadyConnected : ServiceBindResult

    /**
     * [backend] 不可用（Shizuku 未装未跑 / su 不存在）
     *
     * [backend] is unavailable (Shizuku missing or not running / no su).
     */
    data class BackendUnavailable(val backend: RemoteBackend) : ServiceBindResult

    /** 用户在 [backend] 的授权流程里拒绝 / the user denied the grant in [backend]'s flow */
    data class AuthRejected(val backend: RemoteBackend) : ServiceBindResult

    /** 绑定抛了异常；[reason] 是可直接展示的文案 / binding threw; [reason] carries display-ready copy */
    data class Failed(val reason: UiText) : ServiceBindResult
}

/**
 * 汇总保活相关系统权限的授予情况
 *
 * app 进程一死，特权进程的看门狗就自杀并释放虚拟屏——实测 MIUI 的 SwipeUpClean
 * 会按 Adj=905 直接 force-stop。前台服务本体还没做，这几项先让用户能自己开。
 * 没有变更回调，只能随 [PermissionGateway.refresh] 重读。
 *
 * Snapshot of keep-alive-related system permissions.
 *
 * When the app process dies, the privileged process's watchdog kills itself
 * and releases the virtual display — MIUI's SwipeUpClean was observed to
 * force-stop outright at Adj=905. The foreground service itself is not built
 * yet; these entries at least let the user switch them on manually. There is
 * no change callback; the snapshot is only re-read by
 * [PermissionGateway.refresh].
 *
 * @param notification 通知权限 / notification permission
 * @param batteryWhitelist 电池优化白名单 / battery-optimization whitelist
 * @param overlay 悬浮窗 / system alert window
 * @param storage 外部存储（MANAGE_EXTERNAL_STORAGE） / external storage (MANAGE_EXTERNAL_STORAGE)
 * @param accessibility 本 app 的无障碍服务已启用 / this app's accessibility service is enabled
 */
data class SystemPermissionState(
    val notification: Boolean = false,
    val batteryWhitelist: Boolean = false,
    val overlay: Boolean = false,
    val storage: Boolean = false,
    val accessibility: Boolean = false,
)
