package com.azurpilot.ghio.constant

/**
 * 收口 [com.azurpilot.ghio.remote.internal.WakeUnlockController] 的返回码
 *
 * 用 int 而不是枚举：要跨 AIDL，且值直接进 `RemoteService.unlock` 的返回位。
 *
 * Return codes of [com.azurpilot.ghio.remote.internal.WakeUnlockController].
 *
 * Plain ints instead of an enum: the values cross AIDL and land directly in
 * the `RemoteService.unlock` return slot.
 */
object WakeUnlockResult {
    /** 成功（亮屏 / 解锁 / 上锁视调用而定）/ Success (wake, unlock, or lock, per the call). */
    const val OK = 0

    /** wakeUp 后屏幕未在超时内点亮 / The screen did not light up within the timeout after wakeUp. */
    const val WAKE_FAILED = 1

    /** 锁屏需要凭据但拿不到（未配置或非纯数字 PIN）/ The lock needs a credential but none is usable (unset, or not a numeric PIN). */
    const val CREDENTIAL_REQUIRED = 2

    /** 凭据被拒：注入后 keyguard 仍未解除，且不重试 / Credential rejected: the keyguard stayed up after injection, with no retry. */
    const val CREDENTIAL_REJECTED = 3

    /** keyguard 未显示（未设锁屏或本就解锁），不必解 / The keyguard is not showing (no lock set, or already unlocked); nothing to dismiss. */
    const val NO_KEYGUARD = 4

    /** 该 ROM 上对应的隐藏 API 不可用 / The relevant hidden API is unavailable on this ROM. */
    const val UNSUPPORTED = 5

    /** 主动上锁失败 / Re-locking the screen failed. */
    const val LOCK_FAILED = 6
}
