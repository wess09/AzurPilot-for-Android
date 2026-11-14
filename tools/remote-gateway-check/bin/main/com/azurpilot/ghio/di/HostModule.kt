package com.azurpilot.ghio.di

import com.azurpilot.ghio.service.HostState
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 宿主状态绑定：[HostState] 单例
 *
 * [HostState] 是外壳真实状态（特权连接态、桥可达性、虚拟屏 displayId）的唯一
 * 来源，进程内只此一份。
 *
 * Host-state binding: the [HostState] singleton.
 *
 * [HostState] is the single source of the shell's real state (privileged
 * connectivity, bridge reachability, the virtual display's displayId); exactly
 * one instance lives in the process.
 */
val hostModule = module {
    single { HostState(androidContext(), get(), get(), get(named<AppCoroutineScope>()), get()) }
}
