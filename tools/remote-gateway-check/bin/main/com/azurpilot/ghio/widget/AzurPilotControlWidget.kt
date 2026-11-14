package com.azurpilot.ghio.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.azurpilot.ghio.MainActivity
import com.azurpilot.ghio.R

/**
 * 4x2 全功能桌面控制面板小组件
 *
 * 遵循 Material Design 3 规范与系统壁纸动态取色 (Material You)。
 * 展示应用实例状态、当前运行任务、支持一键启停和刷新。
 *
 * 整块 RemoteViews 由 shape drawable 提供圆角与着色：MIUI 12.5（API 30）上
 * RemoteViews 用不了 `?attr` 矢量着色，Glance 的 cornerRadius 修饰符在
 * API 31 以下也不生效——所以所有圆角、底色、图标 tint 全部落成 drawable 资源
 *
 * The 4x2 full-featured AppWidget control panel.
 *
 * Follows Material Design 3 with the system wallpaper's dynamic color
 * (Material You). Shows the app instance status and the current task, with
 * one-tap start/stop and refresh.
 *
 * The whole RemoteViews surface gets its corners and tints from shape
 * drawables: on MIUI 12.5 (API 30) RemoteViews cannot use `?attr` vector
 * tints, and Glance's cornerRadius modifier is a no-op below API 31 — so
 * every corner, background, and icon tint lands in drawable resources.
 */
class AzurPilotControlWidget : GlanceAppWidget() {

    /** 组装并发布 RemoteViews 内容；状态经 [AzurPilotWidgetUpdater.currentState] 同步读取 / Assembles and publishes the RemoteViews content; the state is read synchronously via [AzurPilotWidgetUpdater.currentState]. */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                val state = AzurPilotWidgetUpdater.currentState(context)

                val openAppAction = actionStartActivity<MainActivity>()

                val toggleIntent = Intent(context, AzurPilotWidgetActionReceiver::class.java).apply {
                    action = AzurPilotWidgetActionReceiver.ACTION_TOGGLE_RUNNER
                }
                val toggleAction = actionSendBroadcast(toggleIntent)

                val refreshIntent = Intent(context, AzurPilotWidgetActionReceiver::class.java).apply {
                    action = AzurPilotWidgetActionReceiver.ACTION_REFRESH
                }
                val refreshAction = actionSendBroadcast(refreshIntent)

                // 外部卡片容器：24dp 圆角由 shape drawable 提供（cornerRadius 修饰符在
                // API 31 以下不生效，本机 MIUI 12.5 = API 30，只能靠 drawable 圆角）
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ImageProvider(R.drawable.widget_card_bg))
                        .clickable(openAppAction)
                        .padding(14.dp)
                ) {
                    Column(
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.Vertical.CenterVertically
                    ) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Vertical.CenterVertically
                        ) {
                            Image(
                                provider = ImageProvider(R.mipmap.ic_launcher),
                                contentDescription = null,
                                modifier = GlanceModifier.size(24.dp)
                            )
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            Text(
                                text = "AzurPilot",
                                style = TextStyle(
                                    color = GlanceTheme.colors.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            )
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            // MD3 AssistChip 样式的配置胶囊
                            Box(
                                modifier = GlanceModifier
                                    .background(ImageProvider(R.drawable.widget_pill_bg))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = state.config,
                                    style = TextStyle(
                                        color = GlanceTheme.colors.onSurfaceVariant,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Box(
                                modifier = GlanceModifier
                                    .size(28.dp)
                                    .background(ImageProvider(R.drawable.widget_inner_bg))
                                    .clickable(refreshAction)
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    provider = ImageProvider(R.drawable.ic_widget_refresh),
                                    contentDescription = context.getString(R.string.widget_refresh),
                                    modifier = GlanceModifier.fillMaxSize(),
                                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.height(8.dp))

                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .defaultWeight()
                                .background(ImageProvider(R.drawable.widget_inner_bg))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Column(
                                modifier = GlanceModifier.fillMaxSize(),
                                verticalAlignment = Alignment.Vertical.CenterVertically
                            ) {
                                Row(
                                    modifier = GlanceModifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Vertical.CenterVertically
                                ) {
                                    Image(
                                        provider = ImageProvider(state.statusLevel.dotDrawableRes),
                                        contentDescription = null,
                                        modifier = GlanceModifier.size(10.dp)
                                    )
                                    Spacer(modifier = GlanceModifier.width(6.dp))
                                    Text(
                                        text = state.statusText,
                                        style = TextStyle(
                                            color = GlanceTheme.colors.onSurface,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    )
                                }

                                Spacer(modifier = GlanceModifier.height(4.dp))

                                Row(
                                    modifier = GlanceModifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Vertical.CenterVertically
                                ) {
                                    Image(
                                        provider = ImageProvider(R.drawable.ic_widget_task),
                                        contentDescription = null,
                                        modifier = GlanceModifier.size(14.dp),
                                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                                    )
                                    Spacer(modifier = GlanceModifier.width(6.dp))
                                    Text(
                                        text = state.currentTask ?: context.getString(R.string.widget_task_idle),
                                        maxLines = 1,
                                        style = TextStyle(
                                            color = GlanceTheme.colors.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = GlanceModifier.height(8.dp))

                        // 底部按钮三态互斥：busy 时整个按钮不可点，防止并发启停
                        val buttonBackground = when {
                            state.busy -> R.drawable.widget_btn_muted
                            state.runnerAlive -> R.drawable.widget_btn_error
                            else -> R.drawable.widget_btn_primary
                        }
                        val buttonTextColor = when {
                            state.busy -> GlanceTheme.colors.onSurfaceVariant
                            state.runnerAlive -> GlanceTheme.colors.onErrorContainer
                            else -> GlanceTheme.colors.onPrimary
                        }
                        val buttonIcon = when {
                            state.busy -> R.drawable.ic_widget_hourglass
                            state.runnerAlive -> R.drawable.ic_widget_stop
                            else -> R.drawable.ic_widget_play
                        }
                        val buttonIconTint = when {
                            state.busy -> GlanceTheme.colors.onSurfaceVariant
                            state.runnerAlive -> GlanceTheme.colors.onErrorContainer
                            else -> GlanceTheme.colors.onPrimary
                        }
                        val buttonText = when {
                            state.busy -> context.getString(R.string.widget_action_busy)
                            state.runnerAlive -> context.getString(R.string.widget_action_stop)
                            else -> context.getString(R.string.widget_action_start)
                        }

                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .background(ImageProvider(buttonBackground))
                                .then(if (state.busy) GlanceModifier else GlanceModifier.clickable(toggleAction)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.Vertical.CenterVertically
                            ) {
                                Image(
                                    provider = ImageProvider(buttonIcon),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(18.dp),
                                    colorFilter = ColorFilter.tint(buttonIconTint)
                                )
                                Spacer(modifier = GlanceModifier.width(6.dp))
                                Text(
                                    text = buttonText,
                                    style = TextStyle(
                                        color = buttonTextColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 接收器，连接系统 AppWidget 框架与 Glance 视图
 *
 * The receiver bridging the system AppWidget framework and the Glance view.
 */
class AzurPilotControlWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AzurPilotControlWidget()
}
