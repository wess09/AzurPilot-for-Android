package com.azurpilot.ghio.ui.azurpilot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.service.HostState
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.remoteaccess.RemotePairingCard
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.sections.ConfigSection
import com.azurpilot.ghio.ui.azurpilot.sections.LogsSection
import com.azurpilot.ghio.ui.azurpilot.sections.OverviewSection
import com.azurpilot.ghio.ui.azurpilot.sections.StatisticsSection
import com.azurpilot.ghio.ui.azurpilot.sections.TaskConfigPage
import com.azurpilot.ghio.ui.azurpilot.sections.settings.AnnouncementPage
import com.azurpilot.ghio.ui.azurpilot.sections.settings.DeploySettingsPage
import com.azurpilot.ghio.ui.azurpilot.sections.settings.InstancesPage
import com.azurpilot.ghio.ui.azurpilot.sections.settings.MeowfficerPage
import com.azurpilot.ghio.ui.azurpilot.sections.settings.SettingsSection
import com.azurpilot.ghio.ui.azurpilot.sections.settings.UpdaterPage
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.ksu.AppFilterChip as FilterChip
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppIconButton as IconButton
import com.azurpilot.ghio.ui.ksu.AppSnackbarHost as SnackbarHost
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppButton as Button
import com.azurpilot.ghio.ui.ksu.AppPageScaffold
import com.azurpilot.ghio.ui.navigation.AppRoute
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.utils.pagerGestureOverride

/**
 * 渲染控制台的平级分区，详情通过宿主唯一导航栈打开。
 *
 * Renders peer console sections, opening details through the host's single navigation stack.
 *
 * @param active 是否允许补充启动运行环境。 / Whether environment warm-up is allowed.
 * @param onNavigate 向宿主推入类型目的地。 / Pushes typed destinations into the host stack.
 */
@Composable
fun AzurPilotPage(
    active: Boolean,
    onNavigate: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
    onOpenRemote: () -> Unit = {},
) {
    AzurPilotRuntimeSurface(active = active, modifier = modifier, onOpenRemote = onOpenRemote) { repository ->
        val sourceId by repository.sourceId.collectAsStateWithLifecycle()
        key(sourceId) { AzurPilotContent(repository, onNavigate, active) }
    }
}

/**
 * 保持运行时内容与保存状态，断连时用遮罩提示；运行控制仍由主页与悬浮窗负责。
 *
 * Retains runtime content and saved state under a connection gate. Runtime control stays with the
 * home page and overlay.
 */
@Composable
private fun AzurPilotRuntimeSurface(
    active: Boolean,
    modifier: Modifier = Modifier,
    onOpenRemote: () -> Unit,
    repository: AzurPilotRepository = koinInject(),
    hostState: HostState = koinInject(),
    prootHost: ProotHost = koinInject(),
    runController: AzurPilotRunController = koinInject(),
    content: @Composable (AzurPilotRepository) -> Unit,
) {
    val connected by repository.connected.collectAsStateWithLifecycle()
    val remoteName by repository.remoteName.collectAsStateWithLifecycle()
    val settings: AppSettingsManager = koinInject()
    val remoteClientMode by settings.remoteClientMode.collectAsStateWithLifecycle()
    val authRequired by repository.authRequired.collectAsStateWithLifecycle()
    val hostSnapshot by hostState.snapshot.collectAsStateWithLifecycle()
    val prootState by prootHost.state.collectAsStateWithLifecycle()
    val runState by runController.state.collectAsStateWithLifecycle()
    val message by repository.messages.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 仅在设备不存在时补拉，平级页切换不应重复探测已有的虚拟屏。
    LaunchedEffect(active, hostSnapshot.privilegedConnected, remoteName, remoteClientMode) {
        if (!remoteClientMode && remoteName == null && active && hostSnapshot.privilegedConnected &&
            hostState.snapshot.value.vdDisplayId == DefaultDisplayConfig.DISPLAY_NONE
        ) {
            hostState.ensureEnvironmentStarted()
        }
    }

    LaunchedEffect(active, message) {
        message?.takeIf { active }?.let {
            snackbarHostState.showSnackbar(it)
            repository.dismissMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 底层页面与导航栈保持常驻，断连或服务重启时不暴力卸载，保护用户输入与回退状态
        content(repository)

        // 运行时未就绪或断连时使用悬浮遮罩平滑覆盖，避免破坏底层视图树
        if (!connected) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                if (remoteClientMode || remoteName != null) AppPageScaffold(
                    title = stringResource(R.string.remote_pair_title),
                ) { insets ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(insets)
                            .verticalScroll(rememberScrollState()).padding(AppTokens.Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.remote_client_hint))
                        if (remoteName != null) Text(stringResource(
                            if (authRequired) R.string.remote_pair_auth_failed else R.string.remote_pair_open_failed))
                        RemotePairingCard()
                    }
                } else RuntimeGate(
                    reachable = runState.reachable,
                    phase = prootState.phase,
                    detail = prootState.detail,
                    onOpenRemote = onOpenRemote,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(AppTokens.Spacing.lg),
        )
    }
}

