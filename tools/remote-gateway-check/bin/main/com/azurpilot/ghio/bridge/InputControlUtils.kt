package com.azurpilot.ghio.bridge

import android.os.RemoteException
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

import com.azurpilot.ghio.ITouchEventCallback
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.InputManager
import com.azurpilot.ghio.third.wrappers.ServiceManager

/**
 * 触摸/按键注入的装配层：把 down/move/up 事件流组装成 MotionEvent 后经隐藏
 * `injectInputEvent` 注入
 *
 * 维护当前触点集合 `slots` 与手势起始时间 `gestureDownTime`，每一步的目标状态由
 * [TouchPointerSequence.plan] 纯函数推导；注入经 [InputManager]（shell 身份）。
 * 所有 down/move/up 入口以本 object 为锁串行化，保证共享多触点状态一致。调用方可来自
 * 特权进程内的 Binder、桥客户端或 JNI 路径，因此不同手势不能交错；每个调用方必须以连贯的
 * down/move/up 序列使用同一逻辑手势。按键入口不共享该手势锁。
 *
 * Assembles touch/key injection: builds MotionEvents from the down/move/up
 * stream and feeds them to the hidden `injectInputEvent`.
 *
 * Keeps the current pointer set `slots` and the gesture down time
 * `gestureDownTime`; the target state of each step is derived by the pure
 * [TouchPointerSequence.plan]. Injection goes through [InputManager] (shell
 * identity). All down/move/up entries synchronize on this object to preserve
 * shared multi-pointer state. Callers may be Binder, bridge-client, or JNI
 * paths in the privileged process, so distinct gestures cannot interleave;
 * each caller must use a coherent down/move/up sequence for one logical
 * gesture. Key-entry methods do not share this gesture lock.
 */
object InputControlUtils {

    private const val TAG = "InputControlUtils"

    /**
     * 文本序列预算，限制长码逐键分发的总等待；失败后仍释放已按下的键。
     *
     * Budget for dispatching a text sequence; pressed keys are released even on failure.
     */
    private const val TEXT_INPUT_BUDGET_MS = 10_000L

    /** 懒初始化的 [InputManager] 包装；非空后不再重建 / Lazily created [InputManager] wrapper; never rebuilt once non-null. */
    private var manager: InputManager? = null

    /** 已注册的触摸回调，[Volatile] 保证跨线程可见 / Registered touch callback; [Volatile] for cross-thread visibility. */
    @Volatile
    private var touchCallback: ITouchEventCallback? = null

    private fun getManager(): InputManager {
        if (manager == null) {
            manager = ServiceManager.getInputManager()
        }
        return manager!!
    }

    private const val DEFAULT_DEVICE_ID = 0
    private val DEFAULT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN

    /** 进行中的多触点状态，仅在 [Synchronized] 入口内变更 / In-progress multi-pointer state, mutated only inside the [Synchronized] entries. */
    private val slots = ArrayList<TouchPointerSequence.Pointer>()

    /** 本次手势的按下时间（uptime ms）；0 表示无进行中手势 / Down time of the current gesture (uptime ms); 0 means no gesture in progress. */
    private var gestureDownTime = 0L

    /**
     * 按当前触点集合构造多指 MotionEvent
     *
     * 坐标钳到非负；压力/尺寸恒 1（注入端只关心位置与触点数）。
     *
     * Builds a multi-pointer MotionEvent from the current pointer set.
     *
     * Coordinates are clamped to non-negative; pressure and size stay at 1
     * (injection only cares about positions and pointer count).
     */
    private fun obtainFromSlots(downTime: Long, eventTime: Long, action: Int): MotionEvent {
        val n = slots.size
        val props = arrayOfNulls<MotionEvent.PointerProperties>(n)
        val coords = arrayOfNulls<MotionEvent.PointerCoords>(n)
        for ((i, p) in slots.withIndex()) {
            val prop = MotionEvent.PointerProperties()
            prop.id = p.contact
            prop.toolType = MotionEvent.TOOL_TYPE_FINGER
            props[i] = prop
            val coord = MotionEvent.PointerCoords()
            coord.x = maxOf(0f, p.x)
            coord.y = maxOf(0f, p.y)
            coord.pressure = 1.0f
            coord.size = 1.0f
            coords[i] = coord
        }
        return MotionEvent.obtain(
            downTime, eventTime, action,
            n, props, coords,
            0, 0,
            1.0f, 1.0f,
            DEFAULT_DEVICE_ID, 0, DEFAULT_SOURCE, 0
        )
    }

