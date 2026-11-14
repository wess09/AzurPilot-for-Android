package com.azurpilot.ghio.di

import com.azurpilot.ghio.log.AzurPilotErrorDetailViewModel
import com.azurpilot.ghio.log.AzurPilotLogViewModel
import com.azurpilot.ghio.log.AppLogViewModel
import com.azurpilot.ghio.log.LogTailViewModel
import com.azurpilot.ghio.settings.SettingsViewModel
import com.azurpilot.ghio.report.DeviceReportViewModel
import com.azurpilot.ghio.ocr.OcrDiagnosticsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * ViewModel 绑定：日志四屏（app 日志、日志尾随、AzurPilot 日志、错误详情）
 * 与设置页
 *
 * ViewModel bindings: the four log screens (app log, log tail, AzurPilot log,
 * error detail) and the settings screen.
 */
val viewModelModule = module {
    viewModelOf(::OcrDiagnosticsViewModel)
    viewModel { DeviceReportViewModel(androidContext(), get(), get(), get()) }
    viewModelOf(::AppLogViewModel)
    viewModelOf(::LogTailViewModel)
    viewModelOf(::AzurPilotLogViewModel)
    viewModelOf(::AzurPilotErrorDetailViewModel)

    viewModel {
        SettingsViewModel(get(), get(), get())
    }
}
