package com.azurpilot.ghio.overlay.screensaver

/**
 * 沿纵轴上下逐级漂移，在主线程由屏保的空闲计时器驱动。
 *
 * 每次移动固定步长并平滑过渡，让显示内容在可用纵向空间内折返移动，有效防烧屏；到达边界时折返。
 *
 * Drifts vertically up and down in discrete steps, driven by the screensaver's idle timer on the main thread.
 *
 * Each step moves a fixed distance and transitions smoothly, reversing at boundaries to mitigate OLED
 * burn-in across available vertical space.
 *
 * @property y 垂直物理像素偏移 / Vertical offset in physical pixels.
 * @property direction 垂直移动方向，-1 为向上，1 为向下 / Vertical movement direction (-1 for up, 1 for down).
 */
internal data class ScreenSaverDrift(
    val y: Int = 0,
    private val direction: Int = -1,
) {
    /**
     * 计算下一阶段的垂直偏移；到达边界时折返。
     *
     * Calculates the next vertical position, reversing at bounds.
     *
     * @param stepPx 单次移动的物理像素步长 / Step size in physical pixels.
     * @param maxOffsetY 允许的最大上下物理像素偏移 / Allowed maximum vertical offset in physical pixels.
     */
    fun step(stepPx: Int, maxOffsetY: Int): ScreenSaverDrift {
        val boundY = maxOffsetY.coerceAtLeast(0)
        if (boundY == 0 || stepPx <= 0) return copy(y = 0)
        val clampedY = y.coerceIn(-boundY, boundY)
        val nextY = clampedY + direction * stepPx
        return if (nextY in -boundY..boundY) {
            copy(y = nextY)
        } else {
            // 触达或超出边界时翻转方向折返，避免内容移出屏幕
            val newDirection = -direction
            val bounceY = (clampedY + newDirection * stepPx).coerceIn(-boundY, boundY)
            copy(y = bounceY, direction = newDirection)
        }
    }
}
