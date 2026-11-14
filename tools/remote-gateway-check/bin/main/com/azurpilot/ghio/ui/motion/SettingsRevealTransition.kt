package com.azurpilot.ghio.ui.motion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope

/**
 * A continuous container morph between the full settings page and its toolbar gear.
 *
 * The visual function has no changeover point: opening, closing and predictive-back
 * preview all use the same scale, position and corner interpolation. The navigation
 * driver limits only the amount of travel allowed before the gesture commits.
 */
internal fun settingsRevealTransition(anchor: () -> Offset): NavTransition = object : NavTransition {
    override val opaqueDepth: Float = 2f
    override val predictiveBackPreviewFraction: Float = 0.13f
    override val dismissDirection: NavSwipeDirection = NavSwipeDirection.None
    override val motion: NavMotion = NavMotion(
        programmatic = NavSettleSpec.Tween(
            durationMillis = 370,
            easing = FastOutSlowInEasing,
        ),
        commit = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 320f),
        cancel = NavSettleSpec.Spring(dampingRatio = 1f, stiffness = 410f),
    )

    // A pushed page is not a modal; the home must stay visible behind the preview.
    override fun scrimFraction(scope: NavTransitionScope): Float = 0f

    override fun Modifier.transformEntry(scope: NavTransitionScope): Modifier =
        graphicsLayer {
            val exit = (-scope.relativeDepth).coerceIn(0f, 1f)
            if (exit > 0f) {
                val width = scope.layoutSize.width.coerceAtLeast(1).toFloat()
                val height = scope.layoutSize.height.coerceAtLeast(1).toFloat()
                val iconPx = 48f * scope.density.density
                val source = anchor().takeIf { it.isSpecified }
                    ?: Offset(width - iconPx / 2f, iconPx / 2f)
                // Exactly one continuous mapping: no plateau, phase switch or second easing.
                val scaleXTarget = (iconPx / width).coerceIn(0f, 1f)
                val scaleYTarget = (iconPx / height).coerceIn(0f, 1f)
                val sx = 1f + (scaleXTarget - 1f) * exit
                val sy = 1f + (scaleYTarget - 1f) * exit
                transformOrigin = TransformOrigin.Center
                scaleX = sx
                scaleY = sy
                translationX = (source.x - width / 2f) * exit
                translationY = (source.y - height / 2f) * exit
                // The source icon, never a solid placeholder, shows through at the end.
                alpha = ((1f - exit) / 0.10f).coerceIn(0f, 1f)

                // Keep a consistent physical radius as the window shrinks.
                val radiusPx = (20f + 4f * exit) * scope.density.density
                val radiusLocalDp = radiusPx / minOf(sx, sy) / scope.density.density
                shape = RoundedCornerShape(radiusLocalDp.dp)
                clip = true
            } else {
                // This same transition also governs the covered HOME entry.
                // Reset every graphics property, not only alpha/clip: graphics layers
                // are reused and otherwise retain the last non-identity transform.
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                transformOrigin = TransformOrigin.Center
                alpha = 1f
                clip = false
            }
        }
}
