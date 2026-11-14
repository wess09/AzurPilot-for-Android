@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.azurpilot.ghio.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.ui.navigation.AppNavigator
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

/**
 * 仅限制预测式返回的预览幅度；完整 push/pop 仍由 Miuix 默认转场处理。
 *
 * Limits predictive-back preview to a short peek. The original transition still owns the full
 * programmatic/committed settle and cancellation spring.
 */
private val ShallowPredictiveBackTransition: NavTransition =
    object : NavTransition by NavTransitions.MiuixDefault {
        override val predictiveBackPreviewFraction: Float = 0.13f
    }

/**
 * 在主线程组合统一导航，使用 KernelSU 的 Miuix 默认转场与设备圆角。
 *
 * 手势进度、取消、提交和页面生命周期由框架维护；根页面返回交给系统。 滑动关闭方向随 RTL 镜像，子页面滚动与返回手势的冲突由框架协调。
 *
 * Composes navigation on the main thread using KernelSU's Miuix default transition and device
 * corner radius. The framework owns gesture progress, cancellation, completion, and entry
 * lifecycle; root back falls through to the system. Swipe dismissal mirrors in RTL, with scroll and
 * gesture conflicts coordinated by the framework.
 */
@Composable
fun AppNavigation(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    content: NavEntryBuilder.(NavSwipeDirection) -> Unit,
) {
    val manager: com.azurpilot.ghio.settings.AppSettingsManager = org.koin.compose.koinInject()
    val appearance by manager.appearance.collectAsStateWithLifecycle()
    val swipeDirection =
        if (!appearance.enableSwipeDismiss.toBoolean()) NavSwipeDirection.None
        else if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
            NavSwipeDirection.LeftToRight
        } else {
            NavSwipeDirection.RightToLeft
        }
    NavDisplay(
        backStack = navigator.backStack,
        modifier = modifier,
        onBack = navigator::pop,
        transition = ShallowPredictiveBackTransition,
        effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
    ) {
        content(swipeDirection)
    }
}
