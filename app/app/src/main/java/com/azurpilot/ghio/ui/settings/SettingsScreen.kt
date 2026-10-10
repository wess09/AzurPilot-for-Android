package com.azurpilot.ghio.ui.settings

import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.domain.license.LicenseRepository
import org.koin.compose.koinInject
import androidx.fragment.app.FragmentActivity
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.Alignment
import com.azurpilot.ghio.auth.AppLockManager
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.R
import com.azurpilot.ghio.constant.ProjectLinks
import com.azurpilot.ghio.domain.RemoteBackend
import java.util.Locale
import com.azurpilot.ghio.domain.ThemeMode
import com.azurpilot.ghio.i18n.AppLocales
import com.azurpilot.ghio.keepalive.KeepAliveManager
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.AzurPilotGateway
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import com.azurpilot.ghio.service.AccessibilityHelperService
import com.azurpilot.ghio.settings.SettingsIntent
import com.azurpilot.ghio.settings.SettingsUiState
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.MirrorSourcePicker
import com.azurpilot.ghio.ui.components.ScreenSaverSettingsCard
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.AppFieldLabel
import com.azurpilot.ghio.ui.components.AppInfoRow
import com.azurpilot.ghio.ui.components.AppLabeledControlRow
import com.azurpilot.ghio.ui.components.AppNavigationRow
import com.azurpilot.ghio.ui.components.AppSingleChoiceFlow
import com.azurpilot.ghio.ui.navigation.Routes
import com.azurpilot.ghio.update.ReleaseUrls
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.provision.RootfsProvisioner
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.update.AppUpdateManager
import com.azurpilot.ghio.widget.AzurPilotControlWidgetReceiver
import com.azurpilot.ghio.widget.AzurPilotQuickWidgetReceiver
import android.appwidget.AppWidgetManager
import kotlin.math.round
import kotlinx.coroutines.launch

/**
 * 设置主页的分类入口：一行一个二级页
 *
 * Category entries of the settings hub: one row per second-level page.
 */
enum class SettingsSection(val route: String, val titleRes: Int, val descRes: Int) {
    Display(Routes.SETTINGS_DISPLAY, R.string.settings_cat_display, R.string.settings_cat_display_desc),
    VirtualDisplay(Routes.SETTINGS_VIRTUAL_DISPLAY, R.string.settings_cat_screen, R.string.settings_cat_screen_desc),
    Logs(Routes.SETTINGS_LOGS, R.string.settings_cat_logs, R.string.settings_cat_logs_desc),
    KeepAlive(Routes.SETTINGS_KEEP_ALIVE, R.string.settings_cat_keep_alive, R.string.settings_cat_keep_alive_desc),
    Advanced(Routes.SETTINGS_ADVANCED, R.string.settings_cat_advanced, R.string.settings_cat_advanced_desc),
    Widget(Routes.SETTINGS_WIDGET, R.string.settings_cat_widget, R.string.settings_cat_widget_desc),
    Runtime(Routes.SETTINGS_RUNTIME, R.string.settings_cat_runtime, R.string.settings_cat_runtime_desc),
    Ocr(Routes.SETTINGS_OCR, R.string.ocr_title, R.string.ocr_description),
    DeviceReport(Routes.SETTINGS_DEVICE_REPORT, R.string.device_report_title, R.string.device_report_description),
    About(Routes.SETTINGS_ABOUT, R.string.settings_cat_about, R.string.settings_cat_about_desc),
}