    /**
     * 绑定目标显示器 → 通知回调 → 注入，finally 里回收事件
     *
     * Binds the target display, notifies the callback, injects, and recycles
     * the event in a finally block.
     */
    private fun injectInputEvent(event: MotionEvent, displayId: Int, mode: Int): Boolean {
        try {
            if (!setDisplayId(event, displayId)) {
                return false
            }
            notifyTouchCallback(event)
            return getManager().injectInputEvent(event, mode)
        } finally {
            event.recycle()
        }
    }

    /**
     * 注册/注销触摸事件回调
     *
     * 每个注入的触摸事件都会把 (x, y, actionMasked) 回报给 [callback]；传 null
     * 注销。回调抛出 RemoteException 或 RuntimeException 时视为对端失效，自动
     * 注销，避免注入链路被反复拖垮。
     *
     * Registers or unregisters the touch event callback.
     *
     * Every injected touch event reports (x, y, actionMasked) to [callback];
     * pass null to unregister. A RemoteException or RuntimeException from the
     * callback marks the peer as dead and unregisters it automatically, so the
     * injection chain is not dragged down repeatedly.
     */
    fun setTouchCallback(callback: ITouchEventCallback?) {
        touchCallback = callback
    }

    /**
     * 把触摸事件回报给已注册回调
     *
     * Reports the touch event to the registered callback.
     */
    private fun notifyTouchCallback(event: MotionEvent) {
        val callback = touchCallback ?: return
        try {
            callback.onCallback(Math.round(event.x), Math.round(event.y), event.actionMasked)
        } catch (e: Exception) {
            if (e is RemoteException || e is RuntimeException) {
                touchCallback = null
                Ln.w(TAG + ": touch callback failed, clearing registration", e)
            } else {
                throw e
            }
        }
    }

    /**
     * displayId 为 0 时免绑定（默认屏），否则走隐藏 `InputEvent.setDisplayId`
     *
     * Skips binding for display 0 (the default), otherwise goes through the
     * hidden `InputEvent.setDisplayId`.
     */
    private fun setDisplayId(event: InputEvent, displayId: Int): Boolean {
        return displayId == 0 || InputManager.setDisplayId(event, displayId)
    }

