package com.azurpilot.ghio.ui.oobe

import androidx.annotation.StringRes
import com.azurpilot.ghio.R
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.privileged.RemoteAccessState

/**
 * 返回环境页与摘要共用的权限状态，区分未授权和不可用的后端。
 *
 * Returns the shared permission status for setup and summary, distinguishing an ungranted
 * backend from an unavailable one.
 */
@StringRes
internal fun RemoteAccessState.oobeStatusRes(): Int = when {
    isGranted(configuredBackend) -> R.string.oobe_priv_status_ready
    isAvailable(configuredBackend) -> R.string.oobe_priv_status_need_auth
    configuredBackend == RemoteBackend.ROOT -> R.string.oobe_priv_status_root_unavailable
    else -> R.string.oobe_priv_status_not_running
}
