@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.azurpilot.ghio.ui.ksu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.azurpilot.ghio.theme.ksu.LocalUiMode
import com.azurpilot.ghio.theme.ksu.UiMode
import com.azurpilot.ghio.ui.ksu.material.*
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 使用 KSU 的两种页面骨架，业务页面只提供标题和内容。
 *
 * Uses KSU's two page scaffolds with business titles and content.
 */
@Composable
fun AppPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    collapsible: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        val scrollBehavior = if (collapsible) MiuixScrollBehavior() else null
        val backdrop =
            com.azurpilot.ghio.ui.ksu.util.rememberBlurBackdrop(
                com.azurpilot.ghio.theme.ksu.LocalEnableBlur.current
            )
        top.yukonga.miuix.kmp.basic.Scaffold(
            modifier =
                if (scrollBehavior != null) {
                    modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                } else modifier,
            topBar = {
                com.azurpilot.ghio.ui.ksu.util.BlurredBar(backdrop) {
                    top.yukonga.miuix.kmp.basic.TopAppBar(
                        title = title,
                        color =
                            if (backdrop != null) androidx.compose.ui.graphics.Color.Transparent
                            else top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface,
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { if (onBack != null) AppBackButton(onBack) },
                        actions = actions,
                    )
                }
            },
            popupHost = {},
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal),
            content = { padding ->
                androidx.compose.foundation.layout.Box(
                    Modifier.then(
                        if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
                    )
                ) {
                    content(padding)
                }
            },
        )
    } else {
        val scrollBehavior =
            if (collapsible) {
                TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
            } else null
        ExpressiveScaffold(
            modifier =
                if (scrollBehavior != null) {
                    modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                } else modifier,
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(title) },
                    navigationIcon = { if (onBack != null) AppBackButton(onBack) },
                    actions = actions,
                    colors = expressiveTopAppBarColors(),
                    scrollBehavior = scrollBehavior,
                )
            },
            contentWindowInsets =
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            content = content,
        )
    }
}

/** 返回按钮由 KSU 对应主题提供。 / Uses KSU's themed back button. */
@Composable
fun AppBackButton(onClick: () -> Unit) {
    if (LocalUiMode.current == UiMode.Miuix) {
        top.yukonga.miuix.kmp.basic.IconButton(onClick = onClick) {
            top.yukonga.miuix.kmp.basic.Icon(
                imageVector = top.yukonga.miuix.kmp.icon.MiuixIcons.Back,
                contentDescription =
                    androidx.compose.ui.res.stringResource(com.azurpilot.ghio.R.string.ap_back),
                tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackground,
            )
        }
    } else
        TopBarBackButton(
            onClick,
            contentDescription =
                androidx.compose.ui.res.stringResource(com.azurpilot.ghio.R.string.ap_back),
        )
}

/** 复用 KSU 设置分组：Miuix 卡片或 Material 分段列表。 / Reuses KSU settings groups. */
@Composable
fun AppSettingsGroup(
    modifier: Modifier = Modifier,
    title: String = "",
    content: SegmentedColumnScope.() -> Unit,
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        val entries = SegmentedColumnScope().apply(content).entries.filter { it.visible }
        Column(modifier) {
            if (title.isNotEmpty()) top.yukonga.miuix.kmp.basic.SmallTitle(title)
            top.yukonga.miuix.kmp.basic.Card(modifier = Modifier.fillMaxWidth()) {
                entries.forEach { entry -> key(entry.key) { entry.content() } }
            }
        }
    } else SegmentedColumn(modifier = modifier, title = title, content = content)
}

/** KSU 根容器负责 Miuix 的全局弹层。 / KSU root container hosts global Miuix overlays. */
@Composable
fun AppRootScaffold(content: @Composable () -> Unit) {
    // 共享弹层宿主保持稳定，主题切换不会重建 NavDisplay。
    top.yukonga.miuix.kmp.basic.Scaffold(
        containerColor =
            if (LocalUiMode.current == UiMode.Miuix)
                top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainer
    ) {
        content()
    }
}
