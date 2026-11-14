package com.azurpilot.ghio.constant

/**
 * 收口 DataStore 文件名常量
 *
 * 目前只有 [com.azurpilot.ghio.domain.UserConfiguration] 的 JSON 文件经此取名；
 * app 设置走的是另一个 Preferences DataStore（"app_settings"，键名由 `@PrefSchema`
 * 处理器按 camelToSnakeCase 生成），不经这里的文件名。
 *
 * Centralizes DataStore file-name constants.
 *
 * Currently only the JSON file for the app's [UserConfiguration][com.azurpilot.ghio.domain.UserConfiguration]
 * is named here; app settings live in a separate Preferences DataStore
 * ("app_settings") whose keys the @PrefSchema processor generates via
 * camelToSnakeCase, bypassing these file names.
 */
object DataStoreFile {
    /** UserConfiguration JSON 文件名 / File name of the UserConfiguration JSON store. */
    const val USER_CONFIGRATION = "user_configuration.json"
}
