package com.azurpilot.ghio.privileged

/**
 * 汇报目标 app 在虚拟屏上的看门狗状态
 *
 * 三种坏结局分开处理：进程没了([APP_DIED])、进程还在但窗口跑了([DISPLAY_DRIFT])、
 * 窗口还在但画面冻结([FRAME_STALLED])，用户要做的事完全不同——第一种是应用被杀，
 * 第二种多半是 ROM 把窗口挪回了主屏，第三种是息屏后进程被冻结且踢活无效。
 * 名字对齐 参考实现 的 `appDiedEvent` / `displayDriftEvent`
 *
 * [aidlValue] 对齐 RemoteService.watchdogState() 的 AIDL 契约（特权进程返回 int），
 * app 侧用 [fromAidl] 映射回枚举；PermissionGateway 把它投影给 ViewModel/预览徽标
 *
 * Reports the watchdog state of the target app on the virtual display.
 *
 * The two bad endings are kept apart: the process is gone ([APP_DIED]) versus
 * the process is alive but its window wandered off ([DISPLAY_DRIFT]) — what
 * the user must do differs entirely; the former is a killed app, the latter
 * is usually the ROM moving the window back to the primary display. Names
 * mirror the reference implementation's `appDiedEvent` / `displayDriftEvent`.
 *
 * [aidlValue] matches the AIDL contract of RemoteService.watchdogState() (the
 * privileged process returns an int); the app side maps it back via
 * [fromAidl], and PermissionGateway projects it to the ViewModel / preview
 * badge.
 */
enum class WatchdogState(val aidlValue: Int) {
    /** 未在盯防目标 / no target under watch */
    IDLE(0),

    /** 正常盯防中 / watching the target normally */
    WATCHING(1),

    /**
     * 窗口离开虚拟屏且自动拉回失败；进程还活着
     *
     * The window left the virtual display and the auto pull-back failed; the
     * process is alive.
     */
    DISPLAY_DRIFT(2),

    /** pidof 查不到进程 / pidof finds no process */
    APP_DIED(3),

    /**
     * 目标窗口还在虚拟屏上但采集帧长期不更新（进程多半被 ROM 冻结），
     * 特权侧踢活多次无效；与 [DISPLAY_DRIFT] 不同，自动踢活仍在进行
     *
     * The target window is still on the virtual display but capture frames stopped advancing
     * (the process is likely frozen by the ROM) and repeated privileged-side kicks failed;
     * unlike [DISPLAY_DRIFT], automatic kicks are still running.
     */
    FRAME_STALLED(4);

    /**
     * 两者都表示这一轮已经跑不下去了，UI 与运行日志按同一档处理
     *
     * Both mean this round cannot continue; the UI and run logs treat them as
     * one bucket.
     */
    val isLost: Boolean get() = this == DISPLAY_DRIFT || this == APP_DIED

    companion object {
        /**
         * int → 枚举；未知值兜底回 [IDLE]，老 app 读到新协议的状态值不至于崩
         *
         * int → enum; unknown values fall back to [IDLE] so an old app reading
         * a state value from a newer protocol does not crash.
         */
        fun fromAidl(value: Int): WatchdogState =
            entries.firstOrNull { it.aidlValue == value } ?: IDLE
    }
}
