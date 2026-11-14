package com.azurpilot.ghio.third.wrappers

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.view.InputEvent
import android.view.MotionEvent

import com.azurpilot.ghio.constant.AndroidVersions
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

import android.hardware.input.InputManager as SystemInputManager

/**
 * 系统 `InputManager` 的反射包装（注入输入事件与事件加工）
 *
 * 经 [FakeContext.getSystemService] 拿到真实 InputManager 后，调用 targetSdk 下
 * 不可见的隐藏 `injectInputEvent`（INJECT_EVENTS 特权），把 [InputEvent] 直接送
 * 进系统输入管线；另提供给事件绑定目标显示器（[InputManager.setDisplayId]）与
 * 设置鼠标动作键（[InputManager.setActionButton]）的隐藏方法包装。实例经
 * [InputManager.create] 创建，供特权进程的注入线程调用（binder 调用阻塞）。
 *
 * 从 scrcpy 服务端的 `third/wrappers/InputManager.java` 移植（Apache-2.0,
 * Genymobile/scrcpy）。
 *
 * Reflection wrapper around the system `InputManager` (event injection and
 * event tweaking).
 *
 * After obtaining the real InputManager via [FakeContext.getSystemService],
 * invokes the hidden `injectInputEvent` (INJECT_EVENTS privilege, invisible at
 * targetSdk) to feed [InputEvent]s straight into the system input pipeline;
 * also wraps the hidden methods binding an event to a target display
 * ([InputManager.setDisplayId]) and setting the mouse action button
 * ([InputManager.setActionButton]). Instances come from [InputManager.create],
 * called from the privileged process's injection threads (binder calls block).
 *
 * Ported from scrcpy's server `third/wrappers/InputManager.java`
 * (Apache-2.0, Genymobile/scrcpy).
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
class InputManager private constructor(private val manager: SystemInputManager) {

    // 上次权限错误日志的时间戳（ms），用于限流
    private var lastPermissionLogDate = 0L

    /**
     * 注入一个输入事件到目标显示器
     *
     * [mode] 取 [Companion.INJECT_INPUT_EVENT_MODE_ASYNC] 等隐藏常量，决定注入
     * 是否等待事件处理完成。
     *
     * 缺 INJECT_EVENTS 权限（部分 MIUI 需先开启"USB 调试（安全设置）"并重启）
     * 是可预期的失败：限流到每 3 秒一条错误日志、不打栈，其余反射异常照常
     * 完整记录。
     *
     * Injects an input event into the target display.
     *
     * [mode] is one of the hidden constants such as
     * [Companion.INJECT_INPUT_EVENT_MODE_ASYNC] and decides whether injection
     * waits for the event to finish processing.
     *
     * A missing INJECT_EVENTS permission (some MIUI devices require enabling
     * "USB debugging (Security Settings)" and rebooting) is an expected
     * failure: throttled to one error log every 3 seconds without a stack
     * trace, while other reflective failures are logged in full.
     *
     * @return 系统是否接受该事件 / whether the system accepted the event
     */
    fun injectInputEvent(inputEvent: InputEvent, mode: Int): Boolean {
        return try {
            val method = getInjectInputEventMethod()
            method.invoke(manager, inputEvent, mode) as Boolean
        } catch (e: ReflectiveOperationException) {
            if (e is InvocationTargetException) {
                val cause = e.cause
                if (cause is SecurityException) {
                    val message = e.cause?.message
                    if (message != null && message.contains("INJECT_EVENTS permission")) {
                        // 限流：权限错误日志每 3 秒最多一条，避免刷屏
                        val now = System.currentTimeMillis()
                        if (lastPermissionLogDate <= now - 3000) {
                            Ln.e(message)
                            Ln.e("Make sure you have enabled \"USB debugging (Security Settings)\" and then rebooted your device.")
                            lastPermissionLogDate = now
                        }
                        // 不打印异常栈
                        return false
                    }
                }
            }
            Ln.e("Could not invoke method", e)
            false
        }
    }

    /**
     * 把输入端口与自定义唯一标识关联（API 35+ 隐藏方法）
     *
     * 用于把指定物理端口上的输入设备映射到虚拟输入标识；失败仅记日志。
     *
     * Associates an input port with a custom unique id (hidden method, API 35+).
     *
     * Maps input devices on the given physical port to a virtual input
     * identity; failures are only logged.
     */
    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    fun addUniqueIdAssociationByPort(inputPort: String, uniqueId: String) {
        try {
            val method = getAddUniqueIdAssociationByPortMethod()
            method.invoke(manager, inputPort, uniqueId)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Cannot add unique id association by port", e)
        }
    }

    /**
     * 解除输入端口的自定义唯一标识关联（API 35+ 隐藏方法）；失败仅记日志
     *
     * Removes the input port's custom unique id association (hidden method,
     * API 35+); failures are only logged.
     */
    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    fun removeUniqueIdAssociationByPort(inputPort: String) {
        try {
            val method = getRemoveUniqueIdAssociationByPortMethod()
            method.invoke(manager, inputPort)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Cannot remove unique id association by port", e)
        }
    }

    internal companion object {
        /**
         * 镜像隐藏常量 `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`（注入即返回）
         *
         * Mirrors the hidden `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`
         * (fire-and-forget injection).
         */
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

        /**
         * 镜像隐藏常量 `INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT`（等待分发结果）
         *
         * Mirrors the hidden `INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT` (waits
         * for the dispatch result).
         */
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1

        /**
         * 镜像隐藏常量 `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH`（等待事件处理完）
         *
         * Mirrors the hidden `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH` (waits
         * for the event to finish).
         */
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2

        private var injectInputEventMethod: Method? = null
        private var setDisplayIdMethod: Method? = null
        private var setActionButtonMethod: Method? = null
        private var addUniqueIdAssociationByPortMethod: Method? = null
        private var removeUniqueIdAssociationByPortMethod: Method? = null

        /**
         * 经 [FakeContext] 拿真实 InputManager 并包装
         *
         * FakeContext 以 shell 身份取系统服务，才能绕开应用进程的权限限制。
         *
         * Wraps the real InputManager obtained through [FakeContext].
         *
         * Fetching system services as the shell identity via FakeContext is what
         * bypasses the app-process permission limits.
         */
        fun create(): InputManager {
            val manager = FakeContext.get()
                .getSystemService(Context.INPUT_SERVICE) as SystemInputManager
            return InputManager(manager)
        }

        /**
         * 懒解析并缓存隐藏 `injectInputEvent` 的反射 Method
         *
         * Lazily resolves and caches the reflective hidden `injectInputEvent`
         * Method.
         */
        @Throws(NoSuchMethodException::class)
        private fun getInjectInputEventMethod(): Method {
            var method = injectInputEventMethod
            if (method == null) {
                method = SystemInputManager::class.java.getMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.java
                )
                injectInputEventMethod = method
            }
            return method
        }

        /**
         * 懒解析并缓存隐藏 `InputEvent.setDisplayId` 的反射 Method
         *
         * Lazily resolves and caches the reflective hidden
         * `InputEvent.setDisplayId` Method.
         */
        @Throws(NoSuchMethodException::class)
        private fun getSetDisplayIdMethod(): Method {
            var method = setDisplayIdMethod
            if (method == null) {
                method = InputEvent::class.java.getMethod("setDisplayId", Int::class.java)
                setDisplayIdMethod = method
            }
            return method
        }

        /**
         * 把事件路由到指定显示器（隐藏 `InputEvent.setDisplayId`）
         *
         * 不设置时事件落在默认显示器；失败返回 false。
         *
         * Routes the event to the given display (hidden
         * `InputEvent.setDisplayId`).
         *
         * Without it the event lands on the default display; returns false on
         * failure.
         */
        fun setDisplayId(inputEvent: InputEvent, displayId: Int): Boolean {
            return try {
                val method = getSetDisplayIdMethod()
                method.invoke(inputEvent, displayId)
                true
            } catch (e: ReflectiveOperationException) {
                Ln.e("Cannot associate a display id to the input event", e)
                false
            }
        }

        /**
         * 懒解析并缓存隐藏 `MotionEvent.setActionButton` 的反射 Method
         *
         * Lazily resolves and caches the reflective hidden
         * `MotionEvent.setActionButton` Method.
         */
        @Throws(NoSuchMethodException::class)
        private fun getSetActionButtonMethod(): Method {
            var method = setActionButtonMethod
            if (method == null) {
                method = MotionEvent::class.java.getMethod("setActionButton", Int::class.java)
                setActionButtonMethod = method
            }
            return method
        }

        /**
         * 设置 MotionEvent 的动作键（隐藏 `MotionEvent.setActionButton`）
         *
         * 鼠标侧键等场景用；失败返回 false。
         *
         * Sets the action button of a MotionEvent (hidden
         * `MotionEvent.setActionButton`).
         *
         * Used for mouse side buttons and the like; returns false on failure.
         */
        fun setActionButton(motionEvent: MotionEvent, actionButton: Int): Boolean {
            return try {
                val method = getSetActionButtonMethod()
                method.invoke(motionEvent, actionButton)
                true
            } catch (e: ReflectiveOperationException) {
                Ln.e("Cannot set action button on MotionEvent", e)
                false
            }
        }

        /**
         * 懒解析并缓存隐藏 `addUniqueIdAssociationByPort` 的反射 Method
         *
         * Lazily resolves and caches the reflective hidden
         * `addUniqueIdAssociationByPort` Method.
         */
        @Throws(NoSuchMethodException::class)
        private fun getAddUniqueIdAssociationByPortMethod(): Method {
            var method = addUniqueIdAssociationByPortMethod
            if (method == null) {
                method = SystemInputManager::class.java.getMethod(
                    "addUniqueIdAssociationByPort", String::class.java, String::class.java
                )
                addUniqueIdAssociationByPortMethod = method
            }
            return method
        }

        /**
         * 懒解析并缓存隐藏 `removeUniqueIdAssociationByPort` 的反射 Method
         *
         * Lazily resolves and caches the reflective hidden
         * `removeUniqueIdAssociationByPort` Method.
         */
        @Throws(NoSuchMethodException::class)
        private fun getRemoveUniqueIdAssociationByPortMethod(): Method {
            var method = removeUniqueIdAssociationByPortMethod
            if (method == null) {
                method = SystemInputManager::class.java.getMethod(
                    "removeUniqueIdAssociationByPort", String::class.java
                )
                removeUniqueIdAssociationByPortMethod = method
            }
            return method
        }
    }
}