/**
 * 网关还没连上时的接管屏
 *
 * 「未就绪」与「环境正在起」要分开说：准备链要几分钟，只显示一句「未就绪」会让人以为坏了。
 *
 * The takeover screen shown while the gateway is not connected yet.
 *
 * "Not ready" and "environment coming up" must be told apart: the preparation chain takes minutes,
 * and a bare "not ready" would read as something broken.
 *
 * @param reachable 运行时 HTTP 端口是否可达 / whether the runtime's HTTP port is reachable
 * @param phase PRoot 会话阶段 / the PRoot session phase
 * @param detail 失败阶段的详情文案 / the failure-phase detail text
 */
@Composable
private fun RuntimeGate(reachable: Boolean, phase: ProotPhase, detail: String, onOpenRemote: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AppTokens.Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ExpressiveLoadingIndicator()
        Text(
            text =
                when {
                    phase == ProotPhase.FAILED ->
                        stringResource(R.string.proot_phase_failed, detail)
                    phase == ProotPhase.PREPARING -> stringResource(R.string.proot_phase_preparing)
                    phase == ProotPhase.UPDATING -> stringResource(R.string.proot_phase_updating)
                    phase == ProotPhase.STARTING -> stringResource(R.string.proot_phase_starting)
                    reachable -> stringResource(R.string.ap_connecting)
                    else -> stringResource(R.string.ap_runtime_idle)
                },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppTokens.Spacing.lg),
        )
        Button(onClick = onOpenRemote, modifier = Modifier.padding(top = AppTokens.Spacing.lg)) {
            Text(stringResource(R.string.remote_pair_title))
        }
    }
}