    /**
     * POINTER_DOWN/UP 需要把变更触点索引编码进 action 高位，其余原样返回
     *
     * Encodes the changing pointer index into the action's high bits for
     * POINTER_DOWN/UP; other actions pass through unchanged.
     */
    private fun encodeAction(masked: Int, index: Int): Int {
        if (masked == TouchPointerSequence.ACTION_POINTER_DOWN ||
            masked == TouchPointerSequence.ACTION_POINTER_UP
        ) {
            return masked or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        return masked
    }

    private fun replaceSlots(next: List<TouchPointerSequence.Pointer>) {
        slots.clear()
        slots.addAll(next)
    }

    /**
     * 对进行中的手势补发 ACTION_CANCEL 并清空状态；无手势时仅清状态
     *
     * Sends a trailing ACTION_CANCEL for the in-progress gesture and clears
     * the state; with no gesture in progress it only clears.
     */
    private fun injectCancel(displayId: Int) {
        if (slots.isEmpty() || gestureDownTime == 0L) {
            slots.clear()
            gestureDownTime = 0
            return
        }
        val cancel = obtainFromSlots(
            gestureDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL
        )
        injectInputEvent(cancel, displayId, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        slots.clear()
        gestureDownTime = 0
    }

    /**
     * 执行一个 [TouchPointerSequence.Step]
     *
     * 必要时先取消旧手势（[Step.cancelFirst]），替换触点集合并注入；DOWN/
     * POINTER_DOWN 用 WAIT_FOR_FINISH 让系统先把触点落下，避免后续 MOVE 落空；
     * UP/POINTER_UP 之后收缩触点集合。
     *
     * Applies one [TouchPointerSequence.Step].
     *
     * Cancels the old gesture first when required ([Step.cancelFirst]), then
     * replaces the pointer set and injects; DOWN/POINTER_DOWN use
     * WAIT_FOR_FINISH so the system lands the pointer before later MOVE events,
     * and UP/POINTER_UP shrink the pointer set afterwards.
     */
    private fun injectStep(step: TouchPointerSequence.Step, displayId: Int): Boolean {
        if (!step.ok) {
            return false
        }
        if (step.cancelFirst) {
            injectCancel(displayId)
        }
        replaceSlots(step.pointers)
        if (slots.isEmpty()) {
            return false
        }
        if (gestureDownTime == 0L) {
            gestureDownTime = SystemClock.uptimeMillis()
        }
        val action = encodeAction(step.actionMasked, step.changingIndex)
        val wait = step.actionMasked == TouchPointerSequence.ACTION_DOWN ||
            step.actionMasked == TouchPointerSequence.ACTION_POINTER_DOWN
        val mode = if (wait) {
            InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        } else {
            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        }
        val result = injectInputEvent(
            obtainFromSlots(gestureDownTime, SystemClock.uptimeMillis(), action),
            displayId,
            mode
        )
        if (step.actionMasked == TouchPointerSequence.ACTION_UP) {
            slots.clear()
            gestureDownTime = 0
        } else if (step.actionMasked == TouchPointerSequence.ACTION_POINTER_UP) {
            val idx = step.changingIndex
            if (idx >= 0 && idx < slots.size) {
                slots.removeAt(idx)
            }
        }
        return result
    }

    /**
     * 注入一次触摸按下
     *
     * [x] / [y] 为目标显示器像素坐标，[contact] 为触点编号，[displayId] 为
     * 目标显示器（0 = 默认屏）。
     *
     * Injects one touch down.
     *
     * [x] / [y] are display-pixel coordinates on the target display, [contact]
     * the pointer index and [displayId] the target display (0 = default).
     *
     * @return 注入是否成功 / whether the injection succeeded
     */
    @Synchronized
    fun down(x: Int, y: Int, contact: Int, displayId: Int): Boolean {
        return injectStep(
            TouchPointerSequence.plan(
                TouchPointerSequence.Kind.Down,
                ArrayList(slots),
                contact,
                x.toFloat(),
                y.toFloat()
            ),
            displayId
        )
    }

    /**
     * 注入一次触摸移动；契约同 [down]
     *
     * Injects one touch move; same contract as [down].
     *
     * @return 注入是否成功 / whether the injection succeeded
     */
    @Synchronized
    fun move(x: Int, y: Int, contact: Int, displayId: Int): Boolean {
        return injectStep(
            TouchPointerSequence.plan(
                TouchPointerSequence.Kind.Move,
                ArrayList(slots),
                contact,
                x.toFloat(),
                y.toFloat()
            ),
            displayId
        )
    }

    /**
     * 注入一次触摸抬起；契约同 [down]
     *
     * Injects one touch up; same contract as [down].
     *
     * @return 注入是否成功 / whether the injection succeeded
     */
    @Synchronized
    fun up(x: Int, y: Int, contact: Int, displayId: Int): Boolean {
        return injectStep(
            TouchPointerSequence.plan(
                TouchPointerSequence.Kind.Up,
                ArrayList(slots),
                contact,
                x.toFloat(),
                y.toFloat()
            ),
            displayId
        )
    }

    /**
     * 用按下后取消的序列请求副屏窗口焦点；有进行中的手势时返回 false。
     *
     * Android 11 的部分 ROM 不会给已恢复的副屏 Activity 分发窗口焦点，Unity 因此停在
     * 启动画面。此序列只走窗口聚焦路径，不发送抬起事件；在特权工作线程持手势锁执行。
     *
     * Requests secondary-window focus with a down/cancel sequence; returns false during a gesture.
     *
     * Some Android 11 ROMs omit window focus for a resumed secondary Activity, leaving Unity on
     * its startup screen. This sequence requests focus without an up event. It runs on privileged
     * worker threads under the gesture lock.
     */
    @Synchronized
    fun requestFocus(displayId: Int): Boolean {
        if (displayId <= 0 || slots.isNotEmpty()) return false
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int): MotionEvent =
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1f, 1f, 0).apply {
                source = DEFAULT_SOURCE
            }
        var cancelled = false
        val accepted = try {
            injectInputEvent(
                event(MotionEvent.ACTION_DOWN), displayId,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
            )
        } finally {
            // 即使按下失败也取消，避免窗口就绪的竞态留下未释放触点。
            cancelled = injectInputEvent(
                event(MotionEvent.ACTION_CANCEL), displayId,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
            )
        }
        return accepted && cancelled
    }

