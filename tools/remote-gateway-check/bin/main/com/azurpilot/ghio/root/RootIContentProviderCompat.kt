package com.azurpilot.ghio.root

import android.content.AttributionSource
import android.content.IContentProvider
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.system.Os

/**
 * 在 root/shell 特权进程中跨 Android 版本调用 [IContentProvider]。
 *
 * Android 10 到 12+ 的隐藏 `call` 签名不兼容：Android 12+ 优先传入由当前 uid 构造的
 * [AttributionSource]，但 OEM 可能保留旧签名，故在链接或运行时失败时退回兼容重载。
 * 此对象仅在特权进程的启动握手路径使用，调用方负责处理 [RemoteException]。
 *
 * Calls [IContentProvider] across Android-version differences from the root/shell privileged
 * process.
 *
 * Hidden `call` signatures differ across Android 10 through 12+: Android 12+ first receives an
 * [AttributionSource] built from the current uid, but OEMs may retain an older signature, so a
 * linkage or runtime failure falls back to a compatible overload. This object is used only on the
 * privileged-process bootstrap handshake; callers handle [RemoteException].
 */
object RootIContentProviderCompat {

    private const val SHELL_PACKAGE = "com.android.shell"

    /**
     * 调用目标 provider 的兼容 `call` 重载。
     *
     * [attributeTag]、[callingPkg] 与 [extras] 原样转交；[callingPkg] 为空时使用 shell
     * 包名以匹配由 launcher 降权后的真实调用身份。
     *
     * Calls a compatible `call` overload on [provider].
     *
     * [attributeTag], [callingPkg], and [extras] are passed through unchanged. A null
     * [callingPkg] uses the shell package so attribution matches the caller identity after
     * launcher demotion.
     *
     * @throws RemoteException when the provider's Binder transaction fails.
     */
    @Throws(RemoteException::class)
    fun call(
        provider: IContentProvider,
        attributeTag: String?,
        callingPkg: String?,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        val pkg = callingPkg ?: SHELL_PACKAGE
        return if (Build.VERSION.SDK_INT >= 31) {
            try {
                val attributionSource = AttributionSource.Builder(Os.getuid())
                    .setAttributionTag(attributeTag)
                    .setPackageName(pkg)
                    .build()
                provider.call(attributionSource, authority, method, arg, extras)
            } catch (e: RuntimeException) {
                provider.call(pkg, attributeTag, authority, method, arg, extras)
            } catch (e: LinkageError) {
                provider.call(pkg, attributeTag, authority, method, arg, extras)
            }
        } else if (Build.VERSION.SDK_INT == 30) {
            provider.call(pkg, attributeTag, authority, method, arg, extras)
        } else if (Build.VERSION.SDK_INT == 29) {
            provider.call(pkg, authority, method, arg, extras)
        } else {
            provider.call(pkg, method, arg, extras)
        }
    }
}
