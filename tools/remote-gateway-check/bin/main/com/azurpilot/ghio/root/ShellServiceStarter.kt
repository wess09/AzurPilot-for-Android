package com.azurpilot.ghio.root

/**
 * 内置 ADB 的 app_process 入口；强制使用 Context 初始化，绕开联发科 Application 注入。
 *
 * Runs the embedded ADB app_process entry with Context-only initialization to avoid MediaTek
 * Application hooks. Called only on the privileged process main thread.
 */
object ShellServiceStarter {
    /**
     * 转交带 Context-only 标记的启动参数。
     *
     * Forwards startup arguments with a Context-only marker.
     */
    @JvmStatic
    fun main(args: Array<String>) = RootServiceStarter.main(args + "--context-only")
}
