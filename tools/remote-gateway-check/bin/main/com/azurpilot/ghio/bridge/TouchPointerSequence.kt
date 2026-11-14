package com.azurpilot.ghio.bridge

/**
 * 由当前按下的 contact 集合，算出这一次 down/move/up 该发哪种 MotionEvent
 *
 * 纯函数、无状态，线程安全；action 取值与 `android.view.MotionEvent` 的
 * `ACTION_*` 常量同值（masked 形式，不含指针索引位）。输入端（native 桥）只上报
 * 单点事件，多指合成、越界兜底与状态失步自愈（[Kind.Down] 打在已存在的
 * contact 上时先 cancel 重建）都由 [plan] 推导。
 *
 * Derives which MotionEvent the next down/move/up should carry, given the set
 * of currently pressed contacts.
 *
 * Pure, stateless, and thread-safe; action values match the `ACTION_*`
 * constants of `android.view.MotionEvent` (masked form, without pointer index
 * bits). The native bridge only reports single-pointer events; multi-pointer
 * composition, out-of-range guards and desync self-healing (a [Kind.Down] on
 * an already-pressed contact rebuilds via cancel-first) are all derived by
 * [plan].
 */
object TouchPointerSequence {

    /**
     * 允许的最大触点数；contact 编号须落在 `0 until [MAX_CONTACTS]`，越界的
     * [plan] 直接返回失败
     *
     * Maximum number of simultaneous contacts; a contact index must fall in
     * `0 until [MAX_CONTACTS]`, and [plan] fails outright on out-of-range
     * values.
     */
    const val MAX_CONTACTS = 16

    /** 镜像 `MotionEvent.ACTION_DOWN` / Mirrors `MotionEvent.ACTION_DOWN`. */
    const val ACTION_DOWN = 0

    /** 镜像 `MotionEvent.ACTION_UP` / Mirrors `MotionEvent.ACTION_UP`. */
    const val ACTION_UP = 1

    /** 镜像 `MotionEvent.ACTION_MOVE` / Mirrors `MotionEvent.ACTION_MOVE`. */
    const val ACTION_MOVE = 2

    /** 镜像 `MotionEvent.ACTION_CANCEL` / Mirrors `MotionEvent.ACTION_CANCEL`. */
    const val ACTION_CANCEL = 3

    /** 镜像 `MotionEvent.ACTION_POINTER_DOWN` / Mirrors `MotionEvent.ACTION_POINTER_DOWN`. */
    const val ACTION_POINTER_DOWN = 5

    /** 镜像 `MotionEvent.ACTION_POINTER_UP` / Mirrors `MotionEvent.ACTION_POINTER_UP`. */
    const val ACTION_POINTER_UP = 6

    /**
     * 上报的触点操作类型
     *
     * The reported touch operation kind.
     */
    enum class Kind { Down, Move, Up }

    /**
     * 一个触点的当前状态
     *
     * The current state of one pointer.
     *
     * @property contact 触点编号 / pointer index
     * @property x 显示器像素坐标 / display-pixel coordinate
     * @property y 显示器像素坐标 / display-pixel coordinate
     */
    data class Pointer(
        val contact: Int,
        val x: Float,
        val y: Float,
    )

    /**
     * 一步注入计划：本次该发什么 action、触点集合变成什么样
     *
     * One planned injection step: which action to send and the resulting
     * pointer set.
     *
     * @property ok 计划是否可执行（false 表示本次上报非法，直接丢弃）/
     *   whether the step is executable (false means the report is invalid and
     *   is dropped)
     * @property actionMasked 本步的 masked action（`ACTION_*` 常量）/ the
     *   masked action of this step (an `ACTION_*` constant)
     * @property changingIndex POINTER_DOWN/POINTER_UP/MOVE 中发生变更的触点在
     *   [pointers] 里的索引 / index within [pointers] of the pointer changed
     *   by POINTER_DOWN/POINTER_UP/MOVE
     * @property pointers 本步执行后的完整触点列表 / the full pointer list
     *   after this step
     * @property cancelFirst 注入本步前是否先以 ACTION_CANCEL 取消旧手势 /
     *   whether to send a preceding ACTION_CANCEL for the old gesture before
     *   injecting this step
     */
    data class Step(
        val ok: Boolean,
        val actionMasked: Int = 0,
        val changingIndex: Int = 0,
        val pointers: List<Pointer> = emptyList(),
        val cancelFirst: Boolean = false,
    )

