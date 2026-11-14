package com.azurpilot.ghio.overlay.border

import androidx.core.graphics.toColorInt

/**
 * 运行边框的样式；默认值即当前唯一用法，留参数只为将来按状态换色
 *
 * The run border's style; the defaults are the only current usage, the
 * parameters exist so colors can later vary by state.
 *
 * @property widthDp 边框线宽（dp） / The border stroke width in dp
 * @property colors 扫描渐变的色环，首尾同色才闭合 / The sweep gradient's color
 *   ring; first and last must match for the ring to close
 * @property animationDurationMs 渐变转一圈的时长 / The duration of one gradient
 *   revolution
 */
data class BorderStyle(
    val widthDp: Float = 2f,
    /** 扫描渐变的色环，首尾同色才闭合 / The sweep gradient's color ring; first and last must match for the ring to close. */
    val colors: IntArray = DEFAULT_RAINBOW_COLORS,
    /** 转一圈的时长 / The duration of one gradient revolution. */
    val animationDurationMs: Long = 3000L,
) {

    // colors 是数组，data class 不会按内容比较，必须手写 equals/hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BorderStyle
        return widthDp == other.widthDp &&
                colors.contentEquals(other.colors) &&
                animationDurationMs == other.animationDurationMs
    }

    override fun hashCode(): Int {
        var result = widthDp.hashCode()
        result = 31 * result + colors.contentHashCode()
        result = 31 * result + animationDurationMs.hashCode()
        return result
    }

    companion object {
        /** 默认彩虹色环；末位重复红色使渐变首尾闭合 / The default rainbow ring; the trailing red repeat closes the gradient. */
        val DEFAULT_RAINBOW_COLORS = intArrayOf(
            "#FF0000".toColorInt(),
            "#FF7F00".toColorInt(),
            "#FFFF00".toColorInt(),
            "#00FF00".toColorInt(),
            "#00FFFF".toColorInt(),
            "#0000FF".toColorInt(),
            "#8B00FF".toColorInt(),
            "#FF0000".toColorInt(),
        )
    }
}