    /**
     * 注入按键按下
     *
     * 等待分发完成（WAIT_FOR_FINISH），保证随后的 keyUp 不会超前于 keyDown。
     *
     * Injects a key down.
     *
     * Waits for the dispatch to finish (WAIT_FOR_FINISH) so a following keyUp
     * cannot overtake it.
     *
     * @return 注入是否成功 / whether the injection succeeded
     */
    fun keyDown(keyCode: Int, displayId: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val keyEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)

        if (!setDisplayId(keyEvent, displayId)) {
            return false
        }
        return getManager().injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)
    }

    /**
     * 注入按键抬起；异步注入（不等待分发结果）
     *
     * Injects a key up; asynchronous (does not wait for the dispatch result).
     *
     * @return 注入是否成功 / whether the injection succeeded
     */
    fun keyUp(keyCode: Int, displayId: Int): Boolean {
        val upTime = SystemClock.uptimeMillis()
        val keyEvent = KeyEvent(upTime, upTime, KeyEvent.ACTION_UP, keyCode, 0)

        if (!setDisplayId(keyEvent, displayId)) {
            return false
        }

        return getManager().injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    /**
     * 在特权桥线程替换目标显示器已聚焦输入框的 ASCII 文本，然后发送 Enter。
     *
     * Ctrl+A / Delete 清除整个旧值，不依赖旧码长度。每个事件等待分发完成；失败或
     * 超过序列预算时返回 false，并补发抬键以免 Shift 等修饰键残留。调用方须串行化
     * 整段输入，并限制文本长度；本方法不负责聚焦输入框或确认游戏中的预览结果。
     *
     * Replaces ASCII text in the focused field on the target display from a bridge thread,
     * then sends Enter. Ctrl+A / Delete clears the entire old value regardless of length.
     * Each event waits for dispatch; failure or an expired sequence budget returns false and
     * releases pressed keys. The caller must serialize the sequence and bound text length.
     * This method neither focuses the field nor confirms the in-game preview.
     */
    fun replaceAsciiText(text: String, displayId: Int): Boolean {
        require(text.all { it in ' '..'~' }) { "Only printable ASCII text is supported" }
        val characters = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            .getEvents(text.toCharArray()) ?: return false

        fun keyPair(keyCode: Int, metaState: Int = 0): List<KeyEvent> {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            return listOf(down, KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        }

        val events = keyPair(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON) +
            keyPair(KeyEvent.KEYCODE_DEL) + characters + keyPair(KeyEvent.KEYCODE_ENTER)
        val pressed = linkedMapOf<Int, KeyEvent>()
        val deadline = SystemClock.uptimeMillis() + TEXT_INPUT_BUDGET_MS
        try {
            for (event in events) {
                if (SystemClock.uptimeMillis() >= deadline) return false
                val timed = KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), 0)
                if (!setDisplayId(timed, displayId)) return false
                if (timed.action == KeyEvent.ACTION_DOWN) pressed[timed.keyCode] = timed
                if (!getManager().injectInputEvent(timed, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH)) {
                    return false
                }
                if (timed.action == KeyEvent.ACTION_UP) pressed.remove(timed.keyCode)
            }
            return true
        } finally {
            // 字符映射会为 +、大写字母等按下 Shift；中途失败不能把修饰键留给后续操作。
            for (down in pressed.values.toList().asReversed()) {
                runCatching {
                    val up = KeyEvent.changeTimeRepeat(
                        KeyEvent.changeAction(down, KeyEvent.ACTION_UP), SystemClock.uptimeMillis(), 0
                    )
                    if (setDisplayId(up, displayId)) {
                        getManager().injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
                    }
                }.onFailure { Ln.w("$TAG: text key release failed", it) }
            }
        }
    }
}
