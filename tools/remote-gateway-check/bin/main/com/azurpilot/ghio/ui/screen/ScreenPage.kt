package com.azurpilot.ghio.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.constant.DefaultDisplayConfig
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.service.HostState
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.components.ExpressiveLoadingIndicator
import com.azurpilot.ghio.ui.components.ToolSlotColumn
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppText as Text
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/**
 * `setFixedSize` 必须延后一拍（m0 PreviewSurface 同款处理）： surfaceCreated 里同步调会被 attach 流程吞掉，画面会按错误比例贴上来
 *
 * `setFixedSize` must wait one beat (same treatment as the m0 PreviewSurface): calling it
 * synchronously in surfaceCreated gets swallowed by the attach flow and the frame is pasted at the
 * wrong aspect ratio.
 */
private const val FIXED_SIZE_DELAY_MS = 50L

/**
 * 操作说明的停留时长：够读完一句，又不至于在操作区赖着不走
 *
 * How long the usage hint stays: long enough to read one sentence, short enough not to loiter over
 * the touch area.
 */
private const val HINT_VISIBLE_MS = 4000L

/**
 * 虚拟屏宽高比；画面容器与触摸换算共用一份，别两处各算各的
 *
 * Virtual-display aspect ratio; the frame container and the touch mapping share this one value —
 * never compute it twice.
 */
private val VIDEO_ASPECT = DefaultDisplayConfig.WIDTH.toFloat() / DefaultDisplayConfig.HEIGHT

/**
 * 工具槽竖排的宽度
 *
 * 140dp 是按"画面对少"倒推的上限：横屏可用宽 790dp 上下，画面按高度撑满要 571dp， 余下 190dp 全给侧栏也够——标签在 124dp 内折行，画面尺寸一点不减
 *
 * Width of the vertical tool-slot column.
 *
 * 140dp is a ceiling derived from "least frame loss": landscape offers ~790dp of width, the
 * height-fitted frame needs 571dp, and giving the whole remaining ~190dp to the side rail suffices
 * — labels wrap within 124dp while the frame keeps its full size.
 */
private val ToolColumnWidth = 140.dp

/**
 * 虚拟屏上的手动操作；坐标由 UI 换算到虚拟屏坐标系后随动作一起上报
 *
 * Manual touch actions on the virtual display; the UI maps coordinates into the virtual-display
 * space and reports them together with the action.
 */
private enum class PreviewTouchAction {
    Down,
    Move,
    Up,
}

/**
 * 虚拟屏 tab：实时画面 + 直接触摸操作 + 半自动点击 / 活动剧情两个工具槽
 *
 * 进页即转横屏（虚拟屏本身是横的，竖着看只有中间一条），退出还原用户原本的方向设置。 画面走 native bridge_preview 通道（AIDL
 * setMonitorSurface，零拷贝）； 触摸按 contain 缩放反算回虚拟屏坐标后注入，落在黑边上直接丢弃
 *
 * 版面上不挂 TopAppBar：横屏下竖向只有 390dp 上下，顶栏要吃掉 64dp 的画面高度， 而这一页的正文就是画面本身——当前所在页由导航栏的选中态交代，够了
 *
 * 两个工具槽留在本页而不是主页：它们打的就是这块虚拟屏，入口要挨着画面才连得上
 *
 * Renders the virtual-display tab: live frame + direct touch input + the two tool slots (semi-auto
 * click / event story).
 *
 * Entering the page rotates to landscape (the virtual display itself is landscape; portrait would
 * show a narrow stripe) and leaving restores the user's original orientation setting. The frame
 * flows through the native bridge_preview channel (AIDL setMonitorSurface, zero-copy); touches are
 * mapped back through the contain scaling into virtual-display coordinates and injected, with hits
 * landing on the letterbox discarded.
 *
 * No TopAppBar in the layout: landscape leaves only ~390dp vertically and a top bar would eat 64dp
 * of frame height, while the body of this page is the frame itself — the current page is already
 * conveyed by the nav bar selection.
 *
 * The two tool slots stay on this page rather than the main page: they drive this very virtual
 * display, so the entries belong next to the frame.
 *
 * @param active 是否为 pager 当前页；控制 SurfaceView 的创建 / whether this is the pager's current page;
 *   controls SurfaceView creation
 */
