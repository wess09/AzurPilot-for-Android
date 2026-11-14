package com.azurpilot.ghio.provision

/**
 * 保存运行时检查快照；更新结论随当前源码版本变化，适用于任意线程。
 *
 * Stores a runtime-check snapshot; update decisions follow the current source version on any thread.
 */
data class RuntimeUpdateCheck(
    /** 正在查询发布清单。 / Whether the release index is being queried. */
    val checking: Boolean = false,
    /** 已完成一次查询。 / Whether a query has completed. */
    val checked: Boolean = false,
    /** 发布渠道的 Runtime 版本。 / The Runtime version in the release channel. */
    val latestVersion: String? = null,
    /** 查询或部署的错误。 / The query or deployment error. */
    val error: String? = null,
    /** 当前源码提交与已部署镜像输入组成的版本。 / Current source commit plus deployed image inputs. */
    val installedVersion: String? = null,
) {
    /**
     * 已知发布版的 AP 提交与当前版本不同；仅构建输入变化不要求重装。
     *
     * Whether a known release has a different AP commit; build-input changes alone need no reinstall.
     */
    val available: Boolean
        get() = checked && error == null && runtimeUpdateNeeded(installedVersion, latestVersion)

    /**
     * 源码有差异且镜像构建输入相同，可优先交给热更通道。
     *
     * Whether source differs with identical image inputs, allowing the hot-update path first.
     */
    val commitOnly: Boolean
        get() = available && installedVersion != null && latestVersion != null &&
            installedVersion.substringAfterLast('-') == latestVersion.substringAfterLast('-')
}

/**
 * 判断是否需要取得新 AP 版本；标准版本只比较提交，旧格式保留完整版本比较。
 * 纯函数，可在任意线程调用。
 *
 * Determines whether a new AP version is needed; compares commits for standard versions and
 * complete versions for legacy formats. Pure and safe on any thread.
 */
internal fun runtimeUpdateNeeded(installedVersion: String?, latestVersion: String?): Boolean {
    if (latestVersion == null || latestVersion == installedVersion) return false
    if (installedVersion != null && ROOTFS_VERSION.matches(installedVersion) &&
        ROOTFS_VERSION.matches(latestVersion)
    ) {
        return installedVersion.substringBefore('-') != latestVersion.substringBefore('-')
    }
    return true
}

/**
 * 用清单中的当前源码提交生成展示与更新检查版本；部署记录不一致或字段无效时保留原版本。
 * 纯函数，可在任意线程调用。
 *
 * Combines the manifest's current source commit with deployed inputs for display and update checks;
 * retains the original version on inconsistent deployment records or invalid fields.
 * Pure and safe on any thread.
 */
internal fun runtimeVersionWithSource(
    deployedVersion: String?,
    manifestVersion: String?,
    sourceCommit: String?,
): String? {
    if (deployedVersion == null || deployedVersion != manifestVersion ||
        !ROOTFS_VERSION.matches(deployedVersion) || sourceCommit == null ||
        !SOURCE_COMMIT.matches(sourceCommit)
    ) return deployedVersion
    return "${sourceCommit.take(12)}-${deployedVersion.substringAfterLast('-')}"
}

private val ROOTFS_VERSION = Regex("[0-9a-f]{12}-[0-9a-f]{10}")
private val SOURCE_COMMIT = Regex("[0-9a-f]{40}")
