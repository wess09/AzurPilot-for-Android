package com.azurpilot.ghio.privileged

/**
 * Shizuku 引导的分阶段结论；驱动首页的引导卡片
 *
 * The staged Shizuku onboarding verdict; drives the home onboarding card.
 */
enum class ShizukuReadinessStage {
    /** 内置启动器需要激活，不要求外部管理器。 / The embedded launcher needs activation, without an external manager. */
    BuiltInSetup,
    /** 检测到 Sui（Magisk 模块）提供服务 / Sui (a Magisk module) is serving the API */
    SuiAvailable,

    /** 服务运行中但未授权 / service running but not granted */
    NeedAuth,

    /**
     * 就绪：已授权、当前后端不是 Shizuku，或用户已选跳过
     *
     * Ready: granted, the configured backend is not Shizuku, or the user
     * opted out.
     */
    Ready,
}

/**
 * Shizuku 引导的当下结论；[needsGuidance] 决定要不要弹引导
 *
 * The current Shizuku onboarding verdict; [needsGuidance] decides whether to
 * show guidance.
 *
 * @param stage 当前所处阶段 / the current stage
 * @param canSwitchToRoot Root 后端可用，引导里可建议切换
 *   / the Root backend is available and the guidance may suggest switching
 */
data class ShizukuReadiness(
    val stage: ShizukuReadinessStage = ShizukuReadinessStage.Ready,
    val canSwitchToRoot: Boolean = false,
) {
    /** 非 Ready 即需要引导 / anything but Ready needs guidance */
    val needsGuidance: Boolean
        get() = stage != ShizukuReadinessStage.Ready
}
