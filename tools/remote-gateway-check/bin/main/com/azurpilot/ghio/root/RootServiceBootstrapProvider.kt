package com.azurpilot.ghio.root

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import timber.log.Timber

/**
 * 接收 root/shell 子进程 Binder 回传的 app 进程 ContentProvider 端点。
 *
 * 仅接受 shell UID 或 root UID 的 [call] 请求，并要求 token 已由
 * [RootServiceBootstrapRegistry.register] 登记。Provider 在 app 主进程内运行，Binder
 * 交换本身发生在系统 Binder 线程，Registry 提供并发安全的一次性消费。
 *
 * App-process ContentProvider endpoint that receives a root/shell child Binder handoff.
 *
 * It accepts [call] requests only from shell or root UID and requires a token registered through
 * [RootServiceBootstrapRegistry.register]. The provider runs in the app main process; the Binder
 * exchange occurs on system Binder threads, while the Registry provides concurrent one-time
 * consumption.
 */
class RootServiceBootstrapProvider : ContentProvider() {

    /** 初始化无状态 provider；注册表在进程级 object 中维护。 / Initializes a stateless provider; the process-level object owns the registry. */
    override fun onCreate(): Boolean = true

    /**
     * 验证调用 UID 和一次性 token 后接收特权服务 Binder。
     *
     * 成功回复 app 生命周期 Binder 与 app pid；非法调用者、缺字段或已消费/未知 token 均返回
     * null，且不会泄露注册表状态。
     *
     * Receives the privileged-service Binder after validating caller UID and one-time token.
     *
     * A successful reply carries the app lifecycle Binder and pid. Invalid callers, missing
     * fields, and consumed or unknown tokens return null without exposing registry state.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != RootServiceBootstrapRegistry.METHOD_ATTACH_REMOTE_SERVICE || extras == null) {
            return super.call(method, arg, extras)
        }
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SHELL_UID && callingUid != 0) {
            Timber.w("Rejecting root bootstrap caller uid=%s", callingUid)
            return null
        }

        val token = extras.getString(RootServiceBootstrapRegistry.KEY_TOKEN)
            ?: return null
        val binder = extras.getBinder(RootServiceBootstrapRegistry.KEY_SERVICE_BINDER)
            ?: return null

        val appBinder = RootServiceBootstrapRegistry.attach(token, binder) ?: run {
            Timber.w("Root bootstrap token not found: %s", token)
            return null
        }

        return Bundle().apply {
            putBinder(RootServiceBootstrapRegistry.KEY_APP_BINDER, appBinder)
            putInt(RootServiceBootstrapRegistry.KEY_APP_PID, Process.myPid())
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
