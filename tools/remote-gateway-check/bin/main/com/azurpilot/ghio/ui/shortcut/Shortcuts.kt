package com.azurpilot.ghio.ui.shortcut

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面快捷方式的契约常量：与 `res/xml/shortcuts.xml` 里的声明一一对应
 *
 * intent 走隐式 action 解析而非 targetPackage：applicationId 会被构建 profile
 * 追加后缀（基准构建还有 .benchmark 变体），shortcuts.xml 里写死包名会指错目标；
 * MainActivity 的 intent-filter 按动作匹配，包名怎么变都能命中。
 *
 * Contract constants for the launcher shortcuts; mirrors the declarations in
 * `res/xml/shortcuts.xml`.
 *
 * Shortcut intents resolve implicitly by action instead of targetPackage: the
 * applicationId gets a suffix appended by the build profile (plus the .benchmark
 * variant), so a hard-coded package in shortcuts.xml would miss its target; the
 * intent-filter on MainActivity matches by action and survives any package change.
 */
object ShortcutIntents {

    /** shortcuts.xml 各 intent 声明的隐式动作 / The implicit action every shortcut intent carries. */
    const val ACTION_SHORTCUT = "com.azurpilot.ghio.action.SHORTCUT"

    /** 目的地 extra 键 / The key of the destination extra. */
    const val EXTRA_ID = "com.azurpilot.ghio.extra.SHORTCUT_ID"

    /** 一键启停：MainActivity 直接分发，不经 UI 导航 / Toggle runner; handled by MainActivity directly. */
    const val ID_TOGGLE_RUNNER = "toggle_runner"

    /** 切到虚拟屏主 tab / Switch to the virtual-display main tab. */
    const val ID_SCREEN = "screen"

    /** 打开 AzurPilot 运行日志页 / Open the AzurPilot log page. */
    const val ID_RUNNER_LOG = "runner_log"
}

/**
 * 桌面快捷方式的导航请求总线：MainActivity 收到跳转型快捷方式后在此登记，
 * 由 [com.azurpilot.ghio.ui.AppRoot] 消费——主页面与导航栈 都活在组合里，
 * Activity 摸不到，只能经总线转交
 *
 * 状态流天然带「待办」语义：rootfs 部署门或应用锁挡住时请求留在里面，
 * 门放行后收集器一启动就执行；同刻多次请求按 StateFlow 规则收敛为最后一次。
 *
 * Navigation request bus for the launcher shortcuts: MainActivity registers
 * navigation-type shortcuts here and [com.azurpilot.ghio.ui.AppRoot] consumes
 * them — both main pages and navigation stack live inside composition, unreachable
 * from the Activity, so the bus is the hand-off.
 *
 * The state flow carries pending semantics for free: requests blocked by the
 * provisioning gate or the app lock stay buffered until the collector starts,
 * and repeated posts collapse to the latest one per StateFlow rules.
 */
class ShortcutRequests {

    private val pending = MutableStateFlow<String?>(null)

    /** 当前待处理的导航请求 / The currently pending navigation request. */
    val requests: StateFlow<String?> = pending.asStateFlow()

    /** 登记一条导航请求 / Registers a navigation request. */
    fun post(id: String) {
        pending.value = id
    }

    /** 消费完成后清空，避免界面重建时重复导航 / Clears after consumption so recreation does not navigate twice. */
    fun consume() {
        pending.value = null
    }

    companion object {
        /** 切到虚拟屏主 tab / Switch to the virtual-display main tab. */
        const val OPEN_SCREEN = "open_screen"

        /** 打开 AzurPilot 运行日志页 / Open the AzurPilot log page. */
        const val OPEN_RUNNER_LOG = "open_runner_log"
    }
}
