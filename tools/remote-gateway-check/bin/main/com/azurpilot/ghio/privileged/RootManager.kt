package com.azurpilot.ghio.privileged
import com.azurpilot.ghio.AppDispatchers

import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.domain.RemoteBackend
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Root 提权后端：经 libsu 的 Shell 与 su 对话
 *
 * 可用性 = 已授权，或 PATH 上找得到可执行 `su`；授权 = `Shell.getShell()` 拉起
 * 的 shell 是 root 身份（用户在 su 提示框点了允许）。失败模式：用户拒绝 su、
 * 设备没有 root。su 无常驻状态回调，授权态只在主动拉 shell 时变化。
 *
 * 进程级单例；[Shell.setDefaultBuilder] 只对尚未创建的主 shell 生效，
 * 所以配置放在 `init` 里赶在首次用 shell 之前。
 *
 * The Root privilege backend; talks to su through libsu's Shell.
 *
 * Available means already granted or an executable `su` found on PATH;
 * granted means the shell from `Shell.getShell()` runs as root (the user
 * accepted the su prompt). Failure modes: the user denies su, or the device
 * is not rooted. su has no persistent state callback — the grant only changes
 * when a shell is actively brought up.
 *
 * Process-level singleton; [Shell.setDefaultBuilder] only affects the main
 * shell before it exists, so the configuration sits in `init` ahead of the
 * first shell use.
 */
object RootManager : RemoteAccessPermissionBackend {

    override val backend = RemoteBackend.ROOT

    private val listeners = CopyOnWriteArraySet<RemoteAccessStateListener>()

    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        // 默认 builder 只在主 shell 创建前生效，须赶在首次 getShell 之前设置
        @Suppress("DEPRECATION")
        Shell.setDefaultBuilder(
            Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR)
        )
    }

    /**
     * libsu 报告的 root 状态：isAppGrantedRoot，或缓存 shell 自报 root
     *
     * Root status as libsu reports it: isAppGrantedRoot, or the cached shell
     * self-reporting root.
     */
    override fun isGranted(): Boolean {
        return runCatching {
            Shell.isAppGrantedRoot() == true || Shell.getCachedShell()?.isRoot == true
        }.getOrDefault(false)
    }

    /**
     * 已授权即视为可用；否则沿 PATH 逐项找可执行的 `su`
     *
     * Granted counts as available; otherwise PATH entries are scanned for an
     * executable `su`.
     */
    override fun isAvailable(): Boolean {
        if (isGranted()) return true
        val exec = System.getenv("PATH")?.split(":") ?: return false
        for (path in exec) {
            val su = File(path, "su")
            if (su.canExecute()) {
                return true
            }
        }
        return false
    }

    /**
     * 拉起 root shell 触发 su 授权对话框（IO 线程）
     *
     * Brings up a root shell, which triggers the su prompt (on the IO
     * dispatcher).
     *
     * @return shell 是否以 root 身份跑起来了 / whether the shell came up as root
     */
    override suspend fun requestPermission(): Boolean = withContext(AppDispatchers.IO) {
        val granted = runCatching {
            Shell.getShell().isRoot
        }.onFailure {
            Timber.e(it, "request root permission failed")
        }.getOrDefault(false)
        notifyStateChanged()
        granted
    }

    override fun addStateListener(listener: RemoteAccessStateListener) {
        listeners += listener
    }

    override fun removeStateListener(listener: RemoteAccessStateListener) {
        listeners -= listener
    }


    /**
     * 广播状态变化；单个监听器抛异常不影响其余监听器
     *
     * Fans the state change out to listeners; one throwing never blocks the
     * rest.
     */
    private fun notifyStateChanged() {
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(backend) }
                .onFailure { Timber.w(it, "notifyStateChanged failed") }
        }
    }
}
