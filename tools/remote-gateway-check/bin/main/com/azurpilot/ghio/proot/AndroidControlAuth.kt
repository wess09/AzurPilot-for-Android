package com.azurpilot.ghio.proot

import android.content.Context
import java.util.UUID

/**
 * 加载并惰性生成 App 私有的本机控制口令；WebUI 的普通会话不使用它。
 *
 * 口令首次访问时以 UUID 生成并持久化到 App 私有 SharedPreferences，进程重启后保持不变。
 * App 侧调用 runtime 的 Android 薄接口（`/android/…`）全靠它对端鉴权：既经 wrapper 注入的
 * `AZURPILOT_ANDROID_TOKEN` 环境变量告知 runtime，也在 HTTP 头 `X-AzurPilot-Android-Token`
 * 里回传。进程级单例；`@Synchronized` 串行化并发首建，保证只生成一次。
 *
 * Loads and lazily generates the app-private local control token, which is not
 * used by ordinary WebUI sessions.
 *
 * The token is generated once as a UUID on first access and persisted in the
 * app's private SharedPreferences, staying stable across process restarts. It
 * authenticates app-side calls to the runtime's Android thin API
 * (`/android/…`): the app tells the runtime about it via the
 * `AZURPILOT_ANDROID_TOKEN` environment variable injected by the wrapper, and
 * echoes it back in the `X-AzurPilot-Android-Token` HTTP header. Process-level
 * singleton; `@Synchronized` serializes concurrent first creation so exactly
 * one token is ever generated.
 */
object AndroidControlAuth {
    private const val PREFS = "azurpilot_android_control"
    private const val KEY = "token"

    /**
     * 返回控制口令；不存在时生成新 UUID 并同步落盘
     *
     * 用 [SharedPreferences.commit] 而不是 apply：调用方拿到口令后马上拿去鉴权，
     * 必须保证返回前已持久化，进程随即被杀也不至于两端口令不一致。
     *
     * Returns the control token, generating and synchronously persisting a new
     * UUID when none exists yet.
     *
     * [SharedPreferences.commit] is used instead of apply because callers
     * authenticate with the token immediately; it must be on disk before this
     * returns, so a process death right after cannot leave the two ends
     * holding different tokens.
     *
     * @param context 任意 App 上下文 / any app context
     * @return 当前口令 / the current token
     */
    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).commit() }
    }
}