/**
 * 设置主页：只列分类入口，内容在各二级页
 *
 * 与旧版单页平铺的差异：每类设置推入独立路由（[SettingsSection]），返回由 NavHost 负责
 *
 * Renders the settings hub: category entries only; the content lives in the
 * second-level pages.
 *
 * Difference from the old single-page layout: each category pushes its own route
 * ([SettingsSection]) and back navigation is the NavHost's job.
 *
 * @param onOpenSection 点分类行时回传对应入口 / invoked with the tapped section
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onOpenSection: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxSize()) {
        // M3 的顶栏滚动行为：内容滚起来时顶栏换成容器色并抬起
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        TopAppBar(
            title = { Text(stringResource(R.string.nav_settings)) },
            // AppRoot 的 Scaffold 已吃掉状态栏顶部 inset，这里不能再加一次
            windowInsets = WindowInsets(0, 0, 0, 0),
            scrollBehavior = scrollBehavior,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                // nestedScroll 排在 verticalScroll 左边才是滚动节点的父级
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppTokens.Spacing.lg,
                    end = AppTokens.Spacing.lg,
                    top = AppTokens.Spacing.sm,
                    bottom = AppTokens.Spacing.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.lg),
        ) {
            AppCard {
                SettingsSection.entries.forEachIndexed { index, section ->
                    if (index > 0) HorizontalDivider()
                    AppNavigationRow(
                        label = stringResource(section.titleRes),
                        description = stringResource(section.descRes),
                        onClick = { onOpenSection(section) },
                    )
                }
            }
            // 分类入口之外的直达行：反馈走浏览器离开 App，不该混进二级页导航里
            AppCard {
                AppNavigationRow(
                    label = stringResource(R.string.settings_feedback_bug),
                    description = stringResource(R.string.settings_feedback_bug_desc),
                    onClick = { uriHandler.openUri(ProjectLinks.ISSUES) },
                )
                HorizontalDivider()
                AppNavigationRow(
                    label = stringResource(R.string.settings_feedback_group),
                    description = stringResource(R.string.settings_feedback_group_desc),
                    onClick = { uriHandler.openUri(ProjectLinks.QQ_FEEDBACK_GROUP) },
                )
            }
        }
    }
}

/**
 * 渲染设置二级页骨架：返回栏 + 标题 + 滚动或填充内容
 *
 * Renders the settings sub-page skeleton: back bar + title + scrolling or fill content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // NavHost 层没有背景，不铺底色的话下层主页会从顶栏与卡片间隙透出来
            .background(MaterialTheme.colorScheme.background),
    ) {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        TopAppBar(
            title = { Text(title) },
            // 二级页盖在 AppRoot 的 Scaffold 之外，没人替它吃状态栏 inset，顶栏自己处理
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                    )
                }
            },
        )
        if (scrollable) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = AppTokens.Spacing.lg,
                        end = AppTokens.Spacing.lg,
                        top = AppTokens.Spacing.sm,
                        bottom = AppTokens.Spacing.lg,
                    ),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.lg),
                content = content,
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(
                        start = AppTokens.Spacing.lg,
                        end = AppTokens.Spacing.lg,
                        top = AppTokens.Spacing.sm,
                        bottom = AppTokens.Spacing.lg,
                    ),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsSubPage(
    titleRes: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsSubPage(
        title = stringResource(titleRes),
        onBack = onBack,
        modifier = modifier,
        scrollable = scrollable,
        content = content,
    )
}

/**
 * 渲染显示二级页：主题模式与界面语言
 *
 * Renders the display page: theme mode and UI language.
 *
 * @param onIntent 设置意图回调，交 ViewModel 落盘 / settings intent callback,
 *   persisted by the view model
 * @param onBack 返回回调 / back callback
 */
@Composable
fun DisplaySettingsPage(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSubPage(titleRes = R.string.settings_cat_display, onBack = onBack, modifier = modifier) {
        AppCard {
            AppFieldLabel(stringResource(R.string.settings_theme))
            val modes = listOf(
                ThemeMode.System to stringResource(R.string.settings_follow_system),
                ThemeMode.Light to stringResource(R.string.settings_theme_light),
                ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
            )
            AppSingleChoiceFlow(
                options = modes,
                selected = state.themeMode,
                onSelect = { onIntent(SettingsIntent.SetThemeMode(it)) },
            )
        }
        AppCard {
            AppFieldLabel(stringResource(R.string.settings_language))
            LanguageChoice(onIntent)
        }
        ScreenSaverSettingsCard()
    }
}

/**
 * 渲染语言单选；档位与平台 per-app locale 的回显映射见行内注释
 *
 * Renders the language choice; see the inline notes for how options map back to
 * the platform's per-app locale on re-composition.
 */
