package com.azurpilot.ghio.di

import com.azurpilot.ghio.proot.AzurPilotGateway
import com.azurpilot.ghio.proot.AzurPilotPreferenceStore
import com.azurpilot.ghio.proot.AzurPilotPreferences
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.ocr.OcrServer
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * PRoot 运行时绑定：会话宿主、运行控制器、WebSocket 网关、偏好存储与仓库
 *
 * PRoot runtime bindings: the session host, run controller, WebSocket
 * gateway, preference store, and repository.
 */
val prootModule = module {
    single { OcrServer(androidContext(), get()) }
    single { ProotHost(androidApplication(), get(named<AppCoroutineScope>()), get(), get(), get()) }
    single {
        val autoUpdater = get<com.azurpilot.ghio.provision.RuntimeAutoUpdater>()
        AzurPilotRunController(
            androidApplication(),
            get(named<AppCoroutineScope>()),
            get(),
            get(),
            autoUpdateBusy = autoUpdater.busy,
            tryBeginHotUpdate = autoUpdater::tryBeginHotUpdate,
            endHotUpdate = autoUpdater::endHotUpdate,
            onHotUpdateSettled = get<com.azurpilot.ghio.provision.RootfsProvisioner>()::refreshSourceUpdateCheck,
        )
    }
    // /api/v1/ws 富接口：原生界面（总览 / 配置 / 统计 / 设置）的全部数据来源
    // 订阅只有 repository 一处发出——events.subscribe 是整体替换语义，两处各订一次会互相顶掉
    single { com.azurpilot.ghio.remoteaccess.RemotePairings(androidContext()) }
    single { AzurPilotGateway(get(named<AppCoroutineScope>()), get()) }
    single<AzurPilotPreferenceStore> { AzurPilotPreferences(androidContext()) }
    single { AzurPilotRepository(get(named<AppCoroutineScope>()), get(), get(), get()) }
}
