package com.azurpilot.ghio.remote.internal

import android.content.pm.IPackageManager
import android.os.IDeviceIdleController
import android.os.Process
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln
import com.android.internal.app.IAppOpsService
import rikka.shizuku.SystemServiceHelper

/**
 * 特权进程内的系统服务句柄与 shell 小工具
 *
 * 经 Shizuku 的 SystemServiceHelper 取 binder——shell/root 身份下拿到的就是系统服务本体，
 * 暴露的是隐藏 API 接口（IPackageManager 等），编译期签名由 hidden-api 桩提供。
 * 全部 lazy：首次用到才连 binder。
 *
 * System service handles and small shell helpers inside the privileged process.
 *
 * Binders are fetched via Shizuku's SystemServiceHelper — under the shell/root identity these
 * are the actual system services, exposing hidden-API interfaces (IPackageManager etc.) whose
 * signatures are provided by the hidden-api stubs at compile time. Everything is lazy: the
 * binder is connected on first use.
 */
object RemoteUtils {
    private const val TAG = "RemoteUtils"

    /** IPackageManager 直连：授运行时权限用 / Direct IPackageManager: used to grant runtime permissions */
    val packageManager: IPackageManager by lazy {
        val binder = SystemServiceHelper.getSystemService("package")
        IPackageManager.Stub.asInterface(binder)
    }

    /** IAppOpsService 直连：AppOps 读写 / Direct IAppOpsService: AppOps read and write */
    val appOpsService: IAppOpsService by lazy {
        val binder = SystemServiceHelper.getSystemService("appops")
        IAppOpsService.Stub.asInterface(binder)
    }

    /** IDeviceIdleController 直连：电池白名单 / Direct IDeviceIdleController: battery whitelist */
    val deviceIdleController: IDeviceIdleController by lazy {
        val binder = SystemServiceHelper.getSystemService("deviceidle")
        IDeviceIdleController.Stub.asInterface(binder)
    }

    /**
     * 查包 uid；包不存在等失败返回 -1（调用方以此判空）
     *
     * Looks up the package's uid; -1 on failure such as a missing package (callers branch on
     * that value).
     */
    fun getAppUid(packageName: String): Int = runCatching {
        FakeContext.get().packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrElse {
        Ln.w("$TAG: getAppUid failed for $packageName: ${it.message}")
        -1
    }

    /**
     * 同步执行 shell 命令并等退出码；不收集输出（需要输出走 [BridgeServer] 的 shell 端点）。
     * 阻塞至命令退出。
     *
     * Runs a shell command synchronously and returns its exit code; output is not captured
     * (use [BridgeServer]'s shell endpoint when output is needed). Blocks until the command
     * exits.
     */
    fun shellExec(command: String): Int {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val exitCode = process.waitFor()
        Ln.i("$TAG: $command -> exitCode=$exitCode")
        return exitCode
    }
}
