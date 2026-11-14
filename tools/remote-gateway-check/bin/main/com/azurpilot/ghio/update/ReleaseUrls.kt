package com.azurpilot.ghio.update

/**
 * 解析本项目固定 Release 的 GitHub、CNB 与代理镜像下载源。
 *
 * 镜像均为 ghproxy 形态：`前缀 + 完整 GitHub URL`（如
 * `https://ghproxy.net/https://github.com/…`）。镜像站时效性强，内置列表只是
 * 当前可用的常用快照；某站失效或过慢时，用户可在设置里换用其他镜像或填自定义前缀
 * （同样要求 ghproxy 形态）。
 *
 * Resolves GitHub, CNB, and proxy download sources for this project's fixed release.
 *
 * Mirrors use the ghproxy form (`prefix + full GitHub URL`, e.g.
 * `https://ghproxy.net/https://github.com/…`). Mirror availability changes
 * constantly, so the built-in list is only a snapshot of currently working
 * sites; when one goes down or turns slow, users can switch to another mirror
 * or enter a custom prefix in Settings (same ghproxy form).
 */
internal object ReleaseUrls {
    const val BASE = "https://github.com/wess09/AzurPilot-for-Android/releases/download/azurpilot-android-latest/"
    const val INDEX = "${BASE}latest.json"

    /** CNB 国内源，与 GitHub 共用固定 tag 和附件名称。 / CNB source sharing GitHub's fixed tag and asset names. */
    const val CNB_BASE = "https://cnb.cool/azurpilot/AzurPilot-for-Android/-/releases/download/azurpilot-android-latest/"

    /** 设置存储值：CNB 国内源。 / Settings value for the domestic CNB source. */
    const val CNB = "cnb"

    /** 设置存储值：直连 GitHub / Settings value: direct GitHub connection. */
    const val DIRECT = "direct"

    /** 设置存储值：自定义前缀（实际前缀取自定义输入） / Settings value: custom prefix (resolved from user input). */
    const val CUSTOM = "custom"

    /**
     * 内置镜像前缀；列表项本身即设置存储值。顺序即展示顺序：
     * 首个（ghproxy.net）也是旧版布尔开关迁移的落点。
     *
     * Built-in mirror prefixes; each entry doubles as a settings value. Order
     * is display order, and the first entry (ghproxy.net) is also where the
     * legacy boolean switch migrates.
     */
    val MIRRORS: List<String> = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://gh.ddlc.top/",
        "https://gh-proxy.net/",
    )

    /**
     * 规范化自定义前缀：去空白、补尾斜杠；非法输入一律回落直连，不让解析环节抛异常。
     *
     * Sanitizes a custom prefix: trims whitespace, appends the trailing slash,
     * and falls back to direct on any malformed input so URL assembly never throws.
     */
    fun normalizeCustom(raw: String): String =
        raw.trim()
            .let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
            .takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: ""

    /**
     * 由设置值算出实际前缀；`direct` 与非法值都返回空串（直连）。
     *
     * Maps a settings value to the effective prefix; `direct` and unknown
     * values both yield an empty string (direct connection).
     */
    fun mirrorPrefix(id: String, customRaw: String): String = when (id) {
        DIRECT -> ""
        CUSTOM -> normalizeCustom(customRaw)
        else -> if (id in MIRRORS) id else ""
    }

    /**
     * 返回 CNB 标识或代理前缀；CNB 不是 GitHub 代理，不能用于 Git 源码 URL 拼接。
     *
     * Returns the CNB identifier or a proxy prefix. CNB is not a GitHub proxy and must not be
     * used as a prefix for Git source URLs.
     */
    fun source(id: String, customRaw: String): String =
        if (id == CNB) CNB else mirrorPrefix(id, customRaw)

    /** 只接受本项目 GitHub/CNB 固定发布地址。 / Accepts only this project's fixed GitHub/CNB release URLs. */
    fun isReleaseUrl(url: String): Boolean = url.startsWith(BASE) || url.startsWith(CNB_BASE)

    /** 返回前端归档下载基址。 / Returns the download base for frontend archives. */
    fun distributionBase(id: String, customRaw: String): String = selected(BASE, source(id, customRaw))

    /**
     * 按用户选择在 GitHub、CNB 和代理之间转换附件地址；清单与下载共用相同固定 tag。
     *
     * Routes assets between GitHub, CNB, and proxies using the selected source. Manifests and
     * downloads share the same fixed tag.
     */
    fun selected(url: String, source: String): String {
        require(isReleaseUrl(url)) { "Invalid release URL" }
        val filename = url.removePrefix(BASE).removePrefix(CNB_BASE)
        return when (source) {
            CNB -> "$CNB_BASE$filename"
            "" -> "$BASE$filename"
            else -> "$source$BASE$filename"
        }
    }

    /**
     * 镜像选择器的展示名：去掉协议与尾斜杠，只剩主机名。
     *
     * Builds the picker label for a mirror: strips scheme and trailing slash,
     * leaving the host name.
     */
    fun displayLabel(mirror: String): String =
        mirror.removePrefix("https://").removePrefix("http://").trimEnd('/')
}
