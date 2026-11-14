package com.azurpilot.ghio.remote.internal

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * 采集线程工厂：按 urgent-display 优先级起 [HandlerThread] 并返回其 Handler
 *
 * 特权进程内的帧采集与显示监听回调都跑在这种线程上，优先级不够会掉帧。
 * 线程随进程存活，本类不提供退出接口。
 *
 * Capture thread factory: starts a [HandlerThread] at urgent-display priority and returns its
 * Handler.
 *
 * Frame capture and display-listener callbacks inside the privileged process run on these
 * threads; at lower priority frames drop. The thread lives for the process lifetime — no quit
 * API is offered here.
 */
object FrameCaptureHelper {

    /**
     * 创建指定名字的采集 Handler
     *
     * onLooperPrepared 里再设一次优先级是兜底，确保 urgent-display 优先级真正生效。
     *
     * Creates a capture Handler with the given thread name.
     *
     * The priority is re-applied inside onLooperPrepared as a defensive step, making sure the
     * urgent-display priority actually takes effect.
     *
     * @param name 线程名（logcat / dumpsys 可见）/ thread name (visible in logcat / dumpsys)
     */
    fun createCaptureHandler(name: String): Handler {
        val thread = object : HandlerThread(name, Process.THREAD_PRIORITY_URGENT_DISPLAY) {
            override fun onLooperPrepared() {
                // 兜底：构造传入的优先级偶发未生效，looper 就绪后再设一次
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            }
        }
        thread.start()
        return Handler(thread.looper)
    }
}
