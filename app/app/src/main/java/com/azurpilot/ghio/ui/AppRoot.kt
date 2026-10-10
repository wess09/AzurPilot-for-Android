package com.azurpilot.ghio.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.runtime.DisposableEffect
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.auth.AppLockManager
import com.azurpilot.ghio.ui.components.AppLockGate
import androidx.navigation.NavType
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.azurpilot.ghio.R
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.constant.ProjectLinks
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.domain.ThemeMode
import com.azurpilot.ghio.log.LogExportKind
import com.azurpilot.ghio.privileged.PermissionManager
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.provision.ProvisionState
import com.azurpilot.ghio.provision.RootfsProvisioner
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.settings.SettingsIntent
import com.azurpilot.ghio.settings.SettingsViewModel
import com.azurpilot.ghio.service.HostState
import com.azurpilot.ghio.theme.AzurPilotTheme
import com.azurpilot.ghio.ui.azurpilot.AzurPilotPage
import com.azurpilot.ghio.ui.components.ShizukuReadinessDialog
import com.azurpilot.ghio.ui.hangar.HangarScreen
import com.azurpilot.ghio.ui.navigation.Routes
import com.azurpilot.ghio.ui.screen.ScreenPage
import com.azurpilot.ghio.ui.shortcut.ShortcutRequests
import com.azurpilot.ghio.ui.logs.AzurPilotErrorDetailScreen
import com.azurpilot.ghio.ui.logs.AzurPilotLogDetailScreen
import com.azurpilot.ghio.ui.logs.AzurPilotLogScreen
import com.azurpilot.ghio.ui.logs.AppLogDetailScreen
import com.azurpilot.ghio.ui.logs.AppLogScreen
import com.azurpilot.ghio.ui.logs.LogExportController
import com.azurpilot.ghio.ui.settings.SettingsScreen
import com.azurpilot.ghio.ui.settings.AdvancedSettingsPage
import com.azurpilot.ghio.ui.settings.AboutSettingsPage
import com.azurpilot.ghio.ui.settings.DisplaySettingsPage
import com.azurpilot.ghio.ui.settings.KeepAliveSettingsPage
import com.azurpilot.ghio.ui.settings.LicenseDetailPage
import com.azurpilot.ghio.ui.settings.LogsSettingsPage
import com.azurpilot.ghio.ui.settings.OpenSourceLicensesPage
import com.azurpilot.ghio.ui.settings.RuntimeSettingsPage
import com.azurpilot.ghio.ui.settings.OcrSettingsPage
import com.azurpilot.ghio.ui.settings.DeviceReportSettingsPage
import com.azurpilot.ghio.ui.settings.VirtualDisplaySettingsPage
import com.azurpilot.ghio.ui.settings.WidgetSettingsPage
import com.azurpilot.ghio.ui.setup.ProvisionScreen
import com.azurpilot.ghio.update.AppUpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * 底部导航的主 tab 枚举：携带标签资源与选中/未选中两态图标
 *
 * 声明顺序即 pager 页序；取值依次对应主页、AzurPilot、设置与虚拟屏四张主页面。
 *
 * The bottom navigation tabs: carries the label resource and the
 * selected/unselected icon pair.
 *
 * Declaration order doubles as the pager page order; the values map to the Hangar,
 * AzurPilot, Settings, and virtual-display main pages.
 */