    /**
     * 依据当前触点状态推导下一步注入计划
     *
     * 规则：Down 打在已存在的 contact 上 → 视为状态失步，先 cancel 再以单指
     * ACTION_DOWN 重建；空集合上的 Down → 普通 ACTION_DOWN；已满
     * [MAX_CONTACTS] 时的 Down → 失败；其余 Down → ACTION_POINTER_DOWN。
     * Move/Up 打在未知 contact 上 → 失败；Up 抬起最后一个触点 → ACTION_UP，
     * 否则 ACTION_POINTER_UP。
     *
     * Derives the next injection step from the current pointer state.
     *
     * Rules: a Down on an existing contact is treated as a desync and rebuilt
     * as a fresh single-pointer ACTION_DOWN after a cancel-first; a Down on an
     * empty set is a plain ACTION_DOWN; a Down on a full [MAX_CONTACTS] set
     * fails; other Downs become ACTION_POINTER_DOWN. A Move/Up on an unknown
     * contact fails; an Up on the last pointer becomes ACTION_UP, otherwise
     * ACTION_POINTER_UP.
     *
     * @param kind 本次上报的操作类型 / the reported operation kind
     * @param current 当前按下的触点集合（原样传入，不会被修改）/ the currently
     *   pressed pointers (passed as-is, never mutated)
     * @param contact 触点编号 / pointer index
     * @param x 目标显示器像素坐标 / display-pixel coordinate
     * @param y 目标显示器像素坐标 / display-pixel coordinate
     * @return 本步计划；[Step.ok] 为 false 表示丢弃本次上报 / the planned
     *   step; a false [Step.ok] means the report is dropped
     */
    fun plan(
        kind: Kind,
        current: List<Pointer>,
        contact: Int,
        x: Float,
        y: Float,
    ): Step {
        if (contact !in 0..<MAX_CONTACTS) return Step(ok = false)
        val idx = current.indexOfFirst { it.contact == contact }
        val nextPointer = Pointer(contact, x, y)
        return when (kind) {
            Kind.Down -> when {
                idx >= 0 -> Step(
                    ok = true,
                    actionMasked = ACTION_DOWN,
                    pointers = listOf(nextPointer),
                    cancelFirst = true,
                )

                current.isEmpty() -> Step(
                    ok = true,
                    actionMasked = ACTION_DOWN,
                    pointers = listOf(nextPointer),
                )

                current.size >= MAX_CONTACTS -> Step(ok = false)
                else -> {
                    val next = current + nextPointer
                    Step(
                        ok = true,
                        actionMasked = ACTION_POINTER_DOWN,
                        changingIndex = next.lastIndex,
                        pointers = next,
                    )
                }
            }

            Kind.Move -> {
                if (idx < 0) return Step(ok = false)
                val next = current.toMutableList()
                next[idx] = nextPointer
                Step(ok = true, actionMasked = ACTION_MOVE, changingIndex = idx, pointers = next)
            }

            Kind.Up -> {
                if (idx < 0) return Step(ok = false)
                if (current.size == 1) {
                    Step(ok = true, actionMasked = ACTION_UP, pointers = current)
                } else {
                    Step(
                        ok = true,
                        actionMasked = ACTION_POINTER_UP,
                        changingIndex = idx,
                        pointers = current,
                    )
                }
            }
        }
    }
}
