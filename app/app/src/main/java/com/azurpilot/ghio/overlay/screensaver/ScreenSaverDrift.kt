package com.azurpilot.ghio.overlay.screensaver

/**
 * 沿横纵两个轴交替逐像素漂移，在主线程由屏保的空闲计时器驱动。
 *
 * 每次只移动一个轴的一像素，让横线与竖线都定期换位；到达边界时折返。
 * 没有空间的轴会跳过，布局缩小时先收回边界内。
 *
 * Alternates unit-pixel drift between axes, driven by the screensaver's idle timer on the main thread.
 *
 * Each step moves one pixel on one axis so both horizontal and vertical lines change position
 * regularly. Reverses at bounds, skips axes without space, and clamps after a layout shrinks.
 *
 * @property x 水平物理像素偏移 / Horizontal offset in physical pixels.
 * @property y 垂直物理像素偏移 / Vertical offset in physical pixels.
 * @property horizontalDirection 水平移动方向 / Horizontal movement direction.
 * @property verticalDirection 垂直移动方向 / Vertical movement direction.
 * @property nextAxis 下次移动的轴，零为水平 / Next movement axis, with zero for horizontal.
 */
internal data class ScreenSaverDrift(
    val x: Int = 0,
    val y: Int = 0,
    private val horizontalDirection: Int = 1,
    private val verticalDirection: Int = 1,
    private val nextAxis: Int = 0,
) {
    /**
     * 返回下一像素的位置；没有剩余空间时保持原位。
     *
     * Returns the next pixel position, staying put when there is no available space.
     *
     * @param maxX 左右各允许的物理像素偏移 / Allowed physical pixel offset on each horizontal side.
     * @param maxY 上下各允许的物理像素偏移 / Allowed physical pixel offset on each vertical side.
     */
    fun step(maxX: Int, maxY: Int): ScreenSaverDrift {
        val boundX = maxX.coerceAtLeast(0)
        val boundY = maxY.coerceAtLeast(0)
        val startX = x.coerceIn(-boundX, boundX)
        val startY = y.coerceIn(-boundY, boundY)
        repeat(2) { turn ->
            when ((nextAxis + turn) % 2) {
                0 -> if (boundX > 0) {
                    val direction = if (startX + horizontalDirection in -boundX..boundX) {
                        horizontalDirection
                    } else -horizontalDirection
                    return copy(
                        x = startX + direction, y = startY,
                        horizontalDirection = direction, nextAxis = 1,
                    )
                }
                1 -> if (boundY > 0) {
                    val direction = if (startY + verticalDirection in -boundY..boundY) {
                        verticalDirection
                    } else -verticalDirection
                    return copy(
                        x = startX, y = startY + direction,
                        verticalDirection = direction, nextAxis = 0,
                    )
                }
            }
        }
        return copy(x = startX, y = startY)
    }
}
