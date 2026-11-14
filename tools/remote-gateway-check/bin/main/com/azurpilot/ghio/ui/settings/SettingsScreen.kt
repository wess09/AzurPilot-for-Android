package com.azurpilot.ghio.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.R
import com.azurpilot.ghio.auth.AppLockManager
import com.azurpilot.ghio.constant.ProjectLinks
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.domain.license.LicenseRepository
import com.azurpilot.ghio.i18n.AppLocales
import com.azurpilot.ghio.keepalive.KeepAliveManager
import com.azurpilot.ghio.proot.AzurPilotGateway
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.provision.RootfsProvisioner
import com.azurpilot.ghio.service.AccessibilityHelperService
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.settings.SettingsIntent
import com.azurpilot.ghio.settings.SettingsUiState
import com.azurpilot.ghio.theme.AppTokens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.azurpilot.ghio.ui.components.AppCard
import com.azurpilot.ghio.ui.components.AppFieldLabel
import com.azurpilot.ghio.ui.components.AppInfoRow
import com.azurpilot.ghio.ui.components.AppLabeledControlRow
import com.azurpilot.ghio.ui.components.AppNavigationRow
import com.azurpilot.ghio.ui.components.AppSingleChoiceFlow
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.ui.components.AppSymbolIcon
import com.azurpilot.ghio.ui.components.AppActionLabel
import com.azurpilot.ghio.ui.components.MirrorSourcePicker
import com.azurpilot.ghio.ui.components.ScreenSaverSettingsCard
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppDialog as AlertDialog
import com.azurpilot.ghio.ui.ksu.AppSlider as Slider
import com.azurpilot.ghio.ui.ksu.AppSwitch as Switch
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton
import com.azurpilot.ghio.ui.ksu.AppTextField as OutlinedTextField
import com.azurpilot.ghio.ui.navigation.AppRoute
import com.azurpilot.ghio.update.AppUpdateManager
import com.azurpilot.ghio.update.ReleaseUrls
import com.azurpilot.ghio.widget.AzurPilotControlWidgetReceiver
import com.azurpilot.ghio.widget.AzurPilotQuickWidgetReceiver
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import kotlin.math.round
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * 设置主页的分类入口：一行一个二级页
 *
 * Category entries of the settings hub: one row per second-level page.
 */
enum class SettingsSection(val route: AppRoute, val titleRes: Int, val descRes: Int) {
    Appearance(
        AppRoute.Appearance,
        R.string.ksu_settings_theme,
        R.string.settings_cat_display_desc,
    ),
    Display(AppRoute.Display, R.string.settings_cat_display, R.string.settings_cat_display_desc),
    VirtualDisplay(
        AppRoute.VirtualDisplay,
        R.string.settings_cat_screen,
        R.string.settings_cat_screen_desc,
    ),
    Logs(AppRoute.Logs, R.string.settings_cat_logs, R.string.settings_cat_logs_desc),
    KeepAlive(
        AppRoute.KeepAlive,
        R.string.settings_cat_keep_alive,
        R.string.settings_cat_keep_alive_desc,
    ),
    Advanced(
        AppRoute.Advanced,
        R.string.settings_cat_advanced,
        R.string.settings_cat_advanced_desc,
    ),
    Widget(AppRoute.Widget, R.string.settings_cat_widget, R.string.settings_cat_widget_desc),
    Runtime(AppRoute.Runtime, R.string.settings_cat_runtime, R.string.settings_cat_runtime_desc),
    Ocr(AppRoute.Ocr, R.string.ocr_title, R.string.ocr_description),
    DeviceReport(
        AppRoute.DeviceReport,
        R.string.device_report_title,
        R.string.device_report_description,
    ),
    About(AppRoute.About, R.string.settings_cat_about, R.string.settings_cat_about_desc),
    ;

    /** 分类对应的语义图标。 / Semantic icon for this category. */
    val icon: ImageVector
        get() = when (this) {
            Appearance -> Icons.Outlined.Palette
            Display -> Icons.Outlined.BrightnessMedium
            VirtualDisplay -> Icons.Outlined.ScreenshotMonitor
            Logs -> Icons.AutoMirrored.Outlined.Article
            KeepAlive -> Icons.Outlined.Shield
            Advanced -> Icons.Outlined.Security
            Widget -> Icons.Outlined.Widgets
            Runtime -> Icons.Outlined.Terminal
            Ocr -> Icons.Outlined.DocumentScanner
            DeviceReport -> Icons.Outlined.PermDeviceInformation
            About -> Icons.Outlined.Info
        }
}

