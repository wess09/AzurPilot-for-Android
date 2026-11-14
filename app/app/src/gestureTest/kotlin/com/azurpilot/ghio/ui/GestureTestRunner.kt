package com.azurpilot.ghio.ui

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.azurpilot.ghio.settings.AppSettingsManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * 启动纯界面测试，不连接设备服务、游戏或已有 PRoot 运行时。
 *
 * Starts UI-only tests without connecting to device services, the game, or an existing PRoot runtime.
 */
class GestureTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onStart() {
        startKoin {
            androidContext(targetContext.applicationContext)
            modules(module { single { AppSettingsManager(targetContext.applicationContext) } })
        }
        super.onStart()
    }
}