@Composable
fun ScreenPage(
    active: Boolean,
    modifier: Modifier = Modifier,
    hostState: HostState = koinInject(),
    runController: AzurPilotRunController = koinInject(),
) {
    val run by runController.state.collectAsStateWithLifecycle()

    var surfaceReady by remember { mutableStateOf(false) }
    // 空屏态：虚拟屏是裸建的（startVirtualDisplay 只建屏），屏上除 AzurPilot 拉起的游戏
    // 没有别的东西，所以"跑起来之前一片纯黑"是正常的，不是坏了——那就把这件事说清楚。
    // 闩用"本次环境起跑过没有"：起过之后可能停在"没在跑但画面还在"的状态，
    // 那时再把卡片盖上去就是挡真画面了
    var everRanThisSession by rememberSaveable { mutableStateOf(false) }
    val displayIdle = !run.runnerAlive && !run.toolAlive && !everRanThisSession
    LaunchedEffect(run.runnerAlive, run.toolAlive) {
        if (run.runnerAlive || run.toolAlive) everRanThisSession = true
    }
    // 操作说明：讲一次就够，不在操作区常驻。等第一次有画面了再讲——没有画面时无操作可讲
    var hintShown by rememberSaveable { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(surfaceReady, displayIdle) {
        if (surfaceReady && !displayIdle && !hintShown) {
            hintShown = true
            hintVisible = true
            delay(HINT_VISIBLE_MS)
            hintVisible = false
        }
    }
    val currentAttach by rememberUpdatedState(hostState::attachPreviewSurface)
    val currentDetach by rememberUpdatedState(hostState::detachPreviewSurface)
    val scope = rememberCoroutineScope()

    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier.weight(1f)
                    .fillMaxHeight()
                    // 舞台底色取容器档，不再铺满整页纯黑：画面按 contain 居中后两侧/上下的留白归它，
                    // 顺便把"画面到哪儿为止"这条边界交代出来
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            // 只在可见时接入预览，TextureView 随 Compose 分页移动和裁剪，避免独立图层残留
            if (active) {
                // contain：画面按虚拟屏比例居中缩放，两侧/上下的留白由外层舞台色兜住。
                // 圆角与离屏画面同形——这是块"屏幕"，M3 的媒体容器就是这个形状
                val screenPreviewDesc = stringResource(R.string.screen_preview_desc)
                Box(
                    modifier =
                        Modifier.padding(AppTokens.Spacing.md)
                            .aspectRatio(VIDEO_ASPECT)
                            .clip(MaterialTheme.shapes.medium)
                            // 屏幕自身是黑的：首帧到达前那一层不该透出舞台色
                            .background(Color.Black)
                            .semantics { contentDescription = screenPreviewDesc }
                            .previewTouchInput { x, y, action ->
                                when (action) {
                                    PreviewTouchAction.Down -> hostState.touchDown(x, y)
                                    PreviewTouchAction.Move -> hostState.touchMove(x, y)
                                    PreviewTouchAction.Up -> hostState.touchUp(x, y)
                                }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            android.view.TextureView(context).apply {
                                // TextureView 属于应用图层，分页位移与裁剪能同步作用到预览。
                                surfaceTextureListener =
                                    object : android.view.TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(
                                            texture: android.graphics.SurfaceTexture,
                                            width: Int,
                                            height: Int,
                                        ) {
                                            texture.setDefaultBufferSize(
                                                DefaultDisplayConfig.WIDTH,
                                                DefaultDisplayConfig.HEIGHT,
                                            )
                                            val surface = android.view.Surface(texture)
                                            tag = surface
                                            currentAttach(surface)
                                            surfaceReady = true
                                        }

                                        override fun onSurfaceTextureSizeChanged(
                                            texture: android.graphics.SurfaceTexture,
                                            width: Int,
                                            height: Int,
                                        ) = Unit

                                        override fun onSurfaceTextureUpdated(
                                            texture: android.graphics.SurfaceTexture
                                        ) = Unit

                                        override fun onSurfaceTextureDestroyed(
                                            texture: android.graphics.SurfaceTexture
                                        ): Boolean {
                                            currentDetach()
                                            surfaceReady = false
                                            (tag as? android.view.Surface)?.release()
                                            tag = null
                                            return true
                                        }
                                    }
                            }
                        },
                        onRelease = { view ->
                            view.surfaceTextureListener = null
                            currentDetach()
                            surfaceReady = false
                            (view.tag as? android.view.Surface)?.release()
                            view.tag = null
                        },
                    )
                    if (!surfaceReady) {
                        // 首次接入：转圈 + 说明
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.align(Alignment.Center),
                        ) {
                            Column(
                                modifier =
                                    Modifier.padding(
                                        horizontal = AppTokens.Spacing.lg,
                                        vertical = AppTokens.Spacing.md,
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                            ) {
                                ExpressiveLoadingIndicator(
                                    modifier = Modifier.size(AppTokens.IconSize.md)
                                )
                                Text(
                                    text = stringResource(R.string.screen_waiting),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (displayIdle) {
                        PreviewIdleCard(modifier = Modifier.align(Alignment.Center))
                    } else {
                        // 操作说明只在第一次有画面时露一次，随后自隐：常驻会在操作区压出一条横带，
                        // 挡住的正是它要讲解的那块画面
                        PreviewHintPill(
                            visible = hintVisible,
                            modifier =
                                Modifier.align(Alignment.BottomCenter)
                                    .padding(AppTokens.Spacing.sm),
                        )
                    }
                }
            }
        }

        // 工具槽竖排在画面右侧的留白里，不占竖向：横屏下竖向只有 345dp 上下，
        // 压在画面下沿的一版要吃掉 78dp（约 23%），而横向空着近 190dp 没人用
        ToolSlotColumn(
            run = run,
            onToolStart = { runController.startTool(it) },
            onToolStop = { runController.stopTool() },
            modifier =
                Modifier.fillMaxHeight()
                    .width(ToolColumnWidth)
                    .padding(
                        vertical = AppTokens.Spacing.md,
                        horizontal = AppTokens.Spacing.sm,
                    ),
        )
    }
}

/**
 * 空屏态：屏是活的，但上面什么都没有
 *
 * 与其给一块纯黑让人猜"是不是坏了"，不如直说在等什么
 *
 * Renders the idle state: the display is alive but nothing is on it.
 *
 * Rather than a slab of black that makes people guess "is it broken", it states outright what is
 * being waited for.
 */
@Composable
private fun PreviewIdleCard(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = AppTokens.Spacing.xl,
                    vertical = AppTokens.Spacing.lg,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppTokens.IconSize.lg),
            )
            Text(
                text = stringResource(R.string.screen_idle_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.screen_idle_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 操作说明气泡：垫一层同主题实底，画面内容是任意的，裸文字压上去读不出来
 *
 * 独立成件是为了离开 Row 的作用域——页面根容器现在是 Row，直接写会命中 `RowScope.AnimatedVisibility` 那个重载
 *
 * Renders the usage-hint pill: backed by a solid same-theme layer, because the frame content is
 * arbitrary and bare text pressed onto it is unreadable.
 *
 * Standalone on purpose to escape the Row scope — the page root is now a Row, and writing it inline
 * would resolve to the `RowScope.AnimatedVisibility` overload.
 */
@Composable
private fun PreviewHintPill(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Text(
                text = stringResource(R.string.screen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = AppTokens.Spacing.md,
                        vertical = AppTokens.Spacing.xs,
                    ),
            )
        }
    }
}

