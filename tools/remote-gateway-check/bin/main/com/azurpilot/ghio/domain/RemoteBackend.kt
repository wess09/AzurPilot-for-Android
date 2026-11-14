package com.azurpilot.ghio.domain

/**
 * 特权通道后端的选择：虚拟显示、桥接等特权能力经由哪条通道获得
 *
 * 由特权层（[com.azurpilot.ghio.privileged.RemoteAccessCoordinator]）消费，
 * 两个值分别映射到 RootManager / ShizukuManager 实现；未配置时缺省落 SHIZUKU。
 *
 * Choice of the privileged backend: which channel carries the privileged
 * capabilities such as the virtual display and the bridge.
 *
 * Consumed by the privileged layer
 * ([com.azurpilot.ghio.privileged.RemoteAccessCoordinator]); each value maps to
 * the RootManager / ShizukuManager implementation. Unconfigured setups default
 * to SHIZUKU.
 *
 * @property display 设置页展示用的名称 / Name shown on the settings page.
 */
enum class RemoteBackend(val display: String) {
    /** Shizuku 通道：ADB/无线调试授权，免 root / Shizuku channel: authorized via ADB or wireless debugging, no root needed. */
    SHIZUKU(display = "Shizuku"),

    /** Root 通道：设备已 root，经 su 提权 / Root channel: the device is rooted, elevated via su. */
    ROOT(display = "Root"),
}
