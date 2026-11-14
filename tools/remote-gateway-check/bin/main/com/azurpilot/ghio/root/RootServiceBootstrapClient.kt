package com.azurpilot.ghio.root

import android.content.IContentProvider
import android.os.Binder
import android.os.Bundle
import android.os.IBinder

import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager

/**
 * 从 root/shell 子进程向 app 进程的 bootstrap provider 回传特权服务 Binder。
 *
 * root launcher 不能直接绑定普通 app 进程服务，故通过受 app UID 保护的 ContentProvider
 * 交换一次性 token、远端服务 Binder 与 app 生命周期 Binder。该对象仅在
 * [RootServiceStarter] 的特权进程线程使用。
 *
 * Returns the privileged-service Binder from a root/shell child process to the app process's
 * bootstrap provider.
 *
 * The root launcher cannot bind an ordinary app-process service directly, so it exchanges a
 * one-time token, remote-service Binder, and app-lifecycle Binder through a ContentProvider
 * protected by the app UID. This object is used only on the privileged-process thread of
 * [RootServiceStarter].
 */
object RootServiceBootstrapClient {

    /**
     * 封装握手成功后 app 进程交还的生命周期观察 Binder 与其 pid。
     *
     * [lifecycleBinder] 死亡表示 app 进程已退出，root 子进程必须停止服务；[appPid] 用于
     * 预先配置特权服务的 `/proc` 看门狗。
     *
     * Holds the lifecycle-observation Binder and process id returned by a successful handshake.
     *
     * Death of [lifecycleBinder] means the app process exited and the root child must stop its
     * service; [appPid] preconfigures the privileged service's `/proc` watchdog.
     */
    data class BootstrapResult(val lifecycleBinder: IBinder, val appPid: Int)

    /**
     * 查找 app 进程 bootstrap provider，并一次性回传 [serviceBinder]。
     *
     * 成功时返回生命周期观测信息；任何 provider 获取、Binder 存活检查或协议调用失败都返回
     * null。finally 中必须移除外部 provider 引用，否则 ActivityManager 会认为 shell
     * 调用方仍持有 provider。
     *
     * Finds the app-process bootstrap provider and returns [serviceBinder] exactly once.
     *
     * Returns lifecycle-observation data on success; any provider lookup, Binder-liveness, or
     * protocol-call failure returns null. The external provider reference must be removed in
     * finally so ActivityManager does not think the shell caller still holds it.
     */
    fun attachRemoteService(
        packageName: String,
        userId: Int,
        token: String,
        serviceBinder: IBinder
    ): BootstrapResult? {
        val authority = packageName + RootServiceBootstrapRegistry.AUTHORITY_SUFFIX
        val providerToken = Binder()
        var provider: IContentProvider? = null

        System.err.println("[BootstrapClient] attachRemoteService authority=" + authority + " userId=" + userId)
        try {
            provider = ServiceManager.getActivityManager()
                .getContentProviderExternal(authority, userId, providerToken, authority)
            if (provider == null) {
                Ln.e("Root bootstrap provider is null: " + authority + " user=" + userId)
                System.err.println("[BootstrapClient] getContentProviderExternal returned null")
                return null
            }
            if (!provider.asBinder().pingBinder()) {
                Ln.e("Root bootstrap provider is dead: " + authority + " user=" + userId)
                System.err.println("[BootstrapClient] provider binder is dead")
                return null
            }
            System.err.println("[BootstrapClient] provider ok, calling METHOD_ATTACH_REMOTE_SERVICE")

            val extras = Bundle()
            extras.putString(RootServiceBootstrapRegistry.KEY_TOKEN, token)
            extras.putBinder(RootServiceBootstrapRegistry.KEY_SERVICE_BINDER, serviceBinder)

            val reply = RootIContentProviderCompat.call(
                provider,
                null,
                null,
                authority,
                RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE,
                null,
                extras
            )
            if (reply == null) {
                Ln.e("Root bootstrap provider returned null")
                System.err.println("[BootstrapClient] provider.call() returned null")
                return null
            }

            val lifecycleBinder = reply.getBinder(RootServiceBootstrapRegistry.KEY_APP_BINDER)
            if (lifecycleBinder == null || !lifecycleBinder.pingBinder()) {
                Ln.e("Root bootstrap app lifecycle binder missing")
                System.err.println("[BootstrapClient] app lifecycle binder missing or dead")
                return null
            }
            val appPid = reply.getInt(RootServiceBootstrapRegistry.KEY_APP_PID, 0)
            System.err.println("[BootstrapClient] lifecycle binder received ok, appPid=" + appPid)
            return BootstrapResult(lifecycleBinder, appPid)
        } catch (tr: Throwable) {
            Ln.e("Failed to send binder back to app", tr)
            System.err.println("[BootstrapClient] exception: " + tr)
            tr.printStackTrace(System.err)
            return null
        } finally {
            if (provider != null) {
                ServiceManager.getActivityManager().removeContentProviderExternal(authority, providerToken)
            }
        }
    }
}
