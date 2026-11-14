package com.azurpilot.ghio.proot

import android.content.Context

/**
 * 浏览侧界面选择的持久化（当前选中的实例名）
 *
 * 与 [AzurPilotRunController] 用的 `azurpilot_android` 分开存：那份是「跑哪个配置」的
 * 进程控制选择，这份是「看哪个实例」的浏览选择，两者会不同（比如看着 A 却让 B 在跑）。
 *
 * Persistence for browse-side UI selections (the currently selected instance).
 *
 * Stored separately from the `azurpilot_android` prefs used by
 * [AzurPilotRunController]: that one holds the process-control choice of which
 * config to run, this one the browsing choice of which instance to look at —
 * the two may differ (watching A while B runs).
 */
class AzurPilotPreferences(context: Context) : AzurPilotPreferenceStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 上次选中的实例名；从未选过（或已清空）为 null / Last selected instance name; null before any choice or after clearing. */
    override var selectedInstance: String?
        get() = prefs.getString(KEY_INSTANCE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_INSTANCE) else putString(KEY_INSTANCE, value)
            }.apply()
        }

    private companion object {
        const val PREFS = "azurpilot_browse"
        const val KEY_INSTANCE = "selected_instance"
    }
}
