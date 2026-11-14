package com.azurpilot.ghio.privileged

import com.azurpilot.ghio.domain.RemoteBackend

/**
 * 两个提权后端的可用性与授权快照，外加当前配置的后端
 *
 * 纯数据：由 [RemoteAccessCoordinator] 现场组装，UI 与连接层都只读它。
 *
 * Snapshot of availability and grant state for both privilege backends, plus
 * the currently configured backend.
 *
 * Pure data: assembled on the spot by [RemoteAccessCoordinator]; both the UI
 * and the connection layer read it only.
 */
data class RemoteAccessState(
    /** Shizuku（或 Sui）服务活着 / the Shizuku (or Sui) service is alive */
    val shizukuAvailable: Boolean = false,
    /** Shizuku 授权已到手 / the Shizuku grant is in hand */
    val shizukuGranted: Boolean = false,
    /** 已授权或 PATH 上找得到可执行 su / granted, or an executable su is on PATH */
    val rootAvailable: Boolean = false,
    /** Root 授权已到手 / the Root grant is in hand */
    val rootGranted: Boolean = false,
    /** 用户配置的后端；持久化在设置里 / the user-configured backend; persisted in settings */
    val configuredBackend: RemoteBackend = RemoteBackend.SHIZUKU,
) {
    /** 该后端是否可用 / whether [backend] is available */
    fun isAvailable(backend: RemoteBackend): Boolean {
        return when (backend) {
            RemoteBackend.SHIZUKU -> shizukuAvailable
            RemoteBackend.ROOT -> rootAvailable
        }
    }

    /** 该后端是否已授权 / whether [backend] is granted */
    fun isGranted(backend: RemoteBackend): Boolean {
        return when (backend) {
            RemoteBackend.SHIZUKU -> shizukuGranted
            RemoteBackend.ROOT -> rootGranted
        }
    }
}