private enum class TopDestination(
    @param:StringRes val labelRes: Int,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
    // 默认首页：App 一打开就是主页（运行配置 + 控制面 + 日志）
    Hangar(R.string.nav_hangar, Icons.Outlined.PlayCircle, Icons.Filled.PlayCircle),
    AzurPilot(R.string.nav_azurpilot, Icons.Outlined.Public, Icons.Filled.Public),
    Settings(R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
    // 虚拟屏追加在末尾：不动原有三个 tab 的次序，也不让主页的相邻页变成它
    // （pager 会预组合相邻页，主页的邻居只该是 AzurPilot）
    Screen(R.string.nav_screen, Icons.Outlined.PhoneAndroid, Icons.Filled.PhoneAndroid),
}

/**
 * 拉起应用根 Composable：组装主题、应用锁、导航与全局对话框，是整个 UI 树的入口
 *
 * 自外向内分层：收集 [SettingsViewModel]/[PermissionManager]/[RootfsProvisioner]/
 * [AppUpdateManager] 的状态流；[AzurPilotTheme] 供给双风格主题；[AppLockGate] 在应用锁
 * 开启且未解锁时整屏接管；rootfs 未 Ready 时 [ProvisionScreen] 整屏接管；主 tab 由
 * [HorizontalPager] 渲染，[NavHost] 只承载二级路由并盖在 pager 之上；运行时更新与
 * App 更新对话框按优先级先后弹出。
 *
 * 全程在主线程组合；依赖均经 Koin 注入，VM 为 Activity 作用域；宿主 ON_STOP 时经
 * [AppLockManager] 上锁；深色主题变化经 [onDarkThemeChanged] 通知宿主。
 *
 * Loads the application root composable: wires theme, app lock, navigation, and
 * global dialogs; it is the entry point of the whole UI tree.
 *
 * Layered outside-in: collects the state flows of [SettingsViewModel],
 * [PermissionManager], [RootfsProvisioner], and [AppUpdateManager]; [AzurPilotTheme]
 * supplies the dual-style theme; [AppLockGate] takes over the full screen while the
 * app lock is on and not unlocked; [ProvisionScreen] takes over until the rootfs is
 * Ready; the main tabs render in a [HorizontalPager] while [NavHost] only hosts the
 * second-level routes stacked above the pager; runtime-update and app-update dialogs
 * appear in priority order.
 *
 * Composed on the main thread; dependencies are Koin-injected with Activity-scoped
 * view models; locks via [AppLockManager] on host ON_STOP; dark-theme changes are
 * reported to the host through [onDarkThemeChanged].
 *
 * @param onDarkThemeChanged 深色主题开关变化时回调宿主（用于同步窗口外观）/
 *   invoked when the dark-theme flag changes, so the host can sync window appearance
 */
@Composable
fun AppRoot(
    onDarkThemeChanged: (Boolean) -> Unit,
    settingsViewModel: SettingsViewModel = koinViewModel(),
    permissionManager: PermissionManager = koinInject(),
    provisioner: RootfsProvisioner = koinInject(),
    appUpdateManager: AppUpdateManager = koinInject(),
    appSettings: AppSettingsManager = koinInject(),
    appLockManager: AppLockManager = koinInject(),
) {
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val readiness by permissionManager.readiness.collectAsStateWithLifecycle()
    val isGranting by permissionManager.isGranting.collectAsStateWithLifecycle()

    // 首启 rootfs 部署的门：未 Ready 时整屏接管，tab/二级页都在门内
    val provisionState by provisioner.state.collectAsStateWithLifecycle()
    val runtimeCheck by provisioner.updateCheck.collectAsStateWithLifecycle()
    val githubMirror by appSettings.githubMirror.collectAsStateWithLifecycle()
    var provisionSkipped by remember { mutableStateOf(false) }
    var runtimePromptDismissed by rememberSaveable { mutableStateOf(false) }
    var applyingRuntimeUpdate by remember { mutableStateOf(false) }
    val prootHost: ProotHost = koinInject()
    var prootStarted by remember { mutableStateOf(prootHost.state.value.phase != ProotPhase.IDLE) }
    LaunchedEffect(Unit) {
        if (provisioner.state.value !is ProvisionState.Ready) provisioner.start()
    }
    val appUpdateState by appUpdateManager.state.collectAsStateWithLifecycle()
    var appUpdateChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!appUpdateChecked) {
            appUpdateChecked = true
            appUpdateManager.check()
        }
    }
    val showProvision = provisionState !is ProvisionState.Ready && !provisionSkipped

    // 启动时只查版本，先等用户决定是否更新，再启动 proot。
    val installedRuntime = provisioner.installedVersion()
    val runtimeAvailable = runtimeCheck.checked && runtimeCheck.error == null &&
        runtimeCheck.latestVersion != null && runtimeCheck.latestVersion != installedRuntime
    val hotUpdateOn by appSettings.hotUpdateEnabled.collectAsStateWithLifecycle()
    // 整包重部署只在「基础镜像有差异」时弹出：rootfs_version 仅上游提交前缀不同
    // (commitOnly) 且热更开启时，源码差异交给热更通道，不弹整包。用户主动打开 App
    // 时全量更新必须前台提示（每日定时自动更新只是兜底，不抢前台的知情权）。
    val fullUpdatePending = runtimeAvailable && !(runtimeCheck.commitOnly && hotUpdateOn)
    LaunchedEffect(provisionState, runtimeCheck, runtimePromptDismissed, applyingRuntimeUpdate, hotUpdateOn) {
        if (!prootStarted && provisionState is ProvisionState.Ready && runtimeCheck.checked && !runtimeCheck.checking &&
            !applyingRuntimeUpdate && (!fullUpdatePending || runtimePromptDismissed)
        ) {
            prootHost.ensureStarted()
            prootStarted = true
        }
    }

    val darkTheme = when (settingsState.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(darkTheme) { onDarkThemeChanged(darkTheme) }

    val context = LocalContext.current
    val activity = context as? FragmentActivity

    // 应用进入后台/熄屏时锁定应用
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                appLockManager.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val isUnlocked by appLockManager.isUnlocked.collectAsStateWithLifecycle()
    val appLockEnabled by appSettings.appLockEnabled.collectAsStateWithLifecycle()
    val settingsLoaded by appSettings.loaded.collectAsStateWithLifecycle()
    // Fail closed until the persisted setting is read; the new-install default
    // is false, but existing users may have explicitly enabled app lock.
    val isAppLocked = !settingsLoaded || (appLockEnabled && appLockManager.canAuthenticate(context) && !isUnlocked)
    var appLockError by remember { mutableStateOf<String?>(null) }

    // 首启「机型支持列表」弹窗：盘上标志未置位且本会话未处理过才弹；
    // 必须等设置读盘完成，否则老用户会先看到默认 false 闪一下
    val compatNoticeShown by appSettings.compatNoticeShown.collectAsStateWithLifecycle()
    var compatNoticeDismissed by rememberSaveable { mutableStateOf(false) }
    var showCompatNoticeDialog by rememberSaveable { mutableStateOf(false) }

    AzurPilotTheme(darkTheme = darkTheme) {
        AppLockGate(
            isUnlocked = !isAppLocked,
            errorMessage = appLockError,
            autoRequestUnlock = settingsLoaded,
            onUnlockRequest = {
                if (!settingsLoaded) return@AppLockGate
                appLockError = null
                activity?.let { act ->
                    appLockManager.authenticate(
                        activity = act,
                        title = context.getString(R.string.auth_prompt_title_app),
                        subtitle = context.getString(R.string.auth_prompt_subtitle),
                        onSuccess = {
                            appLockManager.markUnlocked()
                        },
                        onError = { code, message ->
                            appLockError = context.getString(R.string.app_lock_auth_failed, code, message)
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!isAppLocked && provisionState is ProvisionState.Ready && fullUpdatePending &&
                !runtimePromptDismissed && !applyingRuntimeUpdate && !prootStarted
            ) {
                AlertDialog(
                    onDismissRequest = { runtimePromptDismissed = true },
                    title = { Text(stringResource(R.string.runtime_update_title)) },
                    text = { Text(stringResource(R.string.runtime_update_message, installedRuntime.orEmpty(), runtimeCheck.latestVersion.orEmpty())) },
                    confirmButton = {
                        TextButton(onClick = {
                            applyingRuntimeUpdate = true
                            provisioner.applyUpdate()
                        }) { Text(stringResource(R.string.runtime_update_now)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { runtimePromptDismissed = true }) {
                            Text(stringResource(R.string.app_update_later))
                        }
                    },
                )
            }
            LaunchedEffect(provisionState, applyingRuntimeUpdate) {
                if (applyingRuntimeUpdate && provisionState is ProvisionState.Ready &&
                    runtimeCheck.latestVersion == provisioner.installedVersion()
                ) applyingRuntimeUpdate = false
                if (applyingRuntimeUpdate && provisionState is ProvisionState.Ready && runtimeCheck.error != null) {
                    applyingRuntimeUpdate = false
                }
            }
            if (!isAppLocked) {
                appUpdateState.available?.takeUnless {
                    provisionState is ProvisionState.Ready && fullUpdatePending &&
                        !runtimePromptDismissed && !applyingRuntimeUpdate
                }?.let { update ->
                    AlertDialog(
                        onDismissRequest = appUpdateManager::dismiss,
                        title = { Text(stringResource(R.string.app_update_title)) },
                        text = {
                            Text(
                                if (appUpdateState.downloading) stringResource(R.string.app_update_downloading)
                                else if (appUpdateState.error != null) stringResource(R.string.app_update_error, appUpdateState.error!!)
                                else stringResource(R.string.app_update_message, update.versionName),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !appUpdateState.downloading,
                                onClick = appUpdateManager::downloadAndInstall,
                            ) { Text(stringResource(R.string.app_update_install)) }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !appUpdateState.downloading,
                                onClick = appUpdateManager::dismiss,
                            ) { Text(stringResource(R.string.app_update_later)) }
                        },
                    )
                }
            }
            // NavHost 只承载二级页面；主 tab 仍由下面的 HorizontalPager 渲染
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            // 首帧 backStackEntry 还没就绪，那时必然停在 startDestination
            val currentRoute = navBackStackEntry?.destination?.route
            val onSubPage = currentRoute != null && currentRoute !in Routes.mainTabs
            // AppCompat 切换语言会重建 Activity；显式保存目标页，避免恢复到中途页。
            var selectedPage by rememberSaveable { mutableStateOf(TopDestination.Hangar.ordinal) }
            val pagerState = rememberPagerState(initialPage = selectedPage, pageCount = { TopDestination.entries.size })

            LaunchedEffect(pagerState) {
                pagerState.scrollToPage(selectedPage)
                snapshotFlow { pagerState.settledPage }.collect { selectedPage = it }
            }
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        var exportKind by remember { mutableStateOf<LogExportKind?>(null) }

        val hostState: HostState = koinInject()
        // 主页是否可见：HangarScreen 靠它决定要不要自动补一次环境拉起
        val hangarActive = pagerState.currentPage == TopDestination.Hangar.ordinal

        // 首启「机型支持列表」弹窗：进入主页后触发；弹窗状态一旦激活即保持展示，避免后台异步状态变动导致弹窗自动消失。
        // 排序最末：必须等 App 更新与 Runtime 更新两项检查都出结果（否则更新弹窗会在它
        // 触发后才落下，两个 AlertDialog 叠在一起）；Runtime 更新弹窗退场（稍后）后才轮到它。
        // 勾选「不再弹出」才持久化抑制；点击「去提交」前往 GitHub 登记并标记已处理。
        LaunchedEffect(
            hangarActive,
            onSubPage,
            showProvision,
            isAppLocked,
            settingsLoaded,
            compatNoticeShown,
            compatNoticeDismissed,
            readiness.needsGuidance,
            appUpdateState,
            runtimeCheck,
            fullUpdatePending,
            runtimePromptDismissed,
        ) {
            if (hangarActive &&
                !onSubPage &&
                !showProvision &&
                !isAppLocked &&
                settingsLoaded &&
                !compatNoticeShown &&
                !compatNoticeDismissed &&
                !showCompatNoticeDialog &&
                !readiness.needsGuidance &&
                !appUpdateState.checking &&
                appUpdateState.available == null &&
                runtimeCheck.checked &&
                !runtimeCheck.checking &&
                (!fullUpdatePending || runtimePromptDismissed)
            ) {
                delay(300)
                if (hangarActive && !onSubPage && !showProvision && !isAppLocked) {
                    showCompatNoticeDialog = true
                }
            }
        }

        if (showCompatNoticeDialog) {
            val uriHandler = LocalUriHandler.current
            var dontAskAgain by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = {
                    showCompatNoticeDialog = false
                    compatNoticeDismissed = true
                    if (dontAskAgain) {
                        scope.launch { appSettings.setCompatNoticeShown(true) }
                    }
                },
                title = { Text(stringResource(R.string.compat_notice_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.compat_notice_message))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dontAskAgain = !dontAskAgain }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = dontAskAgain,
                                onCheckedChange = { dontAskAgain = it },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.compat_notice_dont_ask_again),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showCompatNoticeDialog = false
                        compatNoticeDismissed = true
                        scope.launch { appSettings.setCompatNoticeShown(true) }
                        uriHandler.openUri(ProjectLinks.ISSUE_DEVICE_SUPPORT)
                    }) { Text(stringResource(R.string.compat_notice_go)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCompatNoticeDialog = false
                        compatNoticeDismissed = true
                        if (dontAskAgain) {
                            scope.launch { appSettings.setCompatNoticeShown(true) }
                        }
                    }) { Text(stringResource(R.string.compat_notice_later)) }
                },
            )
        }

        /**
         * 切到第 [index] 页
         *
         * 进出虚拟屏页要换屏幕方向，而转动会让 pager 的像素偏移与旋转后的新页宽对不上：
         * 动画跨旋转会停在"两页各露一半"的画面上（实测：画的是第 2、3 页的拼接，currentPage 却是 3）。
         * 涉及虚拟屏页的两条边直接落位，其余切换照常走动画
         *
         * Switches the pager to page [index].
         *
         * Entering or leaving the virtual-display page rotates the screen, and the
         * rotation desynchronizes the pager's pixel offset from the rotated page
         * width: an animation crossing a rotation stalls on a "half of each page"
         * frame (observed: pages 2 and 3 rendered stitched while currentPage read 3).
         * The two edges touching the virtual-display page therefore jump instantly;
         * all other switches animate as usual.
         */
        fun goToPage(index: Int) {
            selectedPage = index
            // 二级页已降为普通页面（不再遮底栏）：从二级页直接切 tab 时先把 NavHost
            // 弹回主 tab 根路由，否则旧二级页会继续盖在新 tab 的内容区上
            if (onSubPage) navController.popBackStack(Routes.HANGAR, inclusive = false)
            scope.launch {
                val touchesScreen = index == TopDestination.Screen.ordinal ||
                    pagerState.currentPage == TopDestination.Screen.ordinal
                if (touchesScreen) {
                    pagerState.scrollToPage(index)
                } else {
                    pagerState.animateScrollToPage(index)
                }
            }
        }

        // 桌面快捷方式的跳转请求：MainActivity 收到后登记到总线，这里消费。
        // 部署门未放行或应用锁未解时先挂着（NavHost 未组合时 navigate 会炸，
        // 锁定状态下也不该在遮罩背后偷偷换页），就绪后收集器一起来就补执行
        val shortcutRequests: ShortcutRequests = koinInject()
        LaunchedEffect(showProvision, isAppLocked) {
            if (showProvision || isAppLocked) return@LaunchedEffect
            shortcutRequests.requests.collect { id ->
                when (id) {
                    ShortcutRequests.OPEN_SCREEN -> goToPage(TopDestination.Screen.ordinal)
                    ShortcutRequests.OPEN_RUNNER_LOG -> navController.navigate(Routes.AZURPILOT_LOG) {
                        launchSingleTop = true
                    }
                }
                shortcutRequests.consume()
            }
        }

        // 底栏实际高度：snackbar 要停在它上面，而 M3 只给了 80dp 的私有常量
        val density = LocalDensity.current
        var bottomBarHeight by remember { mutableStateOf(0.dp) }
        // 横屏（虚拟屏页）下导航栏靠边竖排，底部没有可遮挡 snackbar 的东西
        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // 未装/未启动/未授权时的引导；needsGuidance 为 false 时自身不渲染
        ShizukuReadinessDialog(
            readiness = readiness,
            // NotInstalled 档：不内置安装包，确认键 = 跳浏览器到分发页；装完切回来 onResume 自动重测
            onDownload = { permissionManager.openShizukuDownload(context) },
            onOpenApp = { permissionManager.openShizuku(context) },
            onRequestAuth = { scope.launch { permissionManager.requestRemoteAccess() } },
            onUninstall = { permissionManager.uninstallShizuku(context) },
            onDismiss = { scope.launch { permissionManager.skipShizukuCheck() } },
            onSwitchToRoot = { settingsViewModel.onIntent(SettingsIntent.SetBackend(RemoteBackend.ROOT)) },
            isRequesting = isGranting,
        )

        if (showProvision) {
            ProvisionScreen(
                state = provisionState,
                onRetry = { provisioner.retry() },
                // 跳过只留给开发包：跳过只能调外壳，AzurPilot 起不来
                onSkip = if (BuildConfig.DEBUG &&
                    (provisionState is ProvisionState.NotBundled || provisionState is ProvisionState.Failed)
                ) {
                    { provisionSkipped = true }
                } else {
                    null
                },
                // 首启就得选源：直连不通的用户不该先撞一次超时
                mirrorId = githubMirror,
                onMirrorChange = { mirror -> scope.launch { appSettings.setGithubMirror(mirror) } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            // 挖孔屏避让：systemBars 不含孔洞（横屏时状态栏内缩可能小于孔径），
            // 并上 displayCutout 后，横屏内容才会真正从摄像头孔旁边让开
            contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
            bottomBar = {
                // 横屏（虚拟屏页）时导航栏靠边竖排，不从底部吃掉本就紧张的高度
                if (!isLandscape) {
                    NavigationBar(
                        modifier = Modifier.onGloballyPositioned {
                            bottomBarHeight = with(density) { it.size.height.toDp() }
                        },
                    ) {
                        TopDestination.entries.forEachIndexed { index, destination ->
                            val selected = pagerState.currentPage == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { goToPage(index) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.filledIcon else destination.outlinedIcon,
                                        contentDescription = stringResource(destination.labelRes),
                                    )
                                },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // 页内 imePadding 量的是到窗口底边的距离，而这里的底边已经被底栏顶高了一截；
                    // 不声明这份已让位的 inset，页内就会多减一个底栏，正文与键盘之间空出一条
                    .consumeWindowInsets(padding),
            ) {
                if (isLandscape) {
                    NavigationRail {
                        TopDestination.entries.forEachIndexed { index, destination ->
                            val selected = pagerState.currentPage == index
                            NavigationRailItem(
                                selected = selected,
                                onClick = { goToPage(index) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.filledIcon else destination.outlinedIcon,
                                        contentDescription = stringResource(destination.labelRes),
                                    )
                                },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    // 整页横滑一律禁用：切页手势与页内竖向列表同链竞争轴锁（按哪个轴先过
                    // touch slop 判定），慢速斜拖时横向漂移有概率先过 slop，拖动被 pager
                    // 截走，表现为「慢滑有概率无效、只有快滑才动」——AzurPilot 页此前已
                    // 单独禁用，其余页同样中招，索性全禁。pager 只当页面容器用（预组合
                    // 相邻页 + rememberSaveable 保状态），切页唯一入口是底栏
                    userScrollEnabled = false,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.weight(1f),
                ) { page ->
                    when (TopDestination.entries[page]) {
                        TopDestination.Hangar -> HangarScreen(
                            active = hangarActive,
                            modifier = Modifier.fillMaxSize(),
                        )

                        TopDestination.Screen -> ScreenPage(
                            active = pagerState.currentPage == TopDestination.Screen.ordinal,
                            modifier = Modifier.fillMaxSize(),
                        )

                        TopDestination.AzurPilot -> AzurPilotPage(
                            // 从主页直接动画切到设置时，currentPage 会短暂经过中间的 AzurPilot 页；
                            // 只有动画真正停在该页后才做「补一次环境拉起」这类有副作用的事
                            active = pagerState.settledPage == TopDestination.AzurPilot.ordinal,
                            modifier = Modifier.fillMaxSize(),
                        )

                        TopDestination.Settings -> SettingsScreen(
                            state = settingsState,
                            onOpenSection = { section -> navController.navigate(section.route) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // 二级页不再全屏接管：只让出底栏高度，底栏在二级页上仍然可见可点，
        // 二级页由此成为与主 tab 同级的普通页面（从二级页点底栏可直切其他 tab）。
        // 状态栏 inset 依旧归各页顶栏自己吃（顶栏垫进状态栏底下），这里只垫底栏；
        // 让出的高度要同步声明为已消费 inset，否则页内 imePadding 会把它重复算一遍
        // 不再挂指针拦截层：对祖先节点的全量 consume 会杀死子级慢速拖动（快甩没事、
        // 慢滑全灭，真机实测）；它防的横滑切页与底栏穿透如今均不存在——pager 横滑已
        // 全局禁用，命中测试也会剪掉被盖住的兄弟层，二级页空白处点击不会落到下层
        val subPageBottomInset = if (isLandscape) 0.dp else bottomBarHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = subPageBottomInset)
                .consumeWindowInsets(WindowInsets(bottom = subPageBottomInset))
                .imePadding(),
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.HANGAR,
                modifier = Modifier.fillMaxSize(),
                // 共享轴 X 前进转场：推进右进左出、返回左进右出
                enterTransition = { slideInHorizontally { it } + fadeIn() },
                exitTransition = { slideOutHorizontally { -it } + fadeOut() },
                popEnterTransition = { slideInHorizontally { -it } + fadeIn() },
                popExitTransition = { slideOutHorizontally { it } + fadeOut() },
            ) {
                // 主 tab 路由空占位：真实内容由上面的 HorizontalPager 渲染
                composable(Routes.HANGAR) {}
                composable(Routes.AZURPILOT) {}
                composable(Routes.SETTINGS) {}
                composable(Routes.APP_LOG) {
                    AppLogScreen(
                        onBack = { navController.popBackStack() },
                        onOpen = { navController.navigate(Routes.appLogDetail(it)) },
                    )
                }
                composable(
                    route = Routes.APP_LOG_DETAIL,
                    arguments = listOf(
                        navArgument(Routes.APP_LOG_DETAIL_ARG) { type = NavType.StringType },
                    ),
                ) { entry ->
                    AppLogDetailScreen(
                        fileName = entry.arguments?.getString(Routes.APP_LOG_DETAIL_ARG).orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.AZURPILOT_LOG) {
                    AzurPilotLogScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDaily = { navController.navigate(Routes.azurPilotLogDetail(it)) },
                        onOpenError = { navController.navigate(Routes.azurPilotErrorDetail(it)) },
                    )
                }
                composable(
                    route = Routes.AZURPILOT_LOG_DETAIL,
                    arguments = listOf(
                        navArgument(Routes.AZURPILOT_LOG_DETAIL_ARG) { type = NavType.StringType },
                    ),
                ) { entry ->
                    AzurPilotLogDetailScreen(
                        fileName = entry.arguments?.getString(Routes.AZURPILOT_LOG_DETAIL_ARG).orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Routes.AZURPILOT_ERROR_DETAIL,
                    arguments = listOf(
                        navArgument(Routes.AZURPILOT_ERROR_DETAIL_ARG) { type = NavType.StringType },
                    ),
                ) { entry ->
                    AzurPilotErrorDetailScreen(
                        dirName = entry.arguments?.getString(Routes.AZURPILOT_ERROR_DETAIL_ARG).orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                // 设置二级页：主页只列分类入口，内容各归一类
                composable(Routes.SETTINGS_DISPLAY) {
                    DisplaySettingsPage(
                        state = settingsState,
                        onIntent = settingsViewModel::onIntent,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS_VIRTUAL_DISPLAY) {
                    VirtualDisplaySettingsPage(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETTINGS_LOGS) {
                    LogsSettingsPage(
                        state = settingsState,
                        onIntent = settingsViewModel::onIntent,
                        onOpenAppLog = { navController.navigate(Routes.APP_LOG) },
                        onOpenRunnerLog = { navController.navigate(Routes.AZURPILOT_LOG) },
                        onExportRunnerLogs = { exportKind = LogExportKind.AZURPILOT },
                        onExportLauncherLogs = { exportKind = LogExportKind.LAUNCHER },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS_KEEP_ALIVE) {
                    KeepAliveSettingsPage(
                        state = settingsState,
                        onIntent = settingsViewModel::onIntent,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS_ADVANCED) {
                    AdvancedSettingsPage(
                        state = settingsState,
                        onIntent = settingsViewModel::onIntent,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS_WIDGET) {
                    WidgetSettingsPage(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETTINGS_RUNTIME) {
                    RuntimeSettingsPage(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETTINGS_OCR) {
                    OcrSettingsPage(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETTINGS_ABOUT) {
                    AboutSettingsPage(
                        onOpenLicenses = { navController.navigate(Routes.SETTINGS_LICENSES) },
                        onOpenLicenseDetail = { id -> navController.navigate(Routes.licenseDetail(id)) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS_DEVICE_REPORT) {
                    DeviceReportSettingsPage(onBack = { navController.popBackStack() })
                }
                composable(Routes.SETTINGS_LICENSES) {
                    OpenSourceLicensesPage(
                        onOpenDetail = { id -> navController.navigate(Routes.licenseDetail(id)) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Routes.SETTINGS_LICENSE_DETAIL,
                    arguments = listOf(navArgument(Routes.SETTINGS_LICENSE_DETAIL_ARG) { type = NavType.StringType }),
                ) { backStackEntry ->
                    val componentId = backStackEntry.arguments?.getString(Routes.SETTINGS_LICENSE_DETAIL_ARG).orEmpty()
                    LicenseDetailPage(
                        componentId = componentId,
                        onBack = { navController.popBackStack() },
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
        }

        // 押在最外层：二级页仍在它底下，消息不会被页面盖住；二级页不遮底栏后，
        // portrait 下 snackbar 恒按底栏高度让位
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isLandscape) 0.dp else bottomBarHeight),
        )

        // 全屏预览宿主已随虚屏画面一并移除：native 预览是旁路分叉，拆掉不影响截图/识别
        }
        }

        // 无条件挂在这一层：它注册的 SAF launcher 要活得比 sheet 的显隐久
        LogExportController(
            kind = exportKind,
            onDismiss = { exportKind = null },
            onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        )
        }
    }
}