/**
 * 设置主页：只列分类入口，内容在各二级页
 *
 * 与旧版单页平铺的差异：每类设置推入独立路由（[SettingsSection]），返回由根导航负责
 *
 * Renders the settings hub: category entries only; the content lives in the second-level pages.
 *
 * Difference from the old single-page layout: each category pushes its own route
 * ([SettingsSection]) and back navigation belongs to the root navigator.
 *
 * @param onOpenSection 点分类行时回传对应入口 / invoked with the tapped section
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onOpenSection: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onOpenOobe: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val manager: AppSettingsManager = koinInject()
    val appearance by manager.appearance.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    com.azurpilot.ghio.ui.ksu.AppPageScaffold(
        title = stringResource(R.string.nav_settings),
        modifier = modifier,
        onBack = onBack,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = bottomInnerPadding + 13.dp,
                ),
        ) {
            item(key = "uiMode", contentType = "uiMode") {
                com.azurpilot.ghio.ui.ksu.AppLazySettingsItem(
                    index = 0,
                    count = SettingsSection.entries.size + 1,
                ) {
                    com.azurpilot.ghio.ui.ksu.AppDropdownPreference(
                        title = stringResource(R.string.ksu_settings_ui_mode),
                        icon = Icons.Outlined.Style,
                        items = listOf("Miuix", "Material"),
                        selectedIndex = if (appearance.uiMode == "miuix") 0 else 1,
                        onSelectedIndexChange = { index ->
                            scope.launch {
                                manager.setAppearance {
                                    it.copy(uiMode = if (index == 0) "miuix" else "material")
                                }
                            }
                        },
                    )
                }
            }
            itemsIndexed(
                SettingsSection.entries,
                key = { _, section -> section.name },
                contentType = { _, _ -> "settingsNavigation" },
            ) { index, section ->
                com.azurpilot.ghio.ui.ksu.AppLazySettingsItem(
                    index = index + 1,
                    count = SettingsSection.entries.size + 1,
                ) {
                    AppNavigationRow(
                        label = stringResource(section.titleRes),
                        icon = section.icon,
                        description = stringResource(section.descRes),
                        onClick = { onOpenSection(section) },
                    )
                }
            }
            item(key = "feedback", contentType = "feedback") {
                com.azurpilot.ghio.ui.ksu.AppSettingsGroup(
                    modifier = Modifier.padding(top = 13.dp)
                ) {
                    item {
                        AppNavigationRow(
                            label = stringResource(R.string.settings_feedback_bug),
                            description = stringResource(R.string.settings_feedback_bug_desc),
                            onClick = { uriHandler.openUri(ProjectLinks.ISSUES) },
                            icon = Icons.Outlined.BugReport,
                        )
                    }
                    item {
                        AppNavigationRow(
                            label = stringResource(R.string.settings_feedback_group),
                            description = stringResource(R.string.settings_feedback_group_desc),
                            onClick = { uriHandler.openUri(ProjectLinks.QQ_FEEDBACK_GROUP) },
                            icon = Icons.Outlined.Forum,
                        )
                    }
                    if (onOpenOobe != null) {
                        item {
                            AppNavigationRow(
                                label = stringResource(R.string.settings_reopen_oobe),
                                description = stringResource(R.string.settings_reopen_oobe_summary),
                                onClick = onOpenOobe,
                                icon = Icons.Outlined.AutoAwesome,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 渲染设置二级页骨架：返回栏 + 标题 + 滚动或填充内容
 *
 * 二级页独立背景与固定尺寸；普通设置导航不执行全屏共享容器形变。
 *
 * Renders the settings sub-page skeleton: back bar + title + scrolling or fill content.
 *
 * Stable detail surface without full-screen container-morph remeasurement.
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
    com.azurpilot.ghio.ui.ksu.AppPageScaffold(
        title = title,
        onBack = onBack,
        modifier = modifier,
    ) { padding ->
        val body = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        Column(
            modifier =
                (if (scrollable) body.verticalScroll(rememberScrollState()) else body).padding(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content,
        )
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
 * @param onIntent 设置意图回调，交 ViewModel 落盘 / settings intent callback, persisted by the view model
 * @param onBack 返回回调 / back callback
 */