/**
 * AzurPilot 详情页标准容器：拥有独立的 TopAppBar 与返回导航
 *
 * 遵循 MD3 规范：各详情页拥有独立的 TopAppBar，进入详情页时整体推入（不保留顶层 TabRow 与 ChipRow）， 退出时向右滑出返回上层，维持层级与空间连续。
 *
 * Standard container for AzurPilot detail pages: owns an independent TopAppBar and back navigation.
 * Adheres to MD3: detail pages have independent TopAppBars and transition as whole screens without
 * retaining the top-level section tabs or instance chip row.
 *
 * @param title 顶栏标题 / Top app bar title
 * @param onBack 返回上一级回调 / Back callback
 * @param modifier 修饰符 / Modifier
 * @param actions 顶栏右侧动作插槽 / Top app bar actions slot
 * @param content 详情页内容主体 / Detail page content body
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApDetailPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    com.azurpilot.ghio.ui.ksu.AppPageScaffold(
        title = title,
        modifier = modifier,
        onBack = onBack,
        actions = actions,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), content = content)
    }
}

/**
 * 在主线程组合平级分区，并保存每个分区的滚动位置与输入状态。
 *
 * Composes peer sections on the main thread and saves each section's scroll and input state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AzurPilotContent(
    repository: AzurPilotRepository,
    onNavigate: (AppRoute) -> Unit,
    active: Boolean,
) {
    var section by rememberSaveable { mutableStateOf(AzurPilotSection.Overview) }
    val saveableStateHolder = rememberSaveableStateHolder()
    val goHome = { section = AzurPilotSection.Overview }
    androidx.activity.compose.BackHandler(enabled = active && section != AzurPilotSection.Overview) {
        goHome()
    }
    // 两个目的地在动画期间同时保留：整页包含标题栏一起移动，
    // 而不是只对内容做渐变（此前的 section 切换没有任何动画）。
    androidx.compose.animation.AnimatedContent(
        targetState = section,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val forward = targetState != AzurPilotSection.Overview
            val entering =
                if (forward) {
                    androidx.compose.animation.slideInHorizontally(
                        initialOffsetX = { it / 3 },
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 330,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    )
                } else {
                    androidx.compose.animation.slideInHorizontally(
                        initialOffsetX = { -it / 5 },
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 330,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    )
                }
            val exiting =
                if (forward) {
                    androidx.compose.animation.slideOutHorizontally(
                        targetOffsetX = { -it / 5 },
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 330,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    )
                } else {
                    androidx.compose.animation.slideOutHorizontally(
                        targetOffsetX = { it / 3 },
                        animationSpec = androidx.compose.animation.core.tween(
                            durationMillis = 330,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    )
                }
            (entering + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(230),
            )) togetherWith (exiting + androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(200),
            ))
        },
        label = "AzurPilotPageNavigation",
    ) { destination ->
        com.azurpilot.ghio.ui.ksu.AppPageScaffold(
            title = if (destination == AzurPilotSection.Overview) {
                stringResource(R.string.app_name)
            } else {
                stringResource(destination.labelRes)
            },
            onBack = if (destination != AzurPilotSection.Overview) goHome else null,
            actions = {
                if (destination == AzurPilotSection.Overview) {
                    IconButton(onClick = { section = AzurPilotSection.Settings }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.ap_section_settings),
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            ) {
                saveableStateHolder.SaveableStateProvider(destination.name) {
                    when (destination) {
                        AzurPilotSection.Overview ->
                            OverviewSection(
                                repository = repository,
                                onOpenConfig = { section = AzurPilotSection.Config },
                                onOpenLogs = { section = AzurPilotSection.Logs },
                                onOpenStats = { section = AzurPilotSection.Statistics },
                            )
                        AzurPilotSection.Config ->
                            ConfigSection(
                                repository = repository,
                                onOpenTask = { task -> onNavigate(AppRoute.Task(task)) },
                            )
                        AzurPilotSection.Logs -> LogsSection(repository)
                        AzurPilotSection.Statistics -> StatisticsSection(repository)
                        AzurPilotSection.Settings ->
                            SettingsSection(
                                repository = repository,
                                onOpenInstances = { onNavigate(AppRoute.Instances) },
                                onOpenAnnouncement = { onNavigate(AppRoute.Announcement) },
                                onOpenMeowfficer = { onNavigate(AppRoute.Meowfficer) },
                                onOpenUpdater = { onNavigate(AppRoute.Updater) },
                                onOpenDeploy = { onNavigate(AppRoute.Deploy) },
                            )
                    }
                }
            }
        }
    }
}

/**
 * 渲染宿主栈中的运行时详情；每页拥有独立标题栏和任务搜索动作。
 *
 * Renders runtime details in the host stack, with a dedicated title bar and task search action.
 */
@Composable
fun AzurPilotDetailPage(route: AppRoute.Console, onBack: () -> Unit, onOpenRemote: () -> Unit = onBack) {
    AzurPilotRuntimeSurface(active = true, onOpenRemote = onOpenRemote) { repository ->
        val topBarAction = remember { ApTopBarAction() }
        val titleRes =
            when (route) {
                is AppRoute.Task -> R.string.ap_title_task_config
                AppRoute.Instances -> R.string.ap_instances_import
                AppRoute.Announcement -> R.string.ap_title_announcement
                AppRoute.Meowfficer -> R.string.ap_title_meowfficer
                AppRoute.Updater -> R.string.ap_title_updater
                AppRoute.Deploy -> R.string.ap_title_deploy
            }
        ApDetailPage(
            title = stringResource(titleRes),
            onBack = onBack,
            actions = {
                if (route is AppRoute.Task) {
                    AnimatedVisibility(visible = topBarAction.visible) {
                        IconButton(onClick = { topBarAction.onClick?.invoke() }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.ap_bar_search),
                            )
                        }
                    }
                }
            },
        ) {
            when (route) {
                is AppRoute.Task ->
                    TaskConfigPage(repository, route.taskName, topBarAction = topBarAction)
                AppRoute.Instances -> InstancesPage(repository)
                AppRoute.Announcement -> AnnouncementPage(repository)
                AppRoute.Meowfficer -> MeowfficerPage(repository)
                AppRoute.Updater -> UpdaterPage(repository)
                AppRoute.Deploy -> DeploySettingsPage(repository)
            }
        }
    }
}
