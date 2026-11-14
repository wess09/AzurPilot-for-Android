package com.azurpilot.ghio.third

/**
 * 一块逻辑显示器的描述信息（不可变快照）
 *
 * 对应 scrcpy 服务端解析 `DisplayManagerGlobal` 返回的隐藏
 * `android.view.DisplayInfo` 后提取的字段子集。宽高已把旋转计入（即"当前
 * 摆放方向"下的逻辑尺寸），[rotation] 只是记录原始旋转值。
 *
 * 从 scrcpy 服务端的 `third/DisplayInfo.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * An immutable snapshot describing one logical display.
 *
 * Mirrors the subset of fields scrcpy's server extracts from the hidden
 * `android.view.DisplayInfo` returned by `DisplayManagerGlobal`. Width and
 * height already account for rotation (the logical size "as currently
 * oriented"); [rotation] only records the raw rotation value.
 *
 * Ported from scrcpy's server `third/DisplayInfo.java` (Apache-2.0,
 * Genymobile/scrcpy).
 *
 * @property displayId 逻辑显示器 ID / logical display id
 * @property size 逻辑尺寸，已含旋转 / logical size, rotation already applied
 * @property rotation 原始旋转值（`Surface.ROTATION_*`）/ raw rotation
 *   (`Surface.ROTATION_*`)
 * @property layerStack 该显示器归属的 layer stack / layer stack the display
 *   belongs to
 * @property flags Display 标志位（如 [FLAG_SUPPORTS_PROTECTED_BUFFERS]）/
 *   Display flags (e.g. [FLAG_SUPPORTS_PROTECTED_BUFFERS])
 * @property dpi 逻辑密度（dpi）/ logical density in dpi
 * @property uniqueId 显示器唯一标识，dumpsys 回退路径拿不到时为 null /
 *   display unique id; null when only the dumpsys fallback could resolve it
 */
data class DisplayInfo(
    val displayId: Int,
    val size: Size,
    val rotation: Int,
    val layerStack: Int,
    val flags: Int,
    val dpi: Int,
    val uniqueId: String?,
) {
    companion object {
        /**
         * 镜像隐藏 `Display.FLAG_SUPPORTS_PROTECTED_BUFFERS` 的值 / Mirrors the
         * hidden `Display.FLAG_SUPPORTS_PROTECTED_BUFFERS` value.
         */
        const val FLAG_SUPPORTS_PROTECTED_BUFFERS = 0x00000001
    }
}