@Composable
private fun ColumnScope.LanguageChoice(onIntent: (SettingsIntent) -> Unit) {
    // 事实来源在平台侧 per-app locale（AppLocales），不进 UserConfiguration；
    // 切换后 Activity 重建，本处在新组合中重新读取，无需观察流
    // 语言名按惯例保持本族语原文，不随界面语言翻译
    val options = listOf<Pair<String?, String>>(
        null to stringResource(R.string.settings_follow_system),
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en" to "English",
        "ja" to "日本語",
    )
    // 选中态用本地 state 立即回显：切到效果相同的档位（如 跟随系统(中文) ↔ 简体中文）
    // 不触发 Activity 重建，重新读 AppLocales 的时机不会到来
    var selectedTag by remember {
        mutableStateOf(
            AppLocales.currentTag()?.let { rawTag ->
                // 系统侧 per-app locale 可能带地区与脚本（ja-JP、zh-Hant-TW），档位只到语言/脚本粒度
                val tag = rawTag.replace('_', '-').lowercase()
                val language = tag.substringBefore('-')
                // zh 的繁简靠语言子标签分不开：带 Hant 或港澳台地区才是繁体
                if (language == "zh") {
                    val hant = tag.contains("hant") ||
                        tag.endsWith("-tw") || tag.endsWith("-hk") || tag.endsWith("-mo")
                    if (hant) "zh-TW" else "zh-CN"
                } else {
                    // 档位外的语言（ko-KR 等）在资源层落到 values/ 那份简中，回显与之一致
                    options.firstOrNull { it.first == language }?.first ?: "zh-CN"
                }
            },
        )
    }
    AppSingleChoiceFlow(
        options = options,
        selected = selectedTag,
        // 重复点选当前档位不发 Intent：避免无意义的 Activity 重建闪屏
        onSelect = { tag ->
            if (tag != selectedTag) {
                selectedTag = tag
                onIntent(SettingsIntent.SetLanguage(tag))
            }
        },
    )
    Text(
        text = stringResource(R.string.settings_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 渲染虚拟屏二级页：刷新率滑杆，上限跟随主屏当前刷新率
 *
 * 滑杆经 round 取整只落整数档；「最大档存 0」的约定见行内注释。
 *
 * Renders the virtual-display page: a refresh-rate slider whose ceiling follows
 * the main display's current refresh rate.
 *
 * The slider rounds to whole-number tiers; see the inline notes for the "max
 * tier stored as 0" convention.
 *
 * @param onBack 返回回调 / back callback
 */
@Composable
fun VirtualDisplaySettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    settings: AppSettingsManager = koinInject(),
) {
    val context = LocalContext.current
    val displays = remember(context) { context.getSystemService(DisplayManager::class.java) }
    var maximum by remember(displays) {
        mutableStateOf(displays.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f)
    }
    DisposableEffect(displays) {
        fun updateMaximum() {
            maximum = displays.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updateMaximum()
            override fun onDisplayRemoved(displayId: Int) = updateMaximum()
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) updateMaximum()
            }
        }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        updateMaximum()
        onDispose { displays.unregisterDisplayListener(listener) }
    }
    val savedRate by settings.virtualDisplayRefreshRate.collectAsStateWithLifecycle()
    val loaded by settings.loaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val available = maximum.isFinite() && maximum > 1f
    val upper = if (available) maximum else 60f
    var selected by remember(savedRate, upper) {
        mutableStateOf(if (savedRate == 0f) upper else savedRate.coerceIn(1f, upper))
    }
    SettingsSubPage(titleRes = R.string.settings_cat_screen, onBack = onBack, modifier = modifier) {
        AppCard {
            if (supported) {
                AppInfoRow(
                    stringResource(R.string.settings_virtual_display_rate_requested),
                    stringResource(R.string.settings_virtual_display_rate_value, selected),
                )
                Slider(
                    value = selected,
                    onValueChange = { selected = round(it).coerceIn(1f, upper) },
                    onValueChangeFinished = {
                        // 最大档保存为 0，后续启动可继续跟随主屏当前刷新率。
                        val requested = if (selected == upper) 0f else selected
                        scope.launch { settings.setVirtualDisplayRefreshRate(requested) }
                    },
                    valueRange = 1f..upper,
                    enabled = loaded && available,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (available) {
                    AppInfoRow(
                        stringResource(R.string.settings_virtual_display_rate_maximum),
                        stringResource(R.string.settings_virtual_display_rate_value, maximum),
                    )
                }
                Text(
                    stringResource(if (available) R.string.settings_virtual_display_rate_hint
                        else R.string.settings_virtual_display_rate_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.settings_virtual_display_rate_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 日志二级页：两个查看入口 + 两条导出 + 自动清理开关
 *
 * 前四项都是「离开这一页」，只有自动清理是就地开关；关闭走确认弹窗（占空间警告）
 *
 * Renders the logs page: two viewer entries + two exports + the auto-clean toggle.
 *
 * The first four items all leave this page; only auto-clean is an in-place
 * switch, and turning it off goes through a confirm dialog (storage-growth
 * warning).
 */
@Composable
fun LogsSettingsPage(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onOpenAppLog: () -> Unit,
    onOpenRunnerLog: () -> Unit,
    onExportRunnerLogs: () -> Unit,
    onExportLauncherLogs: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDisableConfirm by remember { mutableStateOf(false) }
    SettingsSubPage(titleRes = R.string.settings_cat_logs, onBack = onBack, modifier = modifier) {
        AppCard {
            AppNavigationRow(
                label = stringResource(R.string.app_log_title),
                description = stringResource(R.string.settings_log_launcher_desc),
                onClick = onOpenAppLog,
            )
            AppNavigationRow(
                label = stringResource(R.string.azurpilot_log_title),
                description = stringResource(R.string.settings_log_azurpilot_desc),
                onClick = onOpenRunnerLog,
            )
            AppNavigationRow(
                label = stringResource(R.string.log_export_azurpilot_title),
                description = stringResource(R.string.settings_log_export_azurpilot_desc),
                onClick = onExportRunnerLogs,
            )
            AppNavigationRow(
                label = stringResource(R.string.log_export_launcher_title),
                description = stringResource(R.string.settings_log_export_launcher_desc),
                onClick = onExportLauncherLogs,
            )
        }
        AppCard {
            // 开启直接落盘；关闭先弹确认：关掉之后过期日志只增不减
            AppLabeledControlRow(
                label = stringResource(R.string.settings_auto_clean_logs),
                trailing = {
                    Switch(
                        checked = state.autoCleanLogs,
                        onCheckedChange = { enabled ->
                            if (enabled) onIntent(SettingsIntent.SetAutoCleanLogs(true))
                            else showDisableConfirm = true
                        },
                    )
                },
            )
            Text(
                text = stringResource(R.string.settings_auto_clean_logs_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            title = { Text(stringResource(R.string.dialog_disable_auto_clean_title)) },
            text = { Text(stringResource(R.string.dialog_disable_auto_clean_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDisableConfirm = false
                    onIntent(SettingsIntent.SetAutoCleanLogs(false))
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/**
 * 渲染后台保活二级页：总开关 + 七路保活手段的逐项状态
 *
 * Renders the keep-alive page: the master switch plus per-mechanism status for
 * the seven keep-alive means.
 */
@Composable
fun KeepAliveSettingsPage(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    keepAliveManager: KeepAliveManager = koinInject(),
) {
    val context = LocalContext.current
    val isAudioPlaying by keepAliveManager.isAudioPlaying.collectAsStateWithLifecycle()
    val isOverlayAttached by keepAliveManager.isPixelOverlayAttached.collectAsStateWithLifecycle()
    val isWakeLockHeld by keepAliveManager.isWakeLockHeld.collectAsStateWithLifecycle()
    val isAccessibilityConnected by keepAliveManager.isAccessibilityConnected.collectAsStateWithLifecycle()
    val hasOverlayPermission = remember(state.keepAliveEnabled) {
        Settings.canDrawOverlays(context)
    }
    val hasAccessibility = remember(state.keepAliveEnabled, isAccessibilityConnected) {
        AccessibilityHelperService.isServiceEnabled(context) || isAccessibilityConnected
    }

    SettingsSubPage(titleRes = R.string.settings_cat_keep_alive, onBack = onBack, modifier = modifier) {
        AppCard {
            AppLabeledControlRow(
                label = stringResource(R.string.settings_keepalive_title),
                trailing = {
                    Switch(
                        checked = state.keepAliveEnabled,
                        onCheckedChange = { enabled ->
                            onIntent(SettingsIntent.SetKeepAlive(enabled))
                        },
                    )
                },
            )
            Text(
                text = stringResource(R.string.settings_keepalive_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppCard {
            AppFieldLabel(stringResource(R.string.permission_section))

            // 1. 24小时后台无音量音频
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_audio),
                value = stringResource(
                    if (state.keepAliveEnabled && isAudioPlaying) {
                        R.string.settings_keepalive_status_active
                    } else {
                        R.string.settings_keepalive_status_inactive
                    }
                ),
            )
            Text(
                text = stringResource(R.string.settings_keepalive_audio_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 2. 前台 1px 微型浮窗像素
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_pixel),
                value = stringResource(
                    when {
                        !state.keepAliveEnabled -> R.string.settings_keepalive_status_inactive
                        isOverlayAttached -> R.string.settings_keepalive_status_active
                        !hasOverlayPermission -> R.string.settings_keepalive_status_need_permission
                        else -> R.string.settings_keepalive_status_inactive
                    }
                ),
            )
            Text(
                text = stringResource(R.string.settings_keepalive_pixel_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 3. CPU 防休眠唤醒锁 (WakeLock)
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_wakelock),
                value = stringResource(
                    if (state.keepAliveEnabled && isWakeLockHeld) {
                        R.string.settings_keepalive_status_active
                    } else {
                        R.string.settings_keepalive_status_inactive
                    }
                ),
            )
            Text(
                text = stringResource(R.string.settings_keepalive_wakelock_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 4. 系统定时作业与精准闹钟 (JobScheduler & AlarmManager)
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_alarm_job),
                value = stringResource(
                    if (state.keepAliveEnabled) R.string.settings_keepalive_status_active
                    else R.string.settings_keepalive_status_inactive
                ),
            )
            Text(
                text = stringResource(R.string.settings_keepalive_alarm_job_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 5. 双进程互保与系统广播监听
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_daemon_broadcast),
                value = stringResource(
                    if (state.keepAliveEnabled) R.string.settings_keepalive_status_active
                    else R.string.settings_keepalive_status_inactive
                ),
            )
            Text(
                text = stringResource(R.string.settings_keepalive_daemon_broadcast_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 6. 伴侣设备服务
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_companion),
                value = stringResource(
                    if (state.keepAliveEnabled) R.string.settings_keepalive_status_active
                    else R.string.settings_keepalive_status_inactive
                ),
            )
            Text(
                text = stringResource(R.string.settings_keepalive_companion_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 7. 无障碍守护联动
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_accessibility),
                value = stringResource(
                    when {
                        !state.keepAliveEnabled -> R.string.settings_keepalive_status_inactive
                        isAccessibilityConnected || hasAccessibility -> R.string.settings_keepalive_status_active
                        else -> R.string.settings_keepalive_status_need_accessibility
                    }
                ),
            )
            Text(
                text = stringResource(R.string.settings_keepalive_accessibility_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 渲染运行与安全二级页：提权后端选择 + 应用锁屏保护
 *
 * Renders the advanced page: privileged backend choice + app lock protection.
 */
@Composable
fun AdvancedSettingsPage(
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    appLockManager: AppLockManager = koinInject(),
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val isDeviceSecure = remember(context) { appLockManager.isDeviceSecure(context) }

    SettingsSubPage(titleRes = R.string.settings_cat_advanced, onBack = onBack, modifier = modifier) {
        AppCard {
            AppFieldLabel(stringResource(R.string.permission_backend))
            AppSingleChoiceFlow(
                // 只列后端名，不展示「可用/不可用」——选哪个都行，可用性交给连接流程判
                options = RemoteBackend.entries.map { it to it.display },
                selected = state.remoteAccess.configuredBackend,
                onSelect = { onIntent(SettingsIntent.SetBackend(it)) },
            )
        }
        AppCard {
            AppLabeledControlRow(
                label = stringResource(R.string.settings_app_lock_title),
                trailing = {
                    Switch(
                        checked = state.appLockEnabled,
                        onCheckedChange = { targetEnabled ->
                            if (!targetEnabled && isDeviceSecure) {
                                // 关闭保护前需要进行系统锁身份确认
                                activity?.let { act ->
                                    appLockManager.authenticate(
                                        activity = act,
                                        title = context.getString(R.string.auth_prompt_title_disable),
                                        subtitle = context.getString(R.string.auth_prompt_subtitle),
                                        onSuccess = {
                                            onIntent(SettingsIntent.SetAppLock(false))
                                        },
                                    )
                                }
                            } else {
                                onIntent(SettingsIntent.SetAppLock(targetEnabled))
                            }
                        },
                    )
                },
            )
            Text(
                text = stringResource(R.string.settings_app_lock_desc) +
                    if (!isDeviceSecure) " " + stringResource(R.string.settings_app_lock_no_lock_hint) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 渲染桌面小组件固定添加二级页：走系统的 pin 请求把两颗小组件挂上桌面
 *
 * Renders the widget pin page: asks the system to pin the two widgets to the
 * home screen.
 */
@Composable
fun WidgetSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val supported = remember(appWidgetManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appWidgetManager.isRequestPinAppWidgetSupported
        } else {
            false
        }
    }

    fun pinWidget(providerClass: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supported) {
            val provider = ComponentName(context, providerClass)
            val success = appWidgetManager.requestPinAppWidget(provider, null, null)
            val msgRes = if (success) R.string.widget_pin_success else R.string.widget_pin_unsupported
            Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.widget_pin_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsSubPage(titleRes = R.string.settings_cat_widget, onBack = onBack, modifier = modifier) {
        AppCard {
            Text(
                text = stringResource(R.string.widget_pin_to_home_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppNavigationRow(
                label = stringResource(R.string.widget_pin_control),
                description = stringResource(R.string.widget_control_description),
                onClick = { pinWidget(AzurPilotControlWidgetReceiver::class.java) },
            )
            AppNavigationRow(
                label = stringResource(R.string.widget_pin_quick),
                description = stringResource(R.string.widget_quick_description),
                onClick = { pinWidget(AzurPilotQuickWidgetReceiver::class.java) },
            )
        }
    }
}

/**
 * 渲染关于二级页：版本信息、许可证与仓库链接、第三方开源组件清单
 *
 * Renders the about page: version info, license and repository links, and the
 * third-party open-source component list.
 *
 * @param onOpenLicenses 打开全部开源组件列表回调 / Callback opening full open-source licenses list
 * @param onOpenLicenseDetail 打开特定组件协议原文详情页回调 / Callback opening license detail page
 * @param onBack 返回回调 / back callback
 */
@Composable
fun AboutSettingsPage(
    onOpenLicenses: () -> Unit,
    onOpenLicenseDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    licenseRepository: LicenseRepository = koinInject(),
) {
    val uriHandler = LocalUriHandler.current
    SettingsSubPage(titleRes = R.string.settings_cat_about, onBack = onBack, modifier = modifier) {
        AppCard {
            Text(
                text = stringResource(R.string.settings_about_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            AppInfoRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
            AppInfoRow(stringResource(R.string.settings_build), BuildConfig.VERSION_CODE.toString())
        }
        AppCard {
            AppNavigationRow(
                label = stringResource(R.string.settings_about_license),
                description = "AGPL-3.0",
                onClick = { onOpenLicenseDetail("alas-aos") },
            )
            AppNavigationRow(
                label = stringResource(R.string.settings_about_repository),
                description = "github.com/wess09/AzurPilot-for-Android",
                onClick = { uriHandler.openUri("https://github.com/wess09/AzurPilot-for-Android") },
            )
            AppNavigationRow(
                label = stringResource(R.string.settings_about_source_project),
                description = "github.com/Shinarin/ALAS-AOS",
                onClick = { uriHandler.openUri("https://github.com/Shinarin/ALAS-AOS") },
            )
        }
        AppCard {
            // 开源组件入口行自带标题与计数，不再叠一层区块标题（曾与首行几乎同文重复）
            val allComponents = remember { licenseRepository.getAllComponents() }
            val coreComponents = remember { licenseRepository.getCoreComponents() }

            // 查看完整列表入口（包含组件总数与协议原文提示）
            AppNavigationRow(
                label = stringResource(R.string.settings_open_source_licenses),
                description = stringResource(R.string.settings_about_components_summary, allComponents.size),
                onClick = onOpenLicenses,
            )

            // 核心组件快捷入口：点击直接进入该组件的协议原文
            coreComponents.take(4).forEach { comp ->
                AppNavigationRow(
                    label = comp.name,
                    description = comp.licenseId,
                    onClick = { onOpenLicenseDetail(comp.id) },
                )
            }
        }
    }
}

/**
 * 远程访问状态快照（WS `settings.get` → `remote` 字段）
 *
 * Snapshot of the remote-access state (WS `settings.get` → the `remote` field).
 */
private data class RemoteAccessUiState(
    val state: String = "",
    val address: String = "",
    val error: String = "",
)

/**
 * 渲染运行时二级页：版本信息、热更与镜像、局域网控制、远程访问与会话重启
 *
 * Renders the runtime page: version info, hot update and mirror, LAN control,
 * remote access, and the session restart.
 *
 * @param onBack 返回回调 / back callback
 */
@Composable
fun RuntimeSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: AzurPilotRepository = koinInject(),
    provisioner: RootfsProvisioner = koinInject(),
    updateManager: AppUpdateManager = koinInject(),
    settings: AppSettingsManager = koinInject(),
    runController: AzurPilotRunController = koinInject(),
    prootHost: ProotHost = koinInject(),
    gateway: AzurPilotGateway = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updater by repository.updater.collectAsStateWithLifecycle()
    val provisionState by provisioner.state.collectAsStateWithLifecycle()
    val runtimeCheck by provisioner.updateCheck.collectAsStateWithLifecycle()
    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val githubMirror by settings.githubMirror.collectAsStateWithLifecycle()
    val githubMirrorCustom by settings.githubMirrorCustom.collectAsStateWithLifecycle()
    val hotUpdateEnabled by settings.hotUpdateEnabled.collectAsStateWithLifecycle()
    val autoUpdateHour by settings.autoUpdateHour.collectAsStateWithLifecycle()
    val lanControlEnabled by settings.lanControlEnabled.collectAsStateWithLifecycle()
    val hotUpdate by runController.hotUpdate.collectAsStateWithLifecycle()
    val prootState by prootHost.state.collectAsStateWithLifecycle()
    var showHourDialog by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    val installedVersion = provisioner.installedVersion()
    // 会话正处在准备/启动链上即视为忙：重启按钮置灰并切到「正在重启」文案
    val sessionBusy = prootState.phase == ProotPhase.PREPARING ||
        prootState.phase == ProotPhase.UPDATING ||
        prootState.phase == ProotPhase.STARTING
    // 局域网地址取本机站点内 IPv4；口令是上游公网监听时自动生成的 password.txt。
    // 两者都以 phase 为 key：重启完成状态一变就重算，不用退出页面重进
    val lanAddress = remember(lanControlEnabled, prootState.phase) {
        if (!lanControlEnabled) return@remember null
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@remember null
            Collections.list(interfaces).asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
                ?.let { "http://$it:${ProotHost.WEBUI_PORT}" }
        }.getOrNull()
    }
    val webUiPassword = remember(lanControlEnabled, prootState.phase) {
        if (!lanControlEnabled) return@remember null
        // 与 ProotHost 的 installDir 同约定：guest 的 /opt/azurpilot 映射到 files/rootfs/opt/azurpilot
        runCatching {
            File(context.filesDir, "rootfs/opt/azurpilot/password.txt").readText()
                .trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
    val remoteAccessEnabled by settings.remoteAccessEnabled.collectAsStateWithLifecycle()
    var remoteStatus by remember { mutableStateOf<RemoteAccessUiState?>(null) }
    // 远程入口的访问口令与 WebUI 是同一个（App 在开启时确保非空）；LAN 开启时
    // password.txt 与它一致，这里直接读 deploy.yaml 的权威值
    val remotePassword = remember(remoteAccessEnabled, prootState.phase) {
        if (!remoteAccessEnabled) return@remember null
        runCatching {
            val text = File(context.filesDir, "rootfs/opt/azurpilot/config/deploy.yaml").readText()
            Regex("^\\s*Password:\\s*(\\S.*)$", RegexOption.MULTILINE).find(text)
                ?.groupValues?.get(1)?.substringBefore(" #")?.trim()
                ?.takeUnless { it.isEmpty() || it.equals("null", true) }
        }.getOrNull()
    }
    // 重启完成（phase 变化）后 gateway 重连与隧道注册都要一拍，稍候再取状态
    LaunchedEffect(remoteAccessEnabled, prootState.phase) {
        if (!remoteAccessEnabled) {
            remoteStatus = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(2_000)
        val remote = runCatching {
            gateway.request("settings.get")?.optJSONObject("remote")
        }.getOrNull()
        remoteStatus = remote?.let {
            RemoteAccessUiState(
                state = it.optString("state"),
                address = it.optString("address"),
                error = it.optString("error"),
            )
        }
    }
    SettingsSubPage(titleRes = R.string.settings_cat_runtime, onBack = onBack, modifier = modifier) {
        AppCard {
            AppInfoRow(
                stringResource(R.string.settings_runtime_commit),
                updater?.localHead?.take(12)
                    ?: installedVersion?.substringBefore('-')
                    ?: stringResource(R.string.settings_runtime_unknown),
            )
            if (installedVersion != null) {
                AppInfoRow(stringResource(R.string.settings_runtime_installed), installedVersion)
            }
            runtimeCheck.latestVersion?.let { latest ->
                AppInfoRow(stringResource(R.string.settings_runtime_latest), latest)
            }
            if (runtimeCheck.checked) {
                val status = when {
                    runtimeCheck.error != null -> stringResource(R.string.settings_runtime_check_failed, runtimeCheck.error!!)
                    runtimeCheck.latestVersion == installedVersion -> stringResource(R.string.settings_runtime_current)
                    runtimeCheck.latestVersion != null && runtimeCheck.commitOnly ->
                        stringResource(R.string.settings_runtime_commit_only)
                    runtimeCheck.latestVersion != null -> stringResource(R.string.settings_runtime_new_version)
                    else -> stringResource(R.string.settings_runtime_unknown)
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = stringResource(R.string.settings_runtime_managed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = provisioner::checkForUpdates,
                enabled = !runtimeCheck.checking && provisionState is com.azurpilot.ghio.provision.ProvisionState.Ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (runtimeCheck.checking) R.string.settings_runtime_checking else R.string.settings_runtime_check))
            }
        }
        // 热更卡片：经运行时私有接口增量更新源码与预构建前端；恢复链（实例停止、
        // 依赖同步、重启后拉起）全部由运行时自身的文件协议接管，App 只触发与旁观
        AppCard {
            AppLabeledControlRow(
                label = stringResource(R.string.settings_hot_update),
                trailing = {
                    Switch(
                        checked = hotUpdateEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { settings.setHotUpdateEnabled(enabled) }
                        },
                    )
                },
            )
            Text(
                text = stringResource(R.string.settings_hot_update_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hotUpdateEnabled) {
                AppLabeledControlRow(
                    label = stringResource(R.string.settings_auto_update_hour),
                    trailing = {
                        TextButton(onClick = { showHourDialog = true }) {
                            Text(stringResource(R.string.settings_auto_update_hour_format, autoUpdateHour))
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.settings_auto_update_hour_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 进页面刷一次；之后靠控制器 30 分钟一次的慢频 tick 与应用后的轮询
            LaunchedEffect(Unit) { runController.refreshHotUpdate() }
            val phaseText = when (hotUpdate?.phase) {
                "git" -> stringResource(R.string.settings_hot_update_phase_git)
                "dist" -> stringResource(R.string.settings_hot_update_phase_dist)
                "manifest" -> stringResource(R.string.settings_hot_update_phase_manifest)
                "reload" -> stringResource(R.string.settings_hot_update_phase_reload)
                else -> null
            }
            val hot = hotUpdate
            when {
                // 状态拿不到：运行时没起来或版本太旧没有增量更新接口，「立即更新」按钮
                // 因此置灰——不说清楚用户只会觉得这个功能不存在
                hot == null -> Text(
                    stringResource(R.string.settings_hot_update_unreachable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                hot.busy && phaseText != null -> Text(
                    stringResource(R.string.settings_hot_update_updating, phaseText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                hot.error.isNotEmpty() -> Text(
                    stringResource(R.string.settings_hot_update_failed, hot.error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                hot.available -> Text(
                    stringResource(R.string.settings_hot_update_available, hot.upstreamHead?.take(12) ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !hot.enabled -> Text(
                    stringResource(R.string.settings_hot_update_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !hot.distReady -> Text(
                    stringResource(R.string.settings_hot_update_wait_build),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    stringResource(R.string.settings_hot_update_latest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md)) {
                TextButton(onClick = { runController.refreshHotUpdate() }) {
                    Text(stringResource(R.string.settings_hot_update_check))
                }
                Button(
                    onClick = { runController.applyHotUpdate() },
                    enabled = hot != null && hot.available && !hot.busy,
                ) {
                    Text(stringResource(R.string.settings_hot_update_apply))
                }
            }
        }
        // 局域网控制卡片：开关（默认关）+ 局域网地址与访问口令回显。
        // 绑定地址经会话环境（AZURPILOT_ANDROID_LAN）注入，改动要重启 Runtime 才生效；
        // 口令由上游公网监听时自动生成，非本机连接才需要，App 内嵌界面不受影响
        AppCard {
            AppLabeledControlRow(
                label = stringResource(R.string.settings_lan_control),
                trailing = {
                    Switch(
                        checked = lanControlEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { settings.setLanControlEnabled(enabled) }
                        },
                    )
                },
            )
            Text(
                text = stringResource(R.string.settings_lan_control_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lanControlEnabled) {
                lanAddress?.let {
                    AppInfoRow(stringResource(R.string.settings_lan_control_address), it)
                }
                if (webUiPassword != null) {
                    AppInfoRow(stringResource(R.string.settings_lan_control_password), webUiPassword)
                } else {
                    Text(
                        text = stringResource(R.string.settings_lan_control_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 远程访问卡片：与局域网控制同为网络暴露面开关，相邻放置。
        // 隧道由上游 localshare 中转（SSH 兜底、P2P 升级），改动要重启 Runtime 生效；
        // 口令与 WebUI 同一份，拿到地址+口令即可从公网控制，务必保管好
        AppCard {
            AppLabeledControlRow(
                label = stringResource(R.string.settings_remote_access),
                trailing = {
                    Switch(
                        checked = remoteAccessEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { settings.setRemoteAccessEnabled(enabled) }
                        },
                    )
                },
            )
            Text(
                text = stringResource(R.string.settings_remote_access_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (remoteAccessEnabled) {
                val status = remoteStatus
                AppInfoRow(
                    stringResource(R.string.settings_remote_access_state),
                    stringResource(
                        when {
                            status == null -> R.string.settings_remote_state_unreachable
                            status.address.isNotEmpty() -> R.string.settings_remote_state_ready
                            status.state == "stopped" || status.state == "failed" ||
                                status.state == "dependency_missing" -> R.string.settings_remote_state_stopped
                            else -> R.string.settings_remote_state_waiting
                        }
                    ),
                )
                if (status?.address?.isNotEmpty() == true) {
                    AppInfoRow(stringResource(R.string.settings_remote_access_address), status.address)
                }
                // error 只在没拿到地址时展示：上游 WebRTC provider 首轮等 SSH 回包
                // 超时后重试成功也不清 error 字段，地址已就绪时它是陈旧残留
                val remoteError = status?.error?.takeIf { status.address.isEmpty() }
                if (remoteError?.isNotEmpty() == true) {
                    Text(
                        text = remoteError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (remotePassword != null) {
                    AppInfoRow(stringResource(R.string.settings_remote_access_password), remotePassword)
                }
            }
        }
        // Runtime 重启卡片：改完上面开关/镜像后的生效入口，也是会话卡死时的手动恢复。
        // 重启会先请运行时优雅停掉实例，任务不跨重启恢复，所以先弹确认
        AppCard {
            Button(
                onClick = { showRestartConfirm = true },
                enabled = !sessionBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (sessionBusy) R.string.settings_runtime_restarting
                        else R.string.settings_runtime_restart
                    )
                )
            }
            Text(
                text = stringResource(R.string.settings_runtime_restart_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppCard {
            Text(
                text = stringResource(R.string.settings_github_mirror),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MirrorSourcePicker(
                selected = githubMirror,
                onSelect = { mirror -> scope.launch { settings.setGithubMirror(mirror) } },
            )
            if (githubMirror == ReleaseUrls.CUSTOM) {
                // 草稿以盘上值为准重新同步；点保存才落盘，避免每敲一个字符写一次 DataStore
                var customDraft by remember(githubMirrorCustom) { mutableStateOf(githubMirrorCustom) }
                OutlinedTextField(
                    value = customDraft,
                    onValueChange = { customDraft = it },
                    label = { Text(stringResource(R.string.settings_mirror_custom_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = customDraft != githubMirrorCustom,
                    onClick = { scope.launch { settings.setGithubMirrorCustom(customDraft) } },
                ) {
                    Text(stringResource(R.string.settings_mirror_custom_save))
                }
            }
            Text(
                text = stringResource(R.string.settings_github_mirror_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = updateManager::check,
                enabled = !updateState.downloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_app_check))
            }
        }
    }
    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text(stringResource(R.string.settings_runtime_restart_title)) },
            text = { Text(stringResource(R.string.settings_runtime_restart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestartConfirm = false
                    prootHost.restart()
                }) { Text(stringResource(R.string.dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
    if (showHourDialog) {
        AlertDialog(
            onDismissRequest = { showHourDialog = false },
            title = { Text(stringResource(R.string.settings_auto_update_hour_dialog_title)) },
            text = {
                AppSingleChoiceFlow(
                    options = (0..23).map { hour ->
                        hour to String.format(Locale.US, "%02d:00", hour)
                    },
                    selected = autoUpdateHour,
                    onSelect = { hour ->
                        scope.launch { settings.setAutoUpdateHour(hour) }
                        showHourDialog = false
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { showHourDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
