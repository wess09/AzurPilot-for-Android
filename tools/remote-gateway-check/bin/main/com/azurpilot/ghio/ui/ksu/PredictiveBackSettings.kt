package com.azurpilot.ghio.ui.ksu

import android.content.pm.ApplicationInfo
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 沿用 KernelSU 的预测式返回设置桥接，Android 14 起更新应用标记。
 *
 * Uses KernelSU's predictive-back settings bridge to update the app flag on Android 14+.
 */
fun setEnableOnBackInvokedCallback(appInfo: ApplicationInfo, enable: Boolean) {
    if (Build.VERSION.SDK_INT < 34) return
    HiddenApiBypass.addHiddenApiExemptions(
        "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"
    )
    runCatching {
        val method =
            ApplicationInfo::class
                .java
                .getDeclaredMethod(
                    "setEnableOnBackInvokedCallback",
                    Boolean::class.javaPrimitiveType,
                )
        method.isAccessible = true
        method.invoke(appInfo, enable)
    }
        .onFailure { timber.log.Timber.w(it, "Unable to apply predictive back setting") }
}
