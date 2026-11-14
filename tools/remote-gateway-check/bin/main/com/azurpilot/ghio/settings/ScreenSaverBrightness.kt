package com.azurpilot.ghio.settings

/** Brightness used only while the full-screen screen-off overlay is visible. */
object ScreenSaverBrightness {
    const val DEFAULT = 0.01f
    const val MIN = 0.01f
    const val MAX = 0.50f

    fun parse(stored: String): Float =
        stored.toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(MIN, MAX) ?: DEFAULT
}
