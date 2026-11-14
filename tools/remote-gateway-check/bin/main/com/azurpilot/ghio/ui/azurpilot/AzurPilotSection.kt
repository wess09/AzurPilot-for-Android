package com.azurpilot.ghio.ui.azurpilot

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.azurpilot.ghio.R

/**
 * AzurPilot 页内的一级分区
 *
 * 用**标签行**而不是第二条底部导航栏：AzurPilot 本身已经是外层的一个底部目的地， 再叠一条底部栏就分不清哪条管哪个层级了。分区之间是平级的视图切换，正是标签的语义。
 *
 * The top-level sections inside the AzurPilot page.
 *
 * A **tab row** instead of a second bottom navigation bar: AzurPilot is already a bottom
 * destination of the outer app, and stacking another bottom bar would blur which bar owns which
 * hierarchy level. Section switches are peer-level view changes — exactly the semantics of tabs.
 *
 * @param labelRes 标签文案资源 / the tab-label string resource
 * @param icon 标签图标 / the tab icon
 */
enum class AzurPilotSection(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    /** 总览 / Overview. */
    Overview(R.string.ap_section_overview, Icons.Filled.Dashboard),

    /** 配置 / Config. */
    Config(R.string.ap_section_config, Icons.Filled.Widgets),

    /** 日志 / Logs. */
    Logs(R.string.ap_section_logs, Icons.AutoMirrored.Filled.Article),

    /** 统计 / Statistics. */
    Statistics(R.string.ap_section_stats, Icons.Filled.BarChart),

    /** 设置 / Settings. */
    Settings(R.string.ap_section_settings, Icons.Filled.Tune),
}
