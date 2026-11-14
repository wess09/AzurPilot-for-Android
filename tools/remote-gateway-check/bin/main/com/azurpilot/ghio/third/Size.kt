package com.azurpilot.ghio.third

import android.graphics.Rect

/**
 * 宽 × 高的尺寸值（单位与使用场景一致，本工程内为显示器像素）
 *
 * 承载显示器/编码分辨率的纯值类型，提供旋转、限幅、对齐 8 像素等纯函数变换，
 * 不持有任何系统资源。
 *
 * 从 scrcpy 服务端的 `third/Size.java` 移植（Apache-2.0, Genymobile/scrcpy）。
 *
 * A width-by-height size value (in display pixels in this project).
 *
 * Pure value type carrying display/encoder resolutions with pure transforms
 * (rotate, clamp, align to 8 pixels); holds no system resources.
 *
 * Ported from scrcpy's server `third/Size.java` (Apache-2.0, Genymobile/scrcpy).
 *
 * @property width 宽 / width
 * @property height 高 / height
 */
data class Size(val width: Int, val height: Int) {

    /** 较长边 / The larger of the two dimensions. */
    val max: Int
        get() = maxOf(width, height)

    /** 宽高互换 / Returns the size with width and height swapped. */
    fun rotate(): Size {
        return Size(height, width)
    }

    /**
     * 把较长边限制到 [maxSize]，按比例缩放较短边
     *
     * 用于给编码器设定分辨率上限；[maxSize] 必须是非负的 8 的倍数（编码器对齐
     * 要求），为 0 表示不限制。
     *
     * Clamps the major dimension to [maxSize], scaling the minor one
     * proportionally.
     *
     * Used to cap the encoder resolution; [maxSize] must be a non-negative
     * multiple of 8 (encoder alignment requirement), and 0 means "no limit".
     *
     * @param maxSize 较长边的上限（0 = 不限制）/ cap for the major dimension
     *   (0 = unlimited)
     * @return 未超限时返回自身，否则返回等比缩小的新尺寸 / this when already
     *   within the limit, otherwise a proportionally scaled-down size
     */
    fun limit(maxSize: Int): Size {
        assert(maxSize >= 0) { "Max size may not be negative" }
        assert(maxSize % 8 == 0) { "Max size must be a multiple of 8" }

        if (maxSize == 0) {
            return this
        }

        val portrait = height > width
        val major = if (portrait) height else width
        if (major <= maxSize) {
            return this
        }

        val minor = if (portrait) width else height

        val newMajor = maxSize
        val newMinor = maxSize * minor / major

        val w = if (portrait) newMinor else newMajor
        val h = if (portrait) newMajor else newMinor
        return Size(w, h)
    }

    /** 转为原点在左上角、覆盖整个尺寸的 [Rect] / Converts to a [Rect] covering the whole size from the top-left origin. */
    fun toRect(): Rect {
        return Rect(0, 0, width, height)
    }

    /** 格式 `WxH`，如 `1920x1080` / Formats as `WxH`, e.g. `1920x1080`. */
    override fun toString(): String {
        return "${width}x$height"
    }
}
