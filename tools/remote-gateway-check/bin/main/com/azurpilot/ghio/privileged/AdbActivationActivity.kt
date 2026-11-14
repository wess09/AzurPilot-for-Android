package com.azurpilot.ghio.privileged

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.azurpilot.ghio.R
import com.azurpilot.ghio.config.UserConfigurationStore
import com.azurpilot.ghio.domain.ThemeMode
import com.azurpilot.ghio.domain.UserConfiguration
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.AzurPilotTheme
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 承载内置无线调试配对与启动；只有主线程 UI 可发起系统设置和配对通知。
 *
 * Hosts embedded wireless debugging pairing and activation. Main-thread UI starts system
 * settings and the pairing notification. Network operations belong to [EmbeddedAdbManager].
 */
class AdbActivationActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startPairingNotification() else Toast.makeText(
            this, R.string.adb_notification_denied, Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * 显示跟随应用外观的启动页；屏幕重建不会保存配对码。
     *
     * Displays activation with the app's selected appearance without persisting the pairing code.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val manager: AppSettingsManager = koinInject()
            val userConfiguration: UserConfigurationStore = koinInject()
            val appearance by manager.appearance.collectAsStateWithLifecycle()
            val settings by userConfiguration.data.collectAsStateWithLifecycle(UserConfiguration())
            val dark = when (appearance.colorMode.toIntOrNull()) {
                1, 4 -> false
                2, 5, 6 -> true
                0, 3 -> isSystemInDarkTheme()
                else -> when (settings.themeMode) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                    ) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                    ) { dark },
                )
                if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            }
            AzurPilotTheme(darkTheme = dark) {
                val status by EmbeddedAdbManager.status.collectAsStateWithLifecycle()
                AdbActivationPage(
                    status = status,
                    onActivate = { port ->
                        lifecycleScope.launch { EmbeddedAdbManager.activate(manualPort = port) }
                    },
                    onPair = { port, code ->
                        lifecycleScope.launch { EmbeddedAdbManager.pair(port, code) }
                    },
                    onNotificationPairing = ::requestPairingNotification,
                    onOpenSettings = ::openDeveloperSettings,
                    onBack = ::finish,
                )
            }
        }
    }

    private fun requestPairingNotification() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startPairingNotification()
    }

    private fun startPairingNotification() {
        ContextCompat.startForegroundService(this, Intent(this, AdbPairingService::class.java))
        openDeveloperSettings()
    }

    private fun openDeveloperSettings() {
        runCatching { startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { Toast.makeText(this, R.string.adb_settings_failed, Toast.LENGTH_LONG).show() }
    }
}
