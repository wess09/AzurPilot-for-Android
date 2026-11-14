package com.azurpilot.ghio.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

/**
 * 在主线程维护唯一页面栈；相同目的地复用已有条目，防止快速连点产生重复身份。
 *
 * Owns the single page stack on the main thread. Reuses existing destinations to prevent duplicate
 * entry identities when users tap repeatedly.
 */
@Stable
class AppNavigator internal constructor(val backStack: NavBackStack) {
    /** 当前目的地。 / The current destination. */
    val current: AppRoute
        get() = backStack.last() as AppRoute

    /** 推入目的地；已有条目时回到该条目。 / Pushes a destination or returns to its existing entry. */
    fun push(route: AppRoute) {
        val existing = backStack.indexOf(route)
        if (existing < 0) {
            backStack.add(route)
        } else if (existing < backStack.lastIndex) {
            backStack.subList(existing + 1, backStack.size).clear()
        }
    }

    /** 返回一级，保留根页面。 / Pops one entry while preserving the root page. */
    fun pop() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    /** 关闭全部详情，根页面状态由导航框架保留。 / Closes details while retaining root page state. */
    fun returnToMain() {
        if (backStack.size > 1) backStack.subList(1, backStack.size).clear()
    }
}

/**
 * 创建可跨配置变更与进程重建恢复的页面栈，显式使用完整路由类型的序列化器。
 *
 * Creates a stack restored across configuration changes and process recreation, using the
 * serializer for the complete route hierarchy explicitly.
 */
@Composable
fun rememberAppNavigator(): AppNavigator {
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Main)
    return remember(backStack) { AppNavigator(backStack) }
}
