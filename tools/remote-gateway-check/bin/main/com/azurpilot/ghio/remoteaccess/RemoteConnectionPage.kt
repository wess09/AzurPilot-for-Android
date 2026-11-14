package com.azurpilot.ghio.remoteaccess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.azurpilot.ghio.R
import com.azurpilot.ghio.ui.ksu.AppPageScaffold
import com.azurpilot.ghio.theme.AppTokens

/**
 * 独立的远程入口，不读取本机运行时或提权服务的就绪状态。
 *
 * Standalone remote entry that does not depend on local Runtime or privileged-service readiness.
 */
@Composable
fun RemoteConnectionPage(onBack: () -> Unit, onConnected: () -> Unit, onLocalSelected: () -> Unit) {
    AppPageScaffold(title = stringResource(R.string.remote_pair_bind), onBack = onBack,
        modifier = Modifier.testTag("remote-connection-page")) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding()) {
            Column(Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(AppTokens.Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(AppTokens.Spacing.xl)) {
                RemotePairingCard(onConnected = onConnected, onLocalSelected = onLocalSelected)
            }
        }
    }
}
