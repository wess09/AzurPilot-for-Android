package com.azurpilot.ghio.di

import com.azurpilot.ghio.overlay.OverlayController
import com.azurpilot.ghio.overlay.OverlayViewModelOwner
import com.azurpilot.ghio.overlay.border.BorderOverlayManager
import com.azurpilot.ghio.overlay.screensaver.ScreenSaverOverlayManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * 悬浮窗链路绑定：边框挂载、悬浮窗 Compose Owner、外壳控制层与后台屏保
 *
 * Overlay bindings: border window mounting, the overlay Compose owner, the
 * shell control layer, and the background screensaver.
 */
val overlayModule = module {
    single { BorderOverlayManager(androidContext()) }
    single { OverlayViewModelOwner() }
    single {
        OverlayController(
            context = androidContext() as android.app.Application,
            hostState = get(),
            appSettings = get(),
            borderOverlayManager = get(),
            viewModelOwner = get(),
            runController = get(),
        )
    }

    single {
        ScreenSaverOverlayManager(
            context = androidContext(),
            hostState = get(),
            appSettings = get(),
            runController = get(),
            repository = get(),
        )
    }
}
