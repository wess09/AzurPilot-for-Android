package com.azurpilot.ghio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.azurpilot.ghio.R
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.theme.AzurPilotTheme
import com.azurpilot.ghio.ui.ksu.MainPagerState
import com.azurpilot.ghio.ui.ksu.rememberMainPagerState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

/**
 * 验证三种分页模式只排除游戏画面，仍允许留白翻页和底栏切换。
 *
 * Verifies that all three pager modes exclude only the game frame while margins and tabs still work.
 */
@RunWith(AndroidJUnit4::class)
class VirtualScreenPagerInstrumentedTest {
    private object TestRoot : NavKey
    @get:Rule val compose = createComposeRule()

    @Test fun nativeModePreservesGameAndMarginGestures() = verifyGestures(0)
    @Test fun crossAxisModePreservesGameAndMarginGestures() = verifyGestures(1)
    @Test fun tapToHaltModePreservesGameAndMarginGestures() = verifyGestures(2)
    @Test fun gameTouchCancelsPendingWheelMotion() = verifyGestures(1, checkPendingMotion = true)

    private fun verifyGestures(mode: Int, checkPendingMotion: Boolean = false) {
        val manager = org.koin.core.context.GlobalContext.get().get<AppSettingsManager>()
        runBlocking { withTimeout(10000) { manager.loaded.first { it } } }
        val previous = manager.appearance.value.pagerInterceptionMode
        runBlocking { manager.setAppearance { it.copy(pagerInterceptionMode = mode.toString()) } }
        runBlocking { withTimeout(10000) { manager.appearance.first { it.pagerInterceptionMode == mode.toString() } } }
        lateinit var main: MainPagerState
        var gameMoves = 0
        try {
            compose.setContent {
                NavDisplay(
                    remember { navBackStackOf(TestRoot) }
                ) {
                    entry<TestRoot> {
                        AzurPilotTheme {
                            main = rememberMainPagerState(
                                rememberPagerState(initialPage = TopDestination.Screen.ordinal) {
                                    TopDestination.entries.size
                                }
                            )
                            AppNavigationScaffold(main, false, remember { SnackbarHostState() }) { page, _ ->
                                Box(Modifier.fillMaxSize().testTag("page-$page")) {
                                    if (page == TopDestination.Screen.ordinal) {
                                        Box(Modifier.align(Alignment.Center).padding(16.dp)) {
                                            Box(
                                                Modifier.size(200.dp, 100.dp)
                                                    .mainPagerGestureExclusion()
                                                    .background(Color.Black)
                                                    .testTag("game")
                                                    .pointerInput(Unit) {
                                                        awaitPointerEventScope {
                                                            while (true) {
                                                                val event = awaitPointerEvent()
                                                                event.changes.forEach {
                                                                    if (it.pressed && it.position != it.previousPosition) {
                                                                        gameMoves++
                                                                    }
                                                                    it.consume()
                                                                }
                                                            }
                                                        }
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (checkPendingMotion) {
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("page-2").performMouseInput {
                    moveTo(Offset(width * .1f, height * .1f))
                    scroll(-.25f, ScrollWheel.Horizontal)
                }
                compose.mainClock.advanceTimeBy(32)
                compose.runOnIdle {
                    assertTrue(main.pagerState.isScrollInProgress)
                    assertTrue(kotlin.math.abs(main.pagerState.currentPageOffsetFraction) > 0f)
                }
                compose.onNodeWithTag("game").performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(32)
                var stoppedPosition = 0f
                compose.runOnIdle {
                    assertFalse(main.pagerState.isScrollInProgress)
                    stoppedPosition = main.pagerState.currentPage + main.pagerState.currentPageOffsetFraction
                }
                compose.mainClock.advanceTimeBy(1000)
                compose.runOnIdle {
                    assertEquals(
                        stoppedPosition,
                        main.pagerState.currentPage + main.pagerState.currentPageOffsetFraction,
                        .0001f,
                    )
                }
                compose.onNodeWithTag("game").performTouchInput { up() }
                return
            }
            // 两个方向都只操作游戏；第二次手势也不能沿用第一次的分页状态。
            compose.onNodeWithTag("game").performTouchInput { swipeRight() }
            compose.onNodeWithTag("game").performTouchInput { swipeLeft() }
            // 起点在游戏内时，移出画面边界也仍归游戏处理。
            compose.onNodeWithTag("game").performTouchInput {
                swipe(center, Offset(width * 1.2f, center.y))
            }
            compose.onNodeWithTag("game").performTouchInput {
                swipe(center, Offset(-width * .2f, center.y))
            }
            compose.runOnIdle {
                assertEquals(TopDestination.Screen.ordinal, main.pagerState.settledPage)
                assertTrue("Game must receive drag movement", gameMoves > 0)
            }
            // 从游戏上方留白起手，即使在虚拟屏页也仍可切回配置页。
            compose.onNodeWithTag("page-2").performTouchInput {
                swipe(Offset(width * .1f, height * .1f), Offset(width * .9f, height * .1f))
            }
            compose.waitUntil(10000) { main.pagerState.settledPage == TopDestination.AzurPilot.ordinal }
            // 从配置页正常进入虚拟屏；离开后禁区不会污染配置页。
            compose.onNodeWithTag("page-1").performTouchInput { swipeLeft() }
            compose.waitUntil(10000) { main.pagerState.settledPage == TopDestination.Screen.ordinal }
            // 起点在侧边留白时，即使横穿游戏区域也保持翻页。
            compose.onNodeWithTag("page-2").performTouchInput {
                swipe(Offset(width * .05f, height * .5f), Offset(width * .95f, height * .5f))
            }
            compose.waitUntil(10000) { main.pagerState.settledPage == TopDestination.AzurPilot.ordinal }
            compose.onNodeWithTag("page-1").performTouchInput { swipeLeft() }
            compose.waitUntil(10000) { main.pagerState.settledPage == TopDestination.Screen.ordinal }
            // 从紧邻画面的 16dp 外边距内起手，也应当正常翻页。
            val gameBounds = compose.onNodeWithTag("game").fetchSemanticsNode().boundsInRoot
            val pageBounds = compose.onNodeWithTag("page-2").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("page-2").performTouchInput {
                val start = Offset(
                    gameBounds.left - pageBounds.left - gameBounds.width * .04f,
                    gameBounds.center.y - pageBounds.top,
                )
                swipe(start, Offset(width * .95f, start.y))
            }
            compose.waitUntil(10000) { main.pagerState.settledPage == TopDestination.AzurPilot.ordinal }
            compose.onNodeWithTag("page-1").performTouchInput { swipeLeft() }
            compose.waitUntil(10000) { main.pagerState.settledPage == TopDestination.Screen.ordinal }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithText(context.getString(R.string.nav_azurpilot)).performClick()
            compose.waitUntil(10000) { main.pagerState.settledPage == TopDestination.AzurPilot.ordinal }
        } finally {
            compose.mainClock.autoAdvance = true
            runBlocking { manager.setAppearance { it.copy(pagerInterceptionMode = previous) } }
        }
    }
}
