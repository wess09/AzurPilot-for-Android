package com.azurpilot.ghio.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 保存游戏画面和分页容器的根坐标，让游戏手势避开上层横移拦截。
 *
 * Stores root coordinates for the game frame and pager to exclude game gestures from interception.
 */
internal class MainPagerGestureExclusion {
    /** 游戏画面的根坐标边界。 / Game-frame bounds in root coordinates. */
    var gameBounds: Rect? = null

    /** 分页容器的根坐标原点。 / Pager origin in root coordinates. */
    var pagerOrigin: Offset = Offset.Zero

    /** 判断分页坐标的触摸起点是否在游戏内。 / Reports whether a pager-local start is in the game. */
    fun contains(position: Offset): Boolean = gameBounds?.contains(position + pagerOrigin) == true
}

/** 提供当前主分页的游戏禁区。 / Provides the game exclusion region for the current main pager. */
internal val LocalMainPagerGestureExclusion = staticCompositionLocalOf<MainPagerGestureExclusion?> { null }

/**
 * 注册游戏画面实际边界，并在画面离开组合时清除禁区。
 *
 * Registers the actual game-frame bounds and clears the exclusion when the frame leaves composition.
 */
@Composable
internal fun Modifier.mainPagerGestureExclusion(): Modifier {
    val exclusion = LocalMainPagerGestureExclusion.current
    DisposableEffect(exclusion) {
        onDispose { exclusion?.gameBounds = null }
    }
    return onGloballyPositioned { exclusion?.gameBounds = it.boundsInRoot() }
}
