package com.azurpilot.ghio.di

import com.azurpilot.ghio.privileged.DisplaySizeController
import com.azurpilot.ghio.privileged.DisplaySizeGateway
import com.azurpilot.ghio.privileged.PermissionGateway
import com.azurpilot.ghio.privileged.PermissionManager
import com.azurpilot.ghio.privileged.PrivilegedServicePort
import com.azurpilot.ghio.privileged.RemoteAccessCoordinator
import com.azurpilot.ghio.privileged.RemoteAccessPort
import com.azurpilot.ghio.privileged.RemoteServiceManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * 提权链路绑定：对外端口与其生产实现、权限代授与主屏分辨率网关
 *
 * 端口直接绑 object 单例（[RemoteServiceManager] / [RemoteAccessCoordinator]）：
 * 连接状态机与后端可用性聚合在进程内只有一份；[PermissionManager] 与
 * [DisplaySizeGateway] 经 Koin 注入端口，而不是调用点各自 import 全局。
 *
 * Privilege bindings: outbound ports with their production implementations,
 * permission granting, and the primary-display resolution gateway.
 *
 * Ports bind object singletons directly ([RemoteServiceManager] /
 * [RemoteAccessCoordinator]): the connection state machine and backend
 * availability aggregation exist exactly once per process;
 * [PermissionManager] and [DisplaySizeGateway] receive their ports through
 * Koin instead of call sites importing globals.
 */
val privilegedModule = module {
    single<PrivilegedServicePort> { RemoteServiceManager }
    single<RemoteAccessPort> { RemoteAccessCoordinator }

    single { PermissionManager(androidContext(), get(), get(), get()) }
    single<PermissionGateway> { get<PermissionManager>() }
    single<DisplaySizeGateway> { DisplaySizeController(androidContext(), get()) }
}