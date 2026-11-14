package com.azurpilot.ghio.ui.azurpilot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.ApValue
import com.azurpilot.ghio.proot.AzurPilotStatus
import com.azurpilot.ghio.proot.AzurPilotTaskState
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.theme.AzurPilotTheme
import com.azurpilot.ghio.ui.ksu.AppIcon as Icon
import com.azurpilot.ghio.ui.ksu.AppSurface as Surface
import com.azurpilot.ghio.ui.ksu.AppText as Text
import com.azurpilot.ghio.ui.ksu.AppTextButton as TextButton

/**
 * 宽屏（横屏/分屏/平板）下的内容最大宽度
 *
 * 全宽表单在大屏上行长失控、扫读困难；MD3 与小米大屏规范都要求列表/输入类组件有最大宽度。 设备竖屏（约 390dp）不受影响，横屏（约 870dp）起内容居中收窄。
 *
 * The maximum content width on wide screens (landscape / split-screen / tablets).
 *
 * Full-width forms lose control of line length on large screens and become hard to scan; both the
 * MD3 and the Xiaomi large-screen guidelines require a maximum width for list/input components.
 * Device portrait (~390 dp) is unaffected; from landscape (~870 dp) the content centers and narrows.
 */
val ApMaxContentWidth = 640.dp

/**
 * 内容宽度上限 + 在剩余空间里居中；给分区级容器用
 *
 * Caps the content width and centers it in the remaining space; for section-level containers.
 */
fun Modifier.apContentWidth(): Modifier = this.fillMaxWidth()

/**
 * 分区内容的标准容器：统一内边距与行距，各分区不再各写一遍
 *
 * 自带竖向滚动与 [apContentWidth]，因此分区实现里不要再套一层滚动容器。
 *
 * The standard container for section content: unified padding and row spacing so the sections stop
 * re-writing it.
 *
 * It already scrolls vertically and applies [apContentWidth], so section implementations must not
 * wrap another scroll container around it.
 *
 * @param modifier 应用于容器的修饰符 / the modifier applied to the container
 * @param content 容器内容 / the container content
 */
@Composable
fun ApSectionColumn(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .apContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = AppTokens.Spacing.lg,
                    end = AppTokens.Spacing.lg,
                    top = AppTokens.Spacing.sm,
                    bottom = AppTokens.Spacing.xl,
                ),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        content = content,
    )
}

/**
 * 页面借顶栏的一个动作位
 *
 * 任务配置页的搜索条在内容里，上滑就跟着滚走；滚走之后要让顶栏出现一个搜索图标把它找回来。 那个图标画在顶栏上，而顶栏归外壳管，所以需要一处「页面写入、外壳读取」的位置。
 *
 * A top-bar action slot a page borrows.
 *
 * The task-config page's search bar lives in the content and scrolls away with it; once gone, the
 * top bar should show a search icon to bring it back. That icon is drawn in the top bar, which the
 * shell owns — hence a place the page writes and the shell reads.
 */
class ApTopBarAction {
    /** 顶栏动作位当前是否显示 / Whether the borrowed top-bar action is currently shown. */
    var visible by mutableStateOf(false)

    /** 动作被点时的回调，由页面写入 / The click callback, written by the page. */
    var onClick: (() -> Unit)? = null
}

/**
 * 空态：说清「为什么是空的」，而不是只给一句「暂无数据」
 *
 * The empty state: it explains *why* the list is empty instead of a bare "no data".
 *
 * @param icon 空态图标 / the empty-state icon
 * @param title 一句话结论 / the one-line conclusion
 * @param hint 可选的下一步提示 / the optional next-step hint
 */
@Composable
fun ApEmptyState(icon: ImageVector, title: String, hint: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppTokens.Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(AppTokens.IconSize.lg),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 错误态：带一个重试入口——只说失败而不给下一步，用户只能干等
 *
 * The error state with a retry entry — reporting failure without a next step just leaves the user
 * waiting.
 *
 * @param message 错误文案 / the error message
 * @param onRetry 重试回调；为 null 时不显示按钮 / the retry callback; no button when null
 */
@Composable
fun ApErrorState(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppTokens.Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        onRetry?.let {
            TextButton(onClick = it) { Text(stringResource(R.string.ap_retry)) }
        }
    }
}

/**
 * 状态标签：把一句话压成一个可扫的色块
 *
 * A status pill: a sentence compressed into one scannable colored chip.
 *
 * @param text 标签文案 / the pill text
 * @param container 底色 / the container color
 * @param content 前景色 / the content color
 */
@Composable
fun ApStatusPill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier =
                Modifier.padding(
                    horizontal = AppTokens.Spacing.sm,
                    vertical = AppTokens.Spacing.xxs,
                ),
        )
    }
}

/**
 * 实例状态 → 颜色；四档语义色沿用主题里的 success/warning 扩展
 *
 * Instance status → color; the four semantic tiers reuse the theme's success/warning color
 * extensions.
 */
