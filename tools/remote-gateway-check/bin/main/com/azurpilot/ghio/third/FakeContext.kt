package com.azurpilot.ghio.third

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.IContentProvider
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.third.wrappers.ServiceManager

import java.lang.reflect.Field

/**
 * 以 `com.android.shell` 身份伪装成 Context 的假上下文（进程级单例，[get] 取用）
 *
 * 特权进程没有真实的 App 上下文，而 framework 不少公开 API 要求一个 Context。
 * 本类借 [Workarounds.getSystemContext]（ActivityThread 的 system context）做基座，
 * 再把包名 / 归属信息改成 shell 身份，使剪贴板、content provider 等系统能力以
 * adb shell 同等的权限工作。
 *
 * 从 scrcpy 服务端的 `third/FakeContext.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * A fake Context impersonating `com.android.shell` (process-level singleton,
 * obtained through [get]).
 *
 * The privileged process has no real app context, yet many framework APIs
 * demand one. This class builds on [Workarounds.getSystemContext] (the
 * ActivityThread system context) as its base, then overrides the package name
 * and attribution to the shell identity so that clipboard, content providers
 * and other system capabilities work with adb-shell-equivalent privileges.
 *
 * Ported from scrcpy's server `third/FakeContext.java` (Apache-2.0,
 * Genymobile/scrcpy).
 */
class FakeContext private constructor() : ContextWrapper(Workarounds.getSystemContext()) {

    /**
     * 恒返回已授权 / Returns granted unconditionally.
     *
     * 本进程实际以 shell/root 身份运行，权限由内核与系统服务侧保证；走默认实现
     * 反而会被 package 管理器按"假包名"查表而误判。
     *
     * The process really runs as shell/root with permissions enforced kernel- and
     * system-service-side; the default lookup would misjudge against the fake
     * package name.
     */
    override fun checkCallingPermission(permission: String): Int {
        return PackageManager.PERMISSION_GRANTED
    }

    private val contentResolverImpl: ContentResolver = object : ContentResolver(this@FakeContext) {
        // 以下方法在 SDK 桩里不可见（@hide），编译期是普通方法，运行期按名字+签名虚接 ContentResolver

        fun acquireProvider(c: Context?, name: String): IContentProvider? {
            return ServiceManager.getActivityManager().getContentProviderExternal(name, Binder())
        }

        fun releaseProvider(icp: IContentProvider?): Boolean {
            return false
        }

        fun acquireUnstableProvider(c: Context?, name: String?): IContentProvider? {
            return null
        }

        fun releaseUnstableProvider(icp: IContentProvider?): Boolean {
            return false
        }

        fun unstableProviderDied(icp: IContentProvider?) {
            // ignore
        }
    }

    /** 恒为 shell 包名 / Always the shell package name. */
    override fun getPackageName(): String {
        return PACKAGE_NAME
    }

    /** 恒为 shell 包名（op 归属与包名保持一致）/ Always the shell package name (keeps op attribution consistent). */
    override fun getOpPackageName(): String {
        return PACKAGE_NAME
    }

    /**
     * 伪造归属源：包名 shell、UID [Process.SHELL_UID]
     *
     * API 31+ 系统服务按 AttributionSource 校验调用方；给出 shell 身份才能通过。
     *
     * Fakes the attribution source: shell package with [Process.SHELL_UID].
     *
     * Since API 31 system services validate callers through AttributionSource;
     * presenting the shell identity is what passes the check.
     */
    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    override fun getAttributionSource(): AttributionSource {
        val builder = AttributionSource.Builder(Process.SHELL_UID)
        builder.setPackageName(PACKAGE_NAME)
        return builder.build()
    }

    /** 恒为默认设备（0）/ Always the default device (0). */
    override fun getDeviceId(): Int {
        return 0
    }

    /** 返回自身：假上下文没有外层 Application / Returns itself: there is no outer Application for a fake context. */
    override fun getApplicationContext(): Context {
        return this
    }

    /** 返回以 [ServiceManager.getActivityManager] 获取 provider 的自建 resolver / Returns the custom resolver that acquires providers via [ServiceManager.getActivityManager]. */
    override fun getContentResolver(): ContentResolver {
        return contentResolverImpl
    }

    /**
     * 返回基座服务，但对剪贴板做一次"改锚"
     *
     * [ClipboardManager] 内部持有创建它的 Context（mContext），剪贴板 RPC 的归属
     * 校验会用到；这里反射把它改指到本假上下文，让剪贴板请求归属到
     * [PACKAGE_NAME]（shell）而不是 system context。
     *
     * Returns the base service, with a re-anchor for the clipboard.
     *
     * [ClipboardManager] internally holds the Context it was created from
     * (mContext), which clipboard RPCs use for attribution; reflection repoints
     * it at this fake context so clipboard requests attribute to
     * [PACKAGE_NAME] (shell) instead of the system context.
     */
    @SuppressLint("SoonBlockedPrivateApi")
    override fun getSystemService(name: String): Any? {
        val service = super.getSystemService(name) ?: return null

        if (Context.CLIPBOARD_SERVICE == name) {
            try {
                val field: Field = ClipboardManager::class.java.getDeclaredField("mContext")
                field.isAccessible = true
                field[service] = this
            } catch (e: ReflectiveOperationException) {
                throw RuntimeException(e)
            }
        }

        return service
    }

    companion object {
        /** 与 adb shell 身份绑定的包名，系统服务按它核对 UID 归属 / Package name bound to the adb shell identity, checked against the caller UID by system services. */
        const val PACKAGE_NAME = "com.android.shell"

        /**
         * root 身份 UID；镜像 `android.os.Process.ROOT_UID`，但该常量 API 29 才引入
         *
         * The root UID; mirrors `android.os.Process.ROOT_UID`, which only exists
         * from API 29 on.
         */
        const val ROOT_UID = 0

        // 饿汉单例：类加载即创建，JVM 类初始化天然线程安全
        private val INSTANCE = FakeContext()

        /** 取进程级单例 / Returns the process-level singleton. */
        fun get(): FakeContext {
            return INSTANCE
        }
    }
}
