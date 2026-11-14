package com.azurpilot.ghio.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.components.AppSymbol
import com.azurpilot.ghio.ui.components.AppSymbolIcon
import com.azurpilot.ghio.ui.ksu.*
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppSnackbarHost as SnackbarHost
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.component.FloatingBottomBarItem
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.*

internal enum class TopDestination(
    @param:StringRes val labelRes: Int,
    val outlinedIcon: ImageVector,
    val filledIcon: ImageVector,
) {
    /** 主页。 / Home. */
    Hangar(R.string.nav_hangar, Icons.Outlined.Home, Icons.Filled.Home),
    /** 运行时控制台。 / Runtime console. */
    AzurPilot(R.string.nav_azurpilot, Icons.Outlined.DashboardCustomize, Icons.Filled.DashboardCustomize),
    /** 虚拟屏。 / Virtual display. */
    Screen(R.string.nav_screen, Icons.Outlined.PhoneAndroid, Icons.Filled.PhoneAndroid),
    ;

    /** 主页面对应的动态图标。 / Animated icon for this main destination. */
    val symbol: AppSymbol
        get() = when (this) {
            Hangar -> AppSymbol.Home
            AzurPilot -> AppSymbol.Terminal
            Screen -> AppSymbol.Phone
        }
}

/**
 * 主导航容器：提供应用页面目的地与消息宿主。
 *
 * Main navigation container with app destinations and snackbar hosting.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppNavigationScaffold(
    mainPagerState: MainPagerState,
    rootBackEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    content: @Composable (Int, Dp) -> Unit,
) {
    val enableFloatingBottomBar = com.azurpilot.ghio.theme.ksu.LocalEnableFloatingBottomBar.current
    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)
    val settingsManager: com.azurpilot.ghio.settings.AppSettingsManager =
        org.koin.compose.koinInject()
    val appearanceSettings by settingsManager.appearance.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) { mainPagerState.syncPage() }
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = rootBackEnabled && mainPagerState.selectedPage != 0,
        onBackCompleted = { mainPagerState.animateToPage(0) },
    )
    val contentReady = rememberContentReady()
    val uiMode = com.azurpilot.ghio.theme.ksu.LocalUiMode.current
    val surfaceColor =
        if (uiMode == com.azurpilot.ghio.theme.ksu.UiMode.Miuix)
            top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surface
    val blurBackdrop =
        com.azurpilot.ghio.ui.ksu.util.rememberBlurBackdrop(
            com.azurpilot.ghio.theme.ksu.LocalEnableBlur.current &&
                uiMode == com.azurpilot.ghio.theme.ksu.UiMode.Miuix
        )
    val backdrop =
        top.yukonga.miuix.kmp.blur.rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    val pagerMode =
        PagerInterceptionMode.entries.getOrElse(
            appearanceSettings.pagerInterceptionMode.toIntOrNull() ?: 1
        ) {
            PagerInterceptionMode.Native
        }
    val pagerContent =
        @Composable { bottomInnerPadding: Dp ->
            HorizontalPager(
                modifier =
                    Modifier.fillMaxSize()
                        .clipToBounds()
                        .pagerGestureOverride(mainPagerState.pagerState, mode = pagerMode)
                        .then(
                            if (
                                enableFloatingBottomBar &&
                                    com.azurpilot.ghio.theme.ksu.LocalEnableFloatingBottomBarBlur
                                        .current
                            )
                                Modifier.layerBackdrop(backdrop)
                            else Modifier
                        )
                        .then(
                            if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop)
                            else Modifier
                        ),
                state = mainPagerState.pagerState,
                // 业务分页包含实时日志与统计，避免屏外页面继续组合和绘制。
                beyondViewportPageCount = 0,
                overscrollEffect = null,
                userScrollEnabled = pagerMode != PagerInterceptionMode.CrossAxisInterceptor,
                pageNestedScrollConnection =
                    if (pagerMode == PagerInterceptionMode.CrossAxisInterceptor)
                        PagerGestureNestedScrollConnection
                    else
                        androidx.compose.foundation.pager.PagerDefaults.pageNestedScrollConnection(
                            mainPagerState.pagerState,
                            androidx.compose.foundation.gestures.Orientation.Horizontal,
                        ),
                flingBehavior =
                    flingBehavior(
                        mainPagerState.pagerState,
                        snapAnimationSpec = PagerNavigationSpringSpec,
                    ),
            ) { page ->
                if (contentReady || page == mainPagerState.pagerState.settledPage) {
                    content(page, bottomInnerPadding)
                }
            }
        }
    if (
        com.azurpilot.ghio.theme.ksu.LocalUiMode.current ==
            com.azurpilot.ghio.theme.ksu.UiMode.Miuix
    ) {
        if (useNavigationRail) {
            val manager: com.azurpilot.ghio.settings.AppSettingsManager =
                org.koin.compose.koinInject()
            val appearance by manager.appearance.collectAsStateWithLifecycle()
            val state =
                top.yukonga.miuix.kmp.basic.rememberNavigationRailState(
                    initialValue =
                        if (appearance.navigationRailExpanded.toBoolean())
                            top.yukonga.miuix.kmp.basic.NavigationRailValue.Expanded
                        else top.yukonga.miuix.kmp.basic.NavigationRailValue.Collapsed
                )
            LaunchedEffect(state.currentValue) {
                manager.setAppearance {
                    it.copy(navigationRailExpanded = state.isExpanded.toString())
                }
            }
            top.yukonga.miuix.kmp.basic.Scaffold(popupHost = {}) {
                Row {
                    top.yukonga.miuix.kmp.basic.NavigationRail(
                        state = state,
                        color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface,
                        expandContentDescription = stringResource(R.string.nav_rail_expand),
                        collapseContentDescription = stringResource(R.string.nav_rail_collapse),
                    ) {
                        TopDestination.entries.forEachIndexed { index, destination ->
                            top.yukonga.miuix.kmp.basic.NavigationRailItem(
                                selected = mainPagerState.selectedPage == index,
                                onClick = { mainPagerState.animateToPage(index) },
                                icon = destination.filledIcon,
                                iconContent = { iconModifier, tint, description ->
                                    AppSymbolIcon(
                                        destination.symbol, iconModifier, description, tint,
                                        active = mainPagerState.selectedPage == index,
                                    )
                                },
                                label = stringResource(destination.labelRes),
                            )
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        pagerContent(
                            WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
                        )
                    }
                }
            }
        } else {
            top.yukonga.miuix.kmp.basic.Scaffold(
                popupHost = {},
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Box(Modifier.fillMaxWidth()) {
                        if (enableFloatingBottomBar) {
                            val bottomPadding =
                                WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding()
                                    .let {
                                        if (it != 0.dp) 8.dp + it else 28.dp
                                    }
                            com.azurpilot.ghio.ui.ksu.component.FloatingBottomBar(
                                modifier =
                                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                                        .padding(
                                            start = 28.dp,
                                            end = 28.dp,
                                            bottom = bottomPadding,
                                        ),
                                selectedIndex = mainPagerState.selectedPage,
                                onSelected = { mainPagerState.animateToPage(it) },
                                backdrop = backdrop,
                                tabsCount = TopDestination.entries.size,
                                isBlurEnabled =
                                    com.azurpilot.ghio.theme.ksu.LocalEnableFloatingBottomBarBlur
                                        .current,
                            ) { activateTab ->
                                TopDestination.entries.forEachIndexed { index, destination ->
                                    FloatingBottomBarItem(
                                        selected = mainPagerState.selectedPage == index,
                                        onClick = { activateTab(index) },
                                        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                                    ) {
                                        DestinationIcon(destination, mainPagerState.selectedPage == index)
                                        top.yukonga.miuix.kmp.basic.Text(
                                            stringResource(destination.labelRes),
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Visible,
                                        )
                                    }
                                }
                            }
                        } else {
                            com.azurpilot.ghio.ui.ksu.util.BlurredBar(blurBackdrop) {
                                top.yukonga.miuix.kmp.basic.NavigationBar(
                                    color =
                                        if (blurBackdrop != null)
                                            androidx.compose.ui.graphics.Color.Transparent
                                        else
                                            top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
                                                .surface
                                ) {
                                    TopDestination.entries.forEachIndexed { index, destination ->
                                        MiuixNavigationBarItem(
                                            modifier = Modifier.weight(1f),
                                            icon = destination.filledIcon,
                                            iconContent = { iconModifier, tint, description ->
                                                AppSymbolIcon(
                                                    destination.symbol, iconModifier, description, tint,
                                                    active = mainPagerState.selectedPage == index,
                                                )
                                            },
                                            label = stringResource(destination.labelRes),
                                            selected = mainPagerState.selectedPage == index,
                                            onClick = { mainPagerState.animateToPage(index) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                pagerContent(padding.calculateBottomPadding())
            }
        }
        return
    }
    if (useNavigationRail) {
        val startInsets =
            WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)
        val bottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        val railExpanded = appearanceSettings.navigationRailExpanded.toBoolean()
        val railState =
            rememberWideNavigationRailState(
                initialValue =
                    if (railExpanded) WideNavigationRailValue.Expanded
                    else WideNavigationRailValue.Collapsed
            )
        val expanded = railState.targetValue == WideNavigationRailValue.Expanded
        LaunchedEffect(railState.targetValue) {
            settingsManager.setAppearance { it.copy(navigationRailExpanded = expanded.toString()) }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) {
            Row {
                WideNavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    state = railState,
                    colors =
                        WideNavigationRailDefaults.colors()
                            .copy(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    windowInsets =
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
                    contentPadding = PaddingValues(vertical = 20.dp),
                    header = {
                        IconButton(
                            modifier = Modifier.padding(start = 24.dp),
                            onClick = {
                                scope.launch {
                                    if (expanded) railState.collapse() else railState.expand()
                                }
                            },
                        ) {
                            Icon(
                                if (expanded) Icons.AutoMirrored.Filled.MenuOpen
                                else Icons.Filled.Menu,
                                contentDescription =
                                    stringResource(
                                        if (expanded) R.string.nav_rail_collapse
                                        else R.string.nav_rail_expand
                                    ),
                            )
                        }
                    },
                ) {
                    TopDestination.entries.forEachIndexed { index, destination ->
                        val selected = mainPagerState.selectedPage == index
                        WideNavigationRailItem(
                            railExpanded = expanded,
                            selected = selected,
                            onClick = { if (!selected) mainPagerState.animateToPage(index) },
                            icon = { DestinationIcon(destination, selected) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
                Box(Modifier.weight(1f).consumeWindowInsets(startInsets)) {
                    pagerContent(bottomPadding)
                }
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                ShortNavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    windowInsets =
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                ) {
                    TopDestination.entries.forEachIndexed { index, destination ->
                        val selected = mainPagerState.selectedPage == index
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected) mainPagerState.animateToPage(index) },
                            icon = { DestinationIcon(destination, selected) },
                            label = {
                                Text(
                                    stringResource(destination.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            },
        ) { padding ->
            pagerContent(padding.calculateBottomPadding())
        }
    }
}

@Composable
private fun DestinationIcon(destination: TopDestination, selected: Boolean) {
    AppSymbolIcon(destination.symbol, active = selected)
}