/**
 * 把手指位置换算到虚拟屏坐标再上报（m0 previewTouchInput 同款）
 *
 * 本修饰符就挂在画面那块 Box 上（contain 由 aspectRatio 在外层已经做完）， 所以 size 即画面尺寸，不必再减一遍留白
 *
 * Maps finger positions into virtual-display coordinates before reporting (same approach as the m0
 * previewTouchInput).
 *
 * The modifier sits on the frame Box itself (contain is already done by aspectRatio outside), so
 * `size` is the frame size and no letterbox needs subtracting.
 */
private fun Modifier.previewTouchInput(
    onTouch: (x: Int, y: Int, action: PreviewTouchAction) -> Unit
): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: continue
                val action =
                    when (event.type) {
                        PointerEventType.Press -> PreviewTouchAction.Down
                        PointerEventType.Move ->
                            if (change.pressed) PreviewTouchAction.Move else null
                        PointerEventType.Release -> PreviewTouchAction.Up
                        else -> null
                    }
                if (action != null) {
                    // 宽高比由 aspectRatio 保证，两边算出来的比例只差舍入，取小的那个更保险
                    val scale =
                        minOf(
                            size.width / DefaultDisplayConfig.WIDTH.toFloat(),
                            size.height / DefaultDisplayConfig.HEIGHT.toFloat(),
                        )
                    val vx = (change.position.x / scale).toInt()
                    val vy = (change.position.y / scale).toInt()
                    if (
                        vx in 0 until DefaultDisplayConfig.WIDTH &&
                            vy in 0 until DefaultDisplayConfig.HEIGHT
                    ) {
                        onTouch(vx, vy, action)
                    }
                }
                change.consume()
            }
        }
    }
