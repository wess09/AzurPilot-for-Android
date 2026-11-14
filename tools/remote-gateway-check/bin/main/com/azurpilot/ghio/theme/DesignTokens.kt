package com.azurpilot.ghio.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 收口 MD3 token 之外、本项目自用的静态尺寸：间距、图标、描边、透明度
 *
 * 圆角、elevation、字阶、动效一律取 M3 主题 token
 * （[androidx.compose.material3.MaterialTheme]），不在这里再放一套。
 *
 * Static sizes this project uses beyond the MD3 tokens: spacing, icons,
 * strokes, alpha.
 *
 * Corner radii, elevation, type scale, and motion all come from the M3 theme
 * tokens ([androidx.compose.material3.MaterialTheme]); no second set lives
 * here.
 */
object AppTokens {

    /** 间距阶梯 / Spacing steps. */
    object Spacing {
        val xxs: Dp = 2.dp
        val xs: Dp = 4.dp
        val sm: Dp = 8.dp
        val md: Dp = 12.dp
        val lg: Dp = 16.dp
        val xl: Dp = 20.dp
    }

    /** 分隔线 / Separator sizing. */
    object Separator {
        val thickness: Dp = 0.5.dp
    }

    /** 描边宽度；分隔线用 [Separator] / Stroke widths; separators use [Separator]. */
    object Border {
        /** 转圈的线宽 / The progress-indicator stroke width. */
        val marker: Dp = 2.dp
    }

    /**
     * 图标绘制尺寸；不要在 `Modifier.size(N.dp)` 上拍裸数
     * 新场景对不上现有档时，先在这里加档并写清用途
     *
     * Icon drawing sizes; do not sprinkle raw numbers into
     * `Modifier.size(N.dp)`. When a new scenario misses the existing steps,
     * add a step here first and document its purpose.
     */
    object IconSize {
        /** 行内装饰图标，与 bodyLarge / labelLarge 并排 / Inline decorative icons beside bodyLarge / labelLarge. */
        val sm: Dp = 16.dp

        /** IconButton 与按钮前置图标的标准档 / The standard step for IconButtons and leading button icons. */
        val md: Dp = 20.dp

        /** 卡片内的占位插画 / Placeholder illustrations inside cards. */
        val lg: Dp = 32.dp
    }

    /** 状态指示件：一条状态行的成败靠它在一眼之内分出来 / Status indicators: one glance at a status line must separate success from failure. */
    object Indicator {
        val dot: Dp = 8.dp
    }
}
