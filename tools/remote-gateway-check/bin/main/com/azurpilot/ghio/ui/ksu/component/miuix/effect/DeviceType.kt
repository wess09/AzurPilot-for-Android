// KernelSU Manager UI (GPL-3.0), commit 6e3860443f8fea7c5fc226277e53888033796488.
// AzurPilot adaptations: package/import names, app identity and business callbacks.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

// Mirrored from compose-miuix-ui example.

package com.azurpilot.ghio.ui.ksu.component.miuix.effect

enum class DeviceType {
    PHONE,
    PAD,
}
