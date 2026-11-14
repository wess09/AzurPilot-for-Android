package com.azurpilot.ghio.di

import com.azurpilot.ghio.provision.RootfsProvisioner
import com.azurpilot.ghio.provision.RuntimeAutoUpdater
import com.azurpilot.ghio.settings.AppSettingsManager
import org.koin.android.ext.koin.androidApplication
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 运行时供给绑定：[RootfsProvisioner] 与 [RuntimeAutoUpdater] 单例
 *
 * Provisioning binding: the [RootfsProvisioner] and [RuntimeAutoUpdater] singletons.
 */
val provisionModule = module {
    single { RootfsProvisioner(androidApplication(), get(named<AppCoroutineScope>()), get<AppSettingsManager>()) }
    single {
        RuntimeAutoUpdater(
            app = androidApplication(),
            scope = get(named<AppCoroutineScope>()),
            settings = get(),
            provisioner = get(),
            prootHost = get(),
        )
    }
}
