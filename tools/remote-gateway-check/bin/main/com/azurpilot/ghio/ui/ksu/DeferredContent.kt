// KernelSU Manager UI (GPL-3.0), commit 6e3860443f8fea7c5fc226277e53888033796488.
// AzurPilot adaptations: package/import names, app identity and business callbacks.
package com.azurpilot.ghio.ui.ksu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope

/**
 * Returns true only after the navigation transition animation has completed
 * and an additional buffer frame has passed.
 *
 * Timeline:
 * - During animation: returns false → page shows lightweight placeholder (smooth animation)
 * - Animation ends + 1 frame: returns true → heavy content composes
 *   (stutter is invisible because the page is already static)
 *
 * The value is sticky — once true it never reverts to false,
 * so content stays visible during exit transitions.
 */
@Composable
fun rememberContentReady(): Boolean {
    val transitionRunning = LocalNavTransitionScope.current.isRunning
    val ready = remember { mutableStateOf(false) }

    LaunchedEffect(transitionRunning) {
        if (!transitionRunning && !ready.value) {
            withFrameNanos { }
            ready.value = true
        }
    }

    return ready.value
}