@Composable
fun DisplaySettingsPage(
    onOpenAppearance: () -> Unit,
    state: SettingsUiState,
    onIntent: (SettingsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsSubPage(
        titleRes = R.string.settings_cat_display,
        onBack = onBack,
        modifier = modifier,
    ) {
        com.azurpilot.ghio.ui.ksu.AppSettingsGroup {
            item {
                AppNavigationRow(
                    label = stringResource(R.string.ksu_settings_theme),
                    onClick = onOpenAppearance,
                    icon = Icons.Outlined.Palette,
                )
            }
        }
        AppCard {
            LanguageChoice(onIntent)
        }
        ScreenSaverSettingsCard()
    }
}

/**
 * 渲染语言单选；档位与平台 per-app locale 的回显映射见行内注释
 *
 * Renders the language choice; see the inline notes for how options map back to the platform's
 * per-app locale on re-composition.
 */
@Composable
private fun ColumnScope.LanguageChoice(onIntent: (SettingsIntent) -> Unit) {
    // 事实来源在平台侧 per-app locale（AppLocales），不进 UserConfiguration；
    // 切换后 Activity 重建，本处在新组合中重新读取，无需观察流
    // 语言名按惯例保持本族语原文，不随界面语言翻译
    val options =
        listOf<Pair<String?, String>>(
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
                    val hant =
                        tag.contains("hant") ||
                            tag.endsWith("-tw") ||
                            tag.endsWith("-hk") ||
                            tag.endsWith("-mo")
                    if (hant) "zh-TW" else "zh-CN"
                } else {
                    // 档位外的语言（ko-KR 等）在资源层落到 values/ 那份简中，回显与之一致
                    options.firstOrNull { it.first == language }?.first ?: "zh-CN"
                }
            }
        )
    }
    AppSingleChoiceFlow(
        title = stringResource(R.string.settings_language),
        options = options,
        selected = selectedTag,
        // 重复点选当前档位不发 Intent：避免无意义的 Activity 重建闪屏
        onSelect = { tag ->
            if (tag != selectedTag) {
                selectedTag = tag
                onIntent(SettingsIntent.SetLanguage(tag))
            }
        },
        icon = Icons.Outlined.Translate,
    )
    Text(
        text = stringResource(R.string.settings_language_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 渲染虚拟屏二级页：固定 720P、自定义 DPI 和刷新率，下次启动生效。
 *
 * 滑杆经 round 取整只落整数档；「最大档存 0」的约定见行内注释。
 *
 * Renders the virtual-display page with fixed 720p, custom DPI, and refresh rate, applied on next
 * start. The refresh-rate ceiling follows the main display.
 *
 * The slider rounds to whole-number tiers; see the inline notes for the "max tier stored as 0"
 * convention.
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
    var maximum by
        remember(displays) {
            mutableStateOf(displays.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f)
        }
    DisposableEffect(displays) {
        fun updateMaximum() {
            maximum = displays.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        }
        val listener =
            object : DisplayManager.DisplayListener {
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
    val savedDpi by settings.virtualDisplayDpi.collectAsStateWithLifecycle()
    val loaded by settings.loaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val deviceDpi = context.resources.displayMetrics.densityDpi
    var dpiDraft by remember(savedDpi) { mutableStateOf(savedDpi.toString()) }
    var savingDpi by remember { mutableStateOf(false) }
    val draftDpi = dpiDraft.toIntOrNull()?.takeIf { it == 0 || it in 72..960 }
    val dpiSaveFailure = stringResource(R.string.settings_virtual_display_dpi_save_failed)
    fun saveDpi(dpi: Int) {
        savingDpi = true
        scope.launch {
            try {
                settings.setVirtualDisplayDpi(dpi)
                dpiDraft = dpi.toString()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toast.makeText(context, dpiSaveFailure, Toast.LENGTH_SHORT).show()
            } finally {
                savingDpi = false
            }
        }
    }
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val available = maximum.isFinite() && maximum > 1f
    val upper = if (available) maximum else 60f
    var selected by
        remember(savedRate, upper) {
            mutableStateOf(if (savedRate == 0f) upper else savedRate.coerceIn(1f, upper))
        }
    SettingsSubPage(
        titleRes = R.string.settings_cat_screen,
        onBack = onBack,
        modifier = modifier,
    ) {
        AppCard {
            AppInfoRow(
                stringResource(R.string.settings_virtual_display_resolution),
                "1280 × 720",
                icon = Icons.Outlined.AspectRatio,
            )
            AppInfoRow(
                stringResource(R.string.settings_virtual_display_device_dpi),
                stringResource(R.string.settings_virtual_display_dpi_value, deviceDpi),
                icon = Icons.Outlined.DensityMedium,
            )
            OutlinedTextField(
                value = dpiDraft,
                onValueChange = { dpiDraft = it },
                labelText = stringResource(R.string.settings_virtual_display_dpi),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = loaded && !savingDpi,
                isError = draftDpi == null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (draftDpi == null) {
                Text(
                    stringResource(R.string.settings_virtual_display_dpi_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
                TextButton(
                    enabled = loaded && !savingDpi && draftDpi != null && draftDpi != savedDpi,
                    onClick = { draftDpi?.let(::saveDpi) },
                ) { AppActionLabel(AppSymbol.Save, stringResource(R.string.ap_deploy_save)) }
                TextButton(
                    enabled = loaded && !savingDpi && (savedDpi != 0 || dpiDraft != "0"),
                    onClick = { saveDpi(0) },
                ) { AppActionLabel(AppSymbol.Refresh, stringResource(R.string.settings_virtual_display_dpi_auto)) }
            }
            Text(
                stringResource(R.string.settings_virtual_display_dpi_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppCard {
            if (supported) {
                AppInfoRow(
                    stringResource(R.string.settings_virtual_display_rate_requested),
                    stringResource(R.string.settings_virtual_display_rate_value, selected),
                    icon = Icons.Outlined.Speed,
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
                        icon = Icons.Outlined.Speed,
                    )
                }
                Text(
                    stringResource(
                        if (available) R.string.settings_virtual_display_rate_hint
                        else R.string.settings_virtual_display_rate_unavailable
                    ),
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
 * The first four items all leave this page; only auto-clean is an in-place switch, and turning it
 * off goes through a confirm dialog (storage-growth warning).
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
    SettingsSubPage(
        titleRes = R.string.settings_cat_logs,
        onBack = onBack,
        modifier = modifier,
    ) {
        AppCard {
            Box(modifier = Modifier.fillMaxWidth()) {
                AppNavigationRow(
                    label = stringResource(R.string.app_log_title),
                    description = stringResource(R.string.settings_log_launcher_desc),
                    onClick = onOpenAppLog,
                    icon = Icons.AutoMirrored.Outlined.Article,
                )
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                AppNavigationRow(
                    label = stringResource(R.string.azurpilot_log_title),
                    description = stringResource(R.string.settings_log_azurpilot_desc),
                    onClick = onOpenRunnerLog,
                    icon = Icons.Outlined.Terminal,
                )
            }
            AppNavigationRow(
                label = stringResource(R.string.log_export_azurpilot_title),
                description = stringResource(R.string.settings_log_export_azurpilot_desc),
                onClick = onExportRunnerLogs,
                icon = Icons.Outlined.FileDownload,
            )
            AppNavigationRow(
                label = stringResource(R.string.log_export_launcher_title),
                description = stringResource(R.string.settings_log_export_launcher_desc),
                onClick = onExportLauncherLogs,
                icon = Icons.Outlined.FileDownload,
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
                icon = Icons.Outlined.DeleteSweep,
                iconActive = state.autoCleanLogs,
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
                TextButton(
                    onClick = {
                        showDisableConfirm = false
                        onIntent(SettingsIntent.SetAutoCleanLogs(false))
                    }
                ) {
                    Text(stringResource(R.string.dialog_confirm))
                }
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
 * Renders the keep-alive page: the master switch plus per-mechanism status for the seven keep-alive
 * means.
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
    val isAccessibilityConnected by
        keepAliveManager.isAccessibilityConnected.collectAsStateWithLifecycle()
    val hasOverlayPermission =
        remember(state.keepAliveEnabled) {
            Settings.canDrawOverlays(context)
        }
    val hasAccessibility =
        remember(state.keepAliveEnabled, isAccessibilityConnected) {
            AccessibilityHelperService.isServiceEnabled(context) || isAccessibilityConnected
        }

    SettingsSubPage(
        titleRes = R.string.settings_cat_keep_alive,
        onBack = onBack,
        modifier = modifier,
    ) {
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
                icon = Icons.Outlined.Shield,
                iconActive = state.keepAliveEnabled,
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
                value =
                    stringResource(
                        if (state.keepAliveEnabled && isAudioPlaying) {
                            R.string.settings_keepalive_status_active
                        } else {
                            R.string.settings_keepalive_status_inactive
                        }
                    ),
                icon = Icons.Outlined.MusicNote,
            )
            Text(
                text = stringResource(R.string.settings_keepalive_audio_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 2. 前台 1px 微型浮窗像素
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_pixel),
                value =
                    stringResource(
                        when {
                            !state.keepAliveEnabled -> R.string.settings_keepalive_status_inactive
                            isOverlayAttached -> R.string.settings_keepalive_status_active
                            !hasOverlayPermission ->
                                R.string.settings_keepalive_status_need_permission
                            else -> R.string.settings_keepalive_status_inactive
                        }
                    ),
                icon = Icons.Outlined.Layers,
            )
            Text(
                text = stringResource(R.string.settings_keepalive_pixel_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 3. CPU 防休眠唤醒锁 (WakeLock)
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_wakelock),
                value =
                    stringResource(
                        if (state.keepAliveEnabled && isWakeLockHeld) {
                            R.string.settings_keepalive_status_active
                        } else {
                            R.string.settings_keepalive_status_inactive
                        }
                    ),
                icon = Icons.Outlined.Bolt,
            )
            Text(
                text = stringResource(R.string.settings_keepalive_wakelock_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 4. 系统定时作业与精准闹钟 (JobScheduler & AlarmManager)
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_alarm_job),
                value =
                    stringResource(
                        if (state.keepAliveEnabled) R.string.settings_keepalive_status_active
                        else R.string.settings_keepalive_status_inactive
                    ),
                icon = Icons.Outlined.Schedule,
            )
            Text(
                text = stringResource(R.string.settings_keepalive_alarm_job_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 5. 双进程互保与系统广播监听
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_daemon_broadcast),
                value =
                    stringResource(
                        if (state.keepAliveEnabled) R.string.settings_keepalive_status_active
                        else R.string.settings_keepalive_status_inactive
                    ),
                icon = Icons.Outlined.Sync,
            )
            Text(
                text = stringResource(R.string.settings_keepalive_daemon_broadcast_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 6. 伴侣设备服务
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_companion),
                value =
                    stringResource(
                        if (state.keepAliveEnabled) R.string.settings_keepalive_status_active
                        else R.string.settings_keepalive_status_inactive
                    ),
                icon = Icons.Outlined.DevicesOther,
            )
            Text(
                text = stringResource(R.string.settings_keepalive_companion_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 7. 无障碍守护联动
            AppInfoRow(
                label = stringResource(R.string.settings_keepalive_accessibility),
                value =
                    stringResource(
                        when {
                            !state.keepAliveEnabled -> R.string.settings_keepalive_status_inactive
                            isAccessibilityConnected || hasAccessibility ->
                                R.string.settings_keepalive_status_active
                            else -> R.string.settings_keepalive_status_need_accessibility
                        }
                    ),
                icon = Icons.Outlined.AccessibilityNew,
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
    val permissionManager: com.azurpilot.ghio.privileged.PermissionManager = koinInject()
    val activity = context as? FragmentActivity
    val canAuthenticate = appLockManager.canAuthenticate(context)

    SettingsSubPage(
        titleRes = R.string.settings_cat_advanced,
        onBack = onBack,
        modifier = modifier,
    ) {
        AppCard {
            AppSingleChoiceFlow(
                title = stringResource(R.string.permission_backend),
                // 只列后端名，不展示「可用/不可用」——选哪个都行，可用性交给连接流程判
                options = RemoteBackend.entries.map { it to it.display },
                selected = state.remoteAccess.configuredBackend,
                onSelect = { onIntent(SettingsIntent.SetBackend(it)) },
                icon = Icons.Outlined.AdminPanelSettings,
            )
            if (state.remoteAccess.configuredBackend == RemoteBackend.SHIZUKU) {
                TextButton(onClick = { permissionManager.openShizuku(context) }) {
                    AppActionLabel(AppSymbol.Security, stringResource(R.string.adb_title))
                }
            }
        }
        AppCard {
            AppLabeledControlRow(
                label = stringResource(R.string.settings_app_lock_title),
                trailing = {
                    Switch(
                        checked = state.appLockEnabled,
                        enabled = state.appLockEnabled || canAuthenticate,
                        onCheckedChange = { targetEnabled ->
                            if (!targetEnabled && canAuthenticate) {
                                // 关闭保护前需要进行系统锁身份确认
                                activity?.let { act ->
                                    appLockManager.authenticate(
                                        activity = act,
                                        title =
                                            context.getString(R.string.auth_prompt_title_disable),
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
                icon = Icons.Outlined.Lock,
                iconActive = state.appLockEnabled,
            )
            Text(
                text =
                    stringResource(R.string.settings_app_lock_desc) +
                        if (!canAuthenticate)
                            " " + stringResource(R.string.settings_app_lock_no_lock_hint)
                        else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 渲染桌面小组件固定添加二级页：走系统的 pin 请求把两颗小组件挂上桌面
 *
 * Renders the widget pin page: asks the system to pin the two widgets to the home screen.
 */
@Composable
fun WidgetSettingsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appWidgetManager = remember(context) { AppWidgetManager.getInstance(context) }
    val supported =
        remember(appWidgetManager) {
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
            val msgRes =
                if (success) R.string.widget_pin_success else R.string.widget_pin_unsupported
            Toast.makeText(context, msgRes, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.widget_pin_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    SettingsSubPage(
        titleRes = R.string.settings_cat_widget,
        onBack = onBack,
        modifier = modifier,
    ) {
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
                icon = Icons.Outlined.Widgets,
            )
            AppNavigationRow(
                label = stringResource(R.string.widget_pin_quick),
                description = stringResource(R.string.widget_quick_description),
                onClick = { pinWidget(AzurPilotQuickWidgetReceiver::class.java) },
                icon = Icons.Outlined.TouchApp,
            )
        }
    }
}

/**
 * 渲染关于二级页：版本信息、许可证与仓库链接、第三方开源组件清单
 *
 * Renders the about page: version info, license and repository links, and the third-party
 * open-source component list.
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
    onOpenOobe: (() -> Unit)? = null,
    licenseRepository: LicenseRepository = koinInject(),
) {
    val uriHandler = LocalUriHandler.current
    val components by produceState(emptyList<com.azurpilot.ghio.domain.license.OpenSourceComponent>(), licenseRepository) {
        value = withContext(AppDispatchers.IO) { licenseRepository.getAllComponents() }
    }
    val links =
        buildList {
            add(
                com.azurpilot.ghio.ui.ksu.screen.about.LinkInfo(
                    stringResource(R.string.settings_about_license),
                    "license:alas-aos",
                    icon = Icons.Outlined.Gavel,
                )
            )
            add(
                com.azurpilot.ghio.ui.ksu.screen.about.LinkInfo(
                    stringResource(R.string.settings_about_repository),
                    "https://github.com/wess09/AzurPilot-for-Android",
                    icon = Icons.Outlined.Code,
                )
            )
            add(
                com.azurpilot.ghio.ui.ksu.screen.about.LinkInfo(
                    stringResource(R.string.settings_about_source_project),
                    "https://github.com/Shinarin/ALAS-AOS",
                    icon = Icons.Outlined.ForkRight,
                )
            )
            add(
                com.azurpilot.ghio.ui.ksu.screen.about.LinkInfo(
                    stringResource(R.string.settings_open_source_licenses) + " (${components.size})",
                    "licenses:",
                    icon = Icons.Outlined.Description,
                )
            )
            if (onOpenOobe != null) {
                add(
                    com.azurpilot.ghio.ui.ksu.screen.about.LinkInfo(
                        stringResource(R.string.settings_reopen_oobe),
                        "oobe:",
                        icon = Icons.Outlined.AutoAwesome,
                    )
                )
            }
        }
    val state =
        com.azurpilot.ghio.ui.ksu.screen.about.AboutUiState(
            title = stringResource(R.string.settings_cat_about),
            appName = stringResource(R.string.app_name),
            versionName = BuildConfig.VERSION_NAME + " (${BuildConfig.VERSION_CODE})",
            links = links,
        )
    val actions =
        com.azurpilot.ghio.ui.ksu.screen.about.AboutScreenActions(
            onBack = onBack,
            onOpenLink = { url ->
                when {
                    url == "oobe:" -> onOpenOobe?.invoke()
                    url == "licenses:" -> onOpenLicenses()
                    url.startsWith("license:") -> onOpenLicenseDetail(url.removePrefix("license:"))
                    else -> uriHandler.openUri(url)
                }
            },
        )
    when (com.azurpilot.ghio.theme.ksu.LocalUiMode.current) {
        com.azurpilot.ghio.theme.ksu.UiMode.Miuix ->
            com.azurpilot.ghio.ui.ksu.screen.about.AboutScreenMiuix(state, actions)
        com.azurpilot.ghio.theme.ksu.UiMode.Material ->
            com.azurpilot.ghio.ui.ksu.screen.about.AboutScreenMaterial(state, actions)
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
 * Renders the runtime page: version info, hot update and mirror, LAN control, remote access, and
 * the session restart.
 *
 * @param onBack 返回回调 / back callback
 */
@Composable
fun RuntimeSettingsPage(
    onBack: () -> Unit,
    onRemoteConnected: () -> Unit = {},
    modifier: Modifier = Modifier,
    repository: AzurPilotRepository = koinInject(),
    provisioner: RootfsProvisioner = koinInject(),
    updateManager: AppUpdateManager = koinInject(),
    settings: AppSettingsManager = koinInject(),
    runController: AzurPilotRunController = koinInject(),
    prootHost: ProotHost = koinInject(),
    gateway: AzurPilotGateway = koinInject(),
) {
    val remoteName by repository.remoteName.collectAsStateWithLifecycle()
    if (remoteName != null) {
        SettingsSubPage(titleRes = R.string.settings_cat_runtime, onBack = onBack, modifier = modifier) {
            com.azurpilot.ghio.remoteaccess.RemotePairingCard(onConnected = onRemoteConnected)
        }
        return
    }
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
    val installedVersion = runtimeCheck.installedVersion
    // 会话正处在准备/启动链上即视为忙：重启按钮置灰并切到「正在重启」文案
    val sessionBusy =
        prootState.phase == ProotPhase.PREPARING ||
            prootState.phase == ProotPhase.UPDATING ||
            prootState.phase == ProotPhase.STARTING
    // 地址在 Runtime 启动后刷新，端口与当前运行的会话保持一致。
    // 两者都以 phase 为 key：重启完成状态一变就重算，不用退出页面重进
    val lanAddress by
        produceState<String?>(null, lanControlEnabled, prootState.phase) {
            value = if (!lanControlEnabled) null else withContext(AppDispatchers.IO) {
                runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
                Collections.list(interfaces)
                    .asSequence()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { Collections.list(it.inetAddresses).asSequence() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
                    ?.let { "${if (com.azurpilot.ghio.proot.RuntimeConnection.endpoint.tls) "https" else "http"}://$it:${com.azurpilot.ghio.proot.RuntimeConnection.endpoint.port}" }
            }
                .getOrNull()
            }
        }
    val remoteAccessEnabled by settings.remoteAccessEnabled.collectAsStateWithLifecycle()
    val deploySettings by repository.deploySettings.collectAsStateWithLifecycle()
    val connected by repository.connected.collectAsStateWithLifecycle()
    // 旧版 settings.get 会隐藏口令，直接用 Runtime 的 YAML 解析器读取权威配置。
    val webUiPasswordState by produceState<String?>(null, prootState.phase, deploySettings) {
        value = prootHost.readWebUiPassword()
    }
    val webUiPassword = webUiPasswordState
    val remotePassword = webUiPassword
    var remoteStatus by remember { mutableStateOf<RemoteAccessUiState?>(null) }
    LaunchedEffect(connected, prootState.phase) {
        if (connected) repository.loadDeploySettings()
    }
    // 注册晚于网关连接，持续刷新才能展示 Tunnel 换址和恢复后的状态。
    LaunchedEffect(remoteAccessEnabled, prootState.phase, connected) {
        if (!remoteAccessEnabled) {
            remoteStatus = null
            return@LaunchedEffect
        }
        while (isActive) {
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
            kotlinx.coroutines.delay(if (remoteStatus?.address?.isNotEmpty() == true) 15_000 else 3_000)
        }
    }
    SettingsSubPage(
        titleRes = R.string.settings_cat_runtime,
        onBack = onBack,
        modifier = modifier,
    ) {
        AppCard {
            AppInfoRow(
                stringResource(R.string.settings_runtime_commit),
                updater?.localHead?.take(12)
                    ?: installedVersion?.substringBefore('-')
                    ?: stringResource(R.string.settings_runtime_unknown),
                icon = Icons.Outlined.Terminal,
            )
            if (installedVersion != null) {
                AppInfoRow(stringResource(R.string.settings_runtime_installed), installedVersion, icon = Icons.Outlined.Terminal)
            }
            runtimeCheck.latestVersion?.let { latest ->
                AppInfoRow(stringResource(R.string.settings_runtime_latest), latest, icon = Icons.Outlined.SystemUpdate)
            }
            if (runtimeCheck.checked) {
                val status =
                    when {
                        runtimeCheck.error != null ->
                            stringResource(
                                R.string.settings_runtime_check_failed,
                                runtimeCheck.error!!,
                            )
                        runtimeCheck.latestVersion != null && !runtimeCheck.available ->
                            stringResource(R.string.settings_runtime_current)
                        runtimeCheck.latestVersion != null && runtimeCheck.commitOnly ->
                            stringResource(R.string.settings_runtime_commit_only)
                        runtimeCheck.latestVersion != null ->
                            stringResource(R.string.settings_runtime_new_version)
                        else -> stringResource(R.string.settings_runtime_unknown)
                    }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.settings_runtime_managed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = provisioner::checkForUpdates,
                enabled =
                    !runtimeCheck.checking &&
                        provisionState is com.azurpilot.ghio.provision.ProvisionState.Ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppActionLabel(
                    AppSymbol.Refresh,
                    stringResource(
                        if (runtimeCheck.checking) R.string.settings_runtime_checking
                        else R.string.settings_runtime_check
                    ),
                    busy = runtimeCheck.checking,
                )
            }
        }
        // 热更卡片：经运行时私有接口更新源码与前端；恢复链（实例停止、
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
                icon = Icons.Outlined.SystemUpdate,
                iconActive = hotUpdateEnabled,
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
                            Text(
                                stringResource(
                                    R.string.settings_auto_update_hour_format,
                                    autoUpdateHour,
                                )
                            )
                        }
                    },
                    icon = Icons.Outlined.Schedule,
                )
                Text(
                    text = stringResource(R.string.settings_auto_update_hour_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 进页面刷一次；之后靠控制器 30 分钟一次的慢频 tick 与应用后的轮询
            LaunchedEffect(Unit) { runController.refreshHotUpdate() }
            val phaseText =
                when (hotUpdate?.phase) {
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
                hot == null ->
                    Text(
                        stringResource(R.string.settings_hot_update_unreachable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                hot.busy ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                    ) {
                        AppSymbolIcon(
                            symbol = if (hot.phase == "git") AppSymbol.Download else AppSymbol.Update,
                            modifier = Modifier.size(AppTokens.IconSize.md),
                            tint = MaterialTheme.colorScheme.primary,
                            loop = true,
                        )
                        Text(
                            if (phaseText != null) {
                                stringResource(R.string.settings_hot_update_updating, phaseText)
                            } else {
                                stringResource(R.string.home_updating_title)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                hot.error.isNotEmpty() ->
                    Text(
                        stringResource(R.string.settings_hot_update_failed, hot.error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                hot.available ->
                    Text(
                        stringResource(
                            R.string.settings_hot_update_available,
                            hot.upstreamHead?.take(12) ?: "",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                !hot.enabled ->
                    Text(
                        stringResource(R.string.settings_hot_update_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                !hot.distReady ->
                    Text(
                        stringResource(R.string.settings_hot_update_wait_build),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else ->
                    Text(
                        stringResource(R.string.settings_hot_update_latest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md)) {
                TextButton(onClick = { runController.refreshHotUpdate() }) {
                    AppActionLabel(AppSymbol.Refresh, stringResource(R.string.settings_hot_update_check))
                }
                Button(
                    onClick = { runController.applyHotUpdate() },
                    enabled = hot != null && hot.available && !hot.busy,
                ) {
                    AppActionLabel(AppSymbol.Update, stringResource(R.string.settings_hot_update_apply))
                }
            }
        }
        // 局域网控制卡片：开关（默认关）+ 局域网地址与访问口令回显。
        // 绑定地址写入部署配置，改动要重启 Runtime 才生效；
        // 口令从部署配置回读，与部署设置页的修改保持一致。
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
                icon = Icons.Outlined.Wifi,
                iconActive = lanControlEnabled,
            )
            Text(
                text = stringResource(R.string.settings_lan_control_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lanControlEnabled) {
                lanAddress?.let {
                    AppInfoRow(stringResource(R.string.settings_lan_control_address), it, icon = Icons.Outlined.Link, copyable = true)
                }
                if (webUiPassword != null) {
                    AppInfoRow(
                        stringResource(R.string.settings_lan_control_password),
                        webUiPassword,
                        icon = Icons.Outlined.VpnKey,
                        copyable = true,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_lan_control_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        com.azurpilot.ghio.remoteaccess.RemotePairingCard(onConnected = onRemoteConnected)
        // 远程访问卡片：与局域网控制同为网络暴露面开关，相邻放置。
        // 模式与开关保存后由 Runtime 重启应用；Quick Tunnel 复用 WebUI 的远程鉴权。
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
                icon = Icons.Outlined.Public,
                iconActive = remoteAccessEnabled,
            )
            Text(
                text = stringResource(R.string.settings_remote_access_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(enabled = connected && !sessionBusy, onClick = {
                repository.patchDeploySettings(mapOf(
                    "RemoteAccessMode" to "cloudflare",
                    "CloudflareRegistry" to com.azurpilot.ghio.remoteaccess.RemotePairings.REGISTRY,
                    "EnableRemoteAccess" to true,
                )) { ok, _ ->
                    if (ok) showRestartConfirm = true
                    else Toast.makeText(context, R.string.remote_pair_failed, Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.remote_pair_try_tunnel)) }
            if (remoteAccessEnabled) {
                val status = remoteStatus
                AppInfoRow(
                    stringResource(R.string.settings_remote_access_state),
                    stringResource(
                        when {
                            status == null -> R.string.settings_remote_state_unreachable
                            status.address.isNotEmpty() -> R.string.settings_remote_state_ready
                            status.state == "stopped" ||
                                status.state == "failed" ||
                                status.state == "dependency_missing" ->
                                R.string.settings_remote_state_stopped
                            else -> R.string.settings_remote_state_waiting
                        }
                    ),
                    icon = Icons.Outlined.Sensors,
                )
                if (status?.address?.isNotEmpty() == true) {
                    AppInfoRow(
                        stringResource(R.string.settings_remote_access_address),
                        status.address,
                        icon = Icons.Outlined.Link,
                        copyable = true,
                    )
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
                    AppInfoRow(
                        stringResource(R.string.settings_remote_access_password),
                        remotePassword,
                        icon = Icons.Outlined.VpnKey,
                        copyable = true,
                    )
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
                AppActionLabel(
                    AppSymbol.Refresh,
                    stringResource(
                        if (sessionBusy) R.string.settings_runtime_restarting
                        else R.string.settings_runtime_restart
                    ),
                    busy = sessionBusy,
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
                var customDraft by
                    remember(githubMirrorCustom) { mutableStateOf(githubMirrorCustom) }
                OutlinedTextField(
                    value = customDraft,
                    onValueChange = { customDraft = it },
                    labelText = stringResource(R.string.settings_mirror_custom_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = customDraft != githubMirrorCustom,
                    onClick = { scope.launch { settings.setGithubMirrorCustom(customDraft) } },
                ) {
                    AppActionLabel(AppSymbol.Save, stringResource(R.string.settings_mirror_custom_save))
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
                AppActionLabel(AppSymbol.Refresh, stringResource(R.string.settings_app_check))
            }
        }
    }
    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text(stringResource(R.string.settings_runtime_restart_title)) },
            text = { Text(stringResource(R.string.settings_runtime_restart_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartConfirm = false
                        prootHost.restart()
                    }
                ) {
                    Text(stringResource(R.string.dialog_confirm))
                }
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
                    options =
                        (0..23).map { hour ->
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
