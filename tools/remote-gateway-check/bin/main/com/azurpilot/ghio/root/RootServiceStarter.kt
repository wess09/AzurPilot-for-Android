package com.azurpilot.ghio.root

import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.third.Ln

/**
 * 由 `app_process --class` 调起的 root/shell 特权服务 JVM 入口。
 *
 * 类名由 native `launcher.c` 通过参数传入，且受 R8 keep 规则保护，不能重命名。入口准备
 * Looper、反射构建 [RootUserService]、经 bootstrap provider 回传 Binder，并观察 app
 * 生命周期 Binder；app 进程死亡时向远端服务发送 one-way destroy 事务后退出。
 *
 * JVM entry point for the root/shell privileged service launched by `app_process --class`.
 *
 * The native `launcher.c` supplies this class name as an argument and R8 keep rules protect it
 * from renaming. The entry prepares a Looper, reflectively builds [RootUserService], returns its
 * Binder through the bootstrap provider, and watches the app lifecycle Binder; app-process death
 * sends a one-way destroy transaction to the remote service before exit.
 */
object RootServiceStarter {

    private const val TAG = "RootServiceStarter"
    /**
     * [RemoteService.destroy] 的显式 AIDL transaction code。
     *
     * 生命周期死亡清理直接调用 [IBinder.transact]，绕过生成的 Stub；这里必须与
     * `RemoteService.aidl` 中的 `destroy() = 16777114` 保持一致。
     *
     * Explicit AIDL transaction code for [RemoteService.destroy].
     *
     * Lifecycle-death cleanup calls [IBinder.transact] directly and bypasses the generated Stub, so
     * this value must stay synchronized with `destroy() = 16777114` in `RemoteService.aidl`.
     */
    private const val DESTROY_TRANSACTION_CODE = 16777114

    // linkToDeath 的观察对象与 DeathRecipient 都必须保留强引用直到进程退出。
    private var appLifecycleBinder: IBinder? = null
    private var appDeathRecipient: IBinder.DeathRecipient? = null

    /**
     * 启动特权服务并进入该进程唯一的主 Looper。
     *
     * 仅由 native launcher 的新 JVM 调用；任一创建或 Binder 回传失败都以非零状态退出，避免
     * 留下无法被 app 控制的特权进程。
     *
     * Starts the privileged service and enters this process's only main Looper.
     *
     * Only the native launcher's fresh JVM calls this. Any creation or Binder-handoff failure exits
     * nonzero so no privileged process remains that the app cannot control.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        System.err.println("[RootServiceStarter] main() entry")
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        val createdService = RootUserService.create(args)
        if (createdService == null) {
            System.err.println("[RootServiceStarter] RootUserService.create() returned null")
            System.exit(1)
            return
        }
        System.err.println("[RootServiceStarter] RootUserService.create() ok, token=" + createdService.token)

        if (!sendBinder(createdService)) {
            System.err.println("[RootServiceStarter] sendBinder() failed")
            System.exit(1)
            return
        }
        System.err.println("[RootServiceStarter] sendBinder() ok, entering Looper")

        Looper.loop()
        System.exit(0)
    }

    /**
     * 回传服务 Binder、预置心跳并登记 app 死亡观察。
     *
     * [RootServiceBootstrapClient] 失败或 `linkToDeath` 失败时返回 false，调用 [main] 随即
     * 退出。死亡回调在 Binder 线程执行，只发送 one-way 销毁事务并终止该子进程。
     *
     * Returns the service Binder, primes heartbeat, and registers app-death observation.
     *
     * Failure in [RootServiceBootstrapClient] or `linkToDeath` returns false and [main] exits.
     * The death callback runs on a Binder thread; it only sends a one-way destroy transaction and
     * terminates this child process.
     */
    private fun sendBinder(createdService: RootUserService.CreatedService): Boolean {
        val result = RootServiceBootstrapClient.attachRemoteService(
            createdService.packageName,
            createdService.userId,
            createdService.token,
            createdService.service
        )
        if (result == null) {
            return false
        }

        primeHeartbeat(createdService.service, result.appPid)

        try {
            val recipient = IBinder.DeathRecipient {
                Ln.i(TAG + ": app process died, destroying root service")
                destroyService(createdService.service)
                Ln.i(TAG + ": root service destroy signal sent, exiting")
                System.exit(0)
            }
            val lifecycleBinder = result.lifecycleBinder
            lifecycleBinder.linkToDeath(recipient, 0)
            appLifecycleBinder = lifecycleBinder
            appDeathRecipient = recipient
            return true
        } catch (tr: Throwable) {
            Ln.e(TAG + ": failed to link app lifecycle binder", tr)
            return false
        }
    }

    /**
     * 预先武装本地 [RemoteService] 的 app-pid 看门狗。
     *
     * 非本地服务 Binder、无效 pid 或任何反射/实现异常都只记警告，因为生命周期 Binder 仍是
     * 最终关闭保障。
     *
     * Primes the app-pid watchdog of a local [RemoteService].
     *
     * A nonlocal Binder, invalid pid, or implementation failure only logs a warning because the
     * lifecycle Binder remains the final shutdown guarantee.
     */
    private fun primeHeartbeat(service: IBinder, appPid: Int) {
        if (appPid <= 0) {
            return
        }
        try {
            val local = service.queryLocalInterface(RemoteService::class.java.name)
            if (local is RemoteService) {
                local.heartbeat(appPid)
            }
        } catch (tr: Throwable) {
            Ln.w(TAG + ": prime heartbeat failed", tr)
        }
    }

    /**
     * 向远端服务发送非阻塞销毁事务。
     *
     * 对方可能已死或实现不支持该事务，故该函数只做尽力而为清理；所有 Parcel 无条件回收。
     *
     * Sends a nonblocking destroy transaction to the remote service.
     *
     * The peer may already be dead or not support this transaction, so cleanup is best effort; all
     * Parcels are recycled unconditionally.
     */
    private fun destroyService(service: IBinder?) {
        if (service == null || !service.pingBinder()) {
            return
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            val descriptor = service.interfaceDescriptor
            if (descriptor != null) {
                data.writeInterfaceToken(descriptor)
            }
            service.transact(DESTROY_TRANSACTION_CODE, data, reply, Binder.FLAG_ONEWAY)
        } catch (tr: Throwable) {
            Ln.w(TAG + ": destroy root remote service failed", tr)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
