package com.azurpilot.ghio.ui.azurpilot.sections.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.ui.components.AppNavigationRow
import com.azurpilot.ghio.ui.ksu.AppSettingsGroup

/**
 * 和 Android 设置中心采用相同的原生列表容器与行控件。
 *
 * Uses the same native settings groups and navigation rows as the Android settings screen.
 */
@Composable
fun SettingsSection(
    repository: AzurPilotRepository,
    onOpenInstances: () -> Unit,
    onOpenAnnouncement: () -> Unit,
    onOpenMeowfficer: () -> Unit,
    onOpenUpdater: () -> Unit,
    onOpenDeploy: () -> Unit,
) {
    val updater by repository.updater.collectAsStateWithLifecycle()
    val announcement by repository.announcement.collectAsStateWithLifecycle()
    val deploy by repository.deploySettings.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppSettingsGroup(
            title = stringResource(R.string.ap_section_config),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                AppNavigationRow(
                    label = stringResource(R.string.ap_instances_import),
                    icon = Icons.Outlined.FileUpload,
                    description = stringResource(R.string.ap_import_default_hint),
                    onClick = onOpenInstances,
                )
            }
            item {
                AppNavigationRow(
                    label = stringResource(R.string.ap_title_deploy),
                    icon = Icons.Outlined.Tune,
                    description = deploy?.let {
                        stringResource(R.string.ap_settings_deploy_count, it.groups.size)
                    } ?: stringResource(R.string.ap_waiting_data),
                    onClick = onOpenDeploy,
                )
            }
        }
        AppSettingsGroup(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                AppNavigationRow(
                    label = stringResource(R.string.ap_title_updater),
                    icon = Icons.Outlined.SystemUpdate,
                    description = updater?.let {
                        if (it.managedByAndroid) stringResource(R.string.ap_updater_managed)
                        else if (it.behind > 0) stringResource(R.string.ap_updater_behind, it.behind)
                        else stringResource(R.string.ap_updater_current)
                    } ?: stringResource(R.string.ap_waiting_data),
                    onClick = onOpenUpdater,
                )
            }
            item {
                AppNavigationRow(
                    label = stringResource(R.string.ap_title_announcement),
                    icon = Icons.Outlined.Campaign,
                    description = announcement?.title,
                    onClick = onOpenAnnouncement,
                )
            }
            item {
                AppNavigationRow(
                    label = stringResource(R.string.ap_title_meowfficer),
                    icon = Icons.Outlined.Pets,
                    onClick = onOpenMeowfficer,
                )
            }
        }
    }
}