@Composable
fun instanceStatusColor(status: AzurPilotStatus): Color =
    when (status) {
        AzurPilotStatus.Running -> AzurPilotTheme.palette.success
        AzurPilotStatus.Error -> MaterialTheme.colorScheme.error
        AzurPilotStatus.Updating -> AzurPilotTheme.palette.warning
        AzurPilotStatus.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** 实例状态 → 展示文案 / Maps an instance status to its display text. */
@Composable
fun instanceStatusText(status: AzurPilotStatus): String =
    stringResource(
        when (status) {
            AzurPilotStatus.Running -> R.string.ap_status_running
            AzurPilotStatus.Stopped -> R.string.ap_status_stopped
            AzurPilotStatus.Error -> R.string.ap_status_error
            AzurPilotStatus.Updating -> R.string.ap_status_updating
        }
    )

/** 任务状态 → 展示文案 / Maps a task state to its display text. */
@Composable
fun taskStateText(state: AzurPilotTaskState): String =
    stringResource(
        when (state) {
            AzurPilotTaskState.Running -> R.string.ap_task_running
            AzurPilotTaskState.Pending -> R.string.ap_task_pending
            AzurPilotTaskState.Waiting -> R.string.ap_task_waiting
        }
    )

/** 任务状态 → 语义色 / Maps a task state to its semantic color. */
@Composable
fun taskStateColor(state: AzurPilotTaskState): Color =
    when (state) {
        AzurPilotTaskState.Running -> AzurPilotTheme.palette.success
        AzurPilotTaskState.Pending -> MaterialTheme.colorScheme.primary
        AzurPilotTaskState.Waiting -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * 资源名的显示文案
 *
 * 网关 `overview.resources[].label` 取的是 `translate('<Name>._info.name')`，而翻译表里没有 `Dashboard`/`Oil`
 * 这些键，实测返回的就是字面量 `"name"`——所以名字必须由客户端出， WebUI 也是这么做的（`resourceLabels` 表）。
 *
 * Display text for a resource name.
 *
 * The gateway's `overview.resources[].label` is fetched via `translate('<Name>._info.name')`, but
 * the translation table has no `Dashboard`/`Oil` keys and returns the literal `"name"` in practice
 * — so the name must come from the client side, which is exactly what the WebUI does (its
 * `resourceLabels` table).
 *
 * @param name 网关下发的资源键名 / the resource key name from the gateway
 * @return 已翻译的资源名；未知键名原样返回 / the translated resource name, or the key itself when unknown
 */
@Composable
fun resourceLabel(name: String): String {
    val res =
        when (name) {
            "Oil" -> R.string.ap_resource_oil
            "Coin" -> R.string.ap_resource_coin
            "Gem" -> R.string.ap_resource_gem
            "Cube" -> R.string.ap_resource_cube
            "Pt" -> R.string.ap_resource_pt
            "ActionPoint" -> R.string.ap_resource_action_point
            "YellowCoin" -> R.string.ap_resource_yellow_coin
            "PurpleCoin" -> R.string.ap_resource_purple_coin
            "Core" -> R.string.ap_resource_core
            "Medal" -> R.string.ap_resource_medal
            "Merit" -> R.string.ap_resource_merit
            "GuildCoin" -> R.string.ap_resource_guild_coin
            "Chip" -> R.string.ap_resource_chip
            else -> null
        }
    return if (res != null) stringResource(res) else name
}

/**
 * 数值的显示规则
 *
 * 服务端把「没记录过」表达成 `null` 或 0 + 哨兵时间戳；直接显示 0 会让人以为真的没资源， 所以零值一律显示成占位符。
 *
 * How a resource value is displayed.
 *
 * The server expresses "never recorded" as `null` or 0 with a sentinel timestamp; showing a bare 0
 * would read as genuinely zero resources, so a zero value always renders as a placeholder. Values
 * keep one decimal only when they actually have a fraction.
 *
 * @param value 资源数值 / the resource value
 * @param recorded 服务端是否记录过该资源 / whether the server has ever recorded this resource
 * @return 显示文本，如 `"—"`、`"123"`、`"12.5"` / the display text, e.g. `"—"`, `"123"`, or `"12.5"`
 */
fun formatResource(value: Double?, recorded: Boolean): String {
    if (!recorded || value == null) return "—"
    val rounded = value.toLong()
    return if (rounded.toDouble() == value) rounded.toString() else String.format("%.1f", value)
}

/**
 * 记录时间是不是哨兵（`2020-01-01 00:00:00` 表示从未同步过）
 *
 * Whether the record time is the sentinel (`2020-01-01 …` means never synced).
 */
fun isRecorded(record: String?): Boolean = record != null && !record.startsWith("2020-01-01")

/**
 * 表格单元格的显示文本
 *
 * 统计表的单元格是任意 JSON 值（数字、字符串，偶尔是嵌套结构），统一走一个入口格式化： 数字去掉无意义的小数尾巴，空值给占位符，嵌套结构只报个数——单元格里塞不下它。
 *
 * Display text for a statistics-table cell.
 *
 * A cell holds an arbitrary JSON value (number, string, occasionally a nested structure) and
 * everything funnels through one formatter: numbers lose their meaningless decimal tails, empty
 * values get a placeholder, and nested structures report only their size — a cell cannot fit them.
 */
fun ApValue.prettyCell(): String =
    when (this) {
        null -> "—"
        is Double ->
            if (this == toLong().toDouble()) toLong().toString() else String.format("%.2f", this)
        is Map<*, *> -> "${size} 项"
        is List<*> -> "${size} 条"
        else -> toString()
    }

/**
 * 一行「标签 + 值」，用于信息密集但不需要各自成卡的场合
 *
 * One "label + value" row, for information-dense spots that do not warrant a card each.
 *
 * @param label 标签 / the label
 * @param value 值 / the value
 * @param valueColor 值的颜色；默认跟随主题 / the value's color; theme default when unspecified
 */
@Composable
fun ApKeyValueRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/**
 * 统计口径的数字卡：数字要能一眼扫到，标签退到其次
 *
 * A statistics metric card: the number must be scannable at a glance and the label stays secondary.
 *
 * @param label 指标名 / the metric name
 * @param value 数值 / the value
 * @param unit 单位；空串时不显示 / the unit; hidden when empty
 * @param modifier 应用于卡片的修饰符 / the modifier applied to the card
 */
@Composable
fun ApMetricCard(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(AppTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xxs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = value, style = MaterialTheme.typography.titleLarge)
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}
