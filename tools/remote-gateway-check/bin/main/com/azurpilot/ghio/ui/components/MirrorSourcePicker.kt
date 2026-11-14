package com.azurpilot.ghio.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.azurpilot.ghio.R
import com.azurpilot.ghio.update.ReleaseUrls

/**
 * 渲染下载源选择器：CNB 国内源、GitHub 直连、内置镜像与自定义。
 * 设置页与首启部署页共用同一组选项与存储值。
 * 自定义前缀的输入框只在设置页提供，部署页选了自定义但还没填时按直连处理。
 *
 * Renders the download source picker: CNB, direct GitHub, built-in mirrors, and custom; the
 * settings page and the first-run provisioning page share one option set and one
 * stored value. The input box for the custom prefix exists only on the settings
 * page; the provisioning page treats a custom choice with no prefix filled in yet
 * as direct connection.
 */
@Composable
fun MirrorSourcePicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    arrangement: Alignment.Horizontal = Alignment.Start,
) {
    AppSingleChoiceFlow(
        icon = Icons.Outlined.CloudDownload,
        options = buildList {
            add(ReleaseUrls.CNB to stringResource(R.string.provision_source_cnb))
            add(ReleaseUrls.DIRECT to stringResource(R.string.provision_source_direct))
            ReleaseUrls.MIRRORS.forEach { add(it to ReleaseUrls.displayLabel(it)) }
            add(ReleaseUrls.CUSTOM to stringResource(R.string.provision_source_custom))
        },
        selected = selected,
        onSelect = onSelect,
        modifier = modifier,
        arrangement = arrangement,
    )
}
