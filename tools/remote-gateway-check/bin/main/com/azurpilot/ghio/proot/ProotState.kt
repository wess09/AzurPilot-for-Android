package com.azurpilot.ghio.proot

/**
 * proot 会话（rootfs 内 wrapper + WebUI）的托管状态机
 *
 * The managed state machine of the proot session (the rootfs-internal wrapper
 * plus WebUI).
 */
enum class ProotPhase {
    /** 未启动或已停止 / Not started or stopped. */
    IDLE,

    /**
     * 启动前准备：清残留/写 DNS/铺 overlay/播种实例配置
     *
     * Pre-start preparation: clean leftovers, write DNS, lay overlays, seed
     * instance configs.
     */
    PREPARING,

    /**
     * 热更新中（git.lyoko.io；断网超时降级不阻塞）
     *
     * Hot update in progress (git.lyoko.io; a network timeout degrades without
     * blocking).
     */
    UPDATING,

    /**
     * 拉起 proot 会话 / 等待 wrapper 就绪 / 崩溃重拉中
     *
     * Spawning the proot session, waiting for the wrapper to come up, or
     * respawning after a crash.
     */
    STARTING,

    /**
     * 会话存活（wrapper 22400 可达，WebUI 由 wrapper 监管）
     *
     * Session alive (the wrapper answers on 22400; the WebUI is supervised by
     * the wrapper).
     */
    RUNNING,

    /**
     * 启动失败（detail 为人可读原因；ensureStarted 可重试）
     *
     * Startup failed ([ProotSnapshot.detail] carries a human-readable reason;
     * ensureStarted may be retried).
     */
    FAILED,
}

/**
 * 会话状态的一份快照；UI 与 [com.azurpilot.ghio.service.RunForegroundService] 的
 * 保活判据都读它
 *
 * A snapshot of the session state; both the UI and the keep-alive criterion of
 * [com.azurpilot.ghio.service.RunForegroundService] read from it.
 */
data class ProotSnapshot(
    val phase: ProotPhase = ProotPhase.IDLE,

    /** 当前步骤或失败原因（人可读）/ Current step or failure reason, human readable. */
    val detail: String = "",
) {
    /**
     * FGS 保活判据：会话在任一活跃阶段都需要 app 进程钉前台
     *
     * Keep-alive criterion for the FGS: in any active phase the app process
     * must be pinned to the foreground.
     */
    val sessionActive: Boolean
        get() = phase == ProotPhase.PREPARING || phase == ProotPhase.UPDATING ||
                phase == ProotPhase.STARTING || phase == ProotPhase.RUNNING
}
