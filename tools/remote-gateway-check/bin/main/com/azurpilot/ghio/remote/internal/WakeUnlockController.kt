package com.azurpilot.ghio.remote.internal

import android.os.SystemClock
import android.view.KeyEvent
import com.azurpilot.ghio.bridge.InputControlUtils
import com.azurpilot.ghio.constant.WakeUnlockResult
import com.azurpilot.ghio.third.Ln
import com.azurpilot.ghio.third.wrappers.ServiceManager

/**
 * 亮屏 / 解锁 / 上锁息屏；整段在特权进程内完成
 *
 * 从 参考实现 的同名 object 移植。**只支持纯数字 PIN**：图案与密码要模拟的输入面板差别太大，
 * 注入不出来
 *
 * 全程在特权进程里做而不是 app 侧分几步 IPC：息屏之后 app 侧的协程会被系统挂起，
 * 分步做会卡在中间
 *
 * 线程：跑在调用方的 binder 线程上，全程阻塞（轮询 + sleep 最长十几秒）。
 *
 * Wakes the screen, unlocks, and locks it back to sleep; runs entirely inside the privileged
 * process.
 *
 * Ported from the reference implementation's same-named object. **Numeric PIN only**: pattern
 * and password would require simulating very different input panels, which cannot be injected.
 *
 * Done in one privileged call instead of step-by-step IPC from the app side: after the screen
 * goes off the system may suspend the app-side coroutines, wedging the sequence halfway.
 *
 * Threading: runs on the caller's binder thread and blocks throughout (polling plus sleeps, up
 * to a dozen-plus seconds).
 */
object WakeUnlockController {

    private const val TAG = "WakeUnlock"

    private const val SCREEN_ON_TIMEOUT_MS = 5_000L
    private const val KEYGUARD_GONE_TIMEOUT_MS = 5_000L

    /** bouncer 弹出期间 isKeyguardLocked 仍为 true，先等它稳下来再注入 / While the bouncer is up isKeyguardLocked still reports true; wait for it to settle before injecting */
    private const val BOUNCER_SETTLE_MS = 1_200L

    /** 轮询步长 / Poll step */
    private const val POLL_INTERVAL_MS = 100L

    /** 数字键间隔，模拟人手输入节奏 / Gap between digit keys, mimicking human input pacing */
    private const val DIGIT_GAP_MS = 50L

    /** 上锁后等系统稳定再自测解锁 / Settle wait after locking before the self-test unlock */
    private const val LOCK_SETTLE_MS = 500L

    /** 等息屏完成的窗口 / Window waited for the screen-off to complete */
    private const val SCREEN_OFF_TIMEOUT_MS = 3_000L

    /**
     * 设置页自测：先上锁息屏，等系统稳定再解一次，让用户当场看到 PIN 对不对
     *
     * Settings-page self-test: locks and sleeps first, waits for the system to settle, then
     * unlocks once so the user sees immediately whether the PIN is right.
     */
    fun testUnlock(credential: String): Int {
        val lockCode = lockAndSleep()
        if (lockCode != WakeUnlockResult.OK) return lockCode
        Ln.i("$TAG: locked for test, settle ${LOCK_SETTLE_MS}ms")
        Thread.sleep(LOCK_SETTLE_MS)
        return unlock(credential)
    }

    /**
     * 上锁并息屏
     *
     * lockNow 后 keyguard 迟迟不出现且非 secure → 视为没设锁屏（NO_KEYGUARD）；
     * goToSleep 不可用不视为失败（keyguard 已锁即达标）。
     *
     * Locks the keyguard and puts the screen to sleep.
     *
     * If the keyguard never appears after lockNow and the lock is not secure, no lock screen is
     * configured (NO_KEYGUARD); an unavailable goToSleep is not a failure (a locked keyguard
     * already meets the goal).
     *
     * @return [WakeUnlockResult] 码 / a [WakeUnlockResult] code
     */
    fun lockAndSleep(): Int {
        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        if (!wm.lockNow()) {
            Ln.w("$TAG: lockNow unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        if (!pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked() == true }) {
            // 锁屏方式设为「无」时 lockNow 之后 keyguard 永远不出现；滑动与密码锁屏都会出现。
            // 超时且非 secure 即视为没设锁屏，这种情况也不必验证息屏
            if (wm.isKeyguardSecure(0) != true) {
                Ln.i("$TAG: keyguard never appeared and not secure — no lock screen configured")
                return WakeUnlockResult.NO_KEYGUARD
            }
            Ln.w("$TAG: keyguard did not lock after lockNow")
            return WakeUnlockResult.LOCK_FAILED
        }

        if (!pm.goToSleep()) {
            Ln.w("$TAG: goToSleep unavailable (keyguard already locked)")
            return WakeUnlockResult.OK
        }
        pollUntil(SCREEN_OFF_TIMEOUT_MS) { !pm.isScreenOn(0) }
        Ln.i("$TAG: screen locked and off")
        return WakeUnlockResult.OK
    }

    /**
     * 亮屏并解锁；[credential] 是纯数字 PIN，无凭证锁屏传空串
     *
     * 流程：唤醒 → 等 keyguard 状态 → dismissKeyguard → secure 时注入 PIN + ENTER。
     * PIN 输错**不重试**：连续错会触发系统锁定冷却，越试越进不去。
     *
     * Wakes and unlocks; [credential] is a numeric PIN, empty for a credential-less lock
     * screen.
     *
     * Flow: wake → keyguard state poll → dismissKeyguard → PIN + ENTER injection when secure.
     * A wrong PIN is **never retried**: repeated failures trigger the system's lockout
     * cooldown, making things worse with every attempt.
     *
     * @return [WakeUnlockResult] 码 / a [WakeUnlockResult] code
     */
    fun unlock(credential: String): Int {
        val pm = ServiceManager.getPowerManager()
        val wm = ServiceManager.getWindowManager()

        if (!pm.isScreenOn(0)) {
            if (!pm.wakeUp()) {
                Ln.w("$TAG: wakeUp() unavailable on this ROM")
                return WakeUnlockResult.UNSUPPORTED
            }
            if (!pollUntil(SCREEN_ON_TIMEOUT_MS) { pm.isScreenOn(0) }) {
                Ln.w("$TAG: screen did not turn on within ${SCREEN_ON_TIMEOUT_MS}ms")
                return WakeUnlockResult.WAKE_FAILED
            }
        }

        val locked = wm.isKeyguardLocked()
        if (locked == null) {
            Ln.w("$TAG: isKeyguardLocked unavailable")
            return WakeUnlockResult.UNSUPPORTED
        }
        if (!locked) {
            Ln.i("$TAG: keyguard not showing, nothing to dismiss")
            return WakeUnlockResult.OK
        }

        val secure = wm.isKeyguardSecure(0) ?: false
        Ln.i("$TAG: keyguard locked, secure=$secure")

        if (!wm.dismissKeyguard()) {
            Ln.w("$TAG: dismissKeyguard unavailable on this ROM")
            return WakeUnlockResult.UNSUPPORTED
        }

        if (!secure) {
            return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked() == false }) {
                Ln.i("$TAG: unlocked (insecure keyguard)")
                WakeUnlockResult.OK
            } else {
                Ln.w("$TAG: insecure keyguard did not dismiss")
                WakeUnlockResult.CREDENTIAL_REJECTED
            }
        }

        if (credential.isEmpty()) {
            Ln.w("$TAG: secure keyguard but no credential configured")
            return WakeUnlockResult.CREDENTIAL_REQUIRED
        }
        if (credential.any { !it.isDigit() }) {
            Ln.w("$TAG: only numeric PIN is supported")
            return WakeUnlockResult.CREDENTIAL_REQUIRED
        }

        Thread.sleep(BOUNCER_SETTLE_MS)
        Ln.i("$TAG: injecting ${credential.length} PIN digits after ${BOUNCER_SETTLE_MS}ms settle")

        for (c in credential) {
            val keyCode = KeyEvent.KEYCODE_0 + (c - '0')
            InputControlUtils.keyDown(keyCode, 0)
            InputControlUtils.keyUp(keyCode, 0)
            Thread.sleep(DIGIT_GAP_MS)
        }
        // 部分 ROM 输满自动提交，其余要确认；补一个 ENTER 两边都兼容
        InputControlUtils.keyDown(KeyEvent.KEYCODE_ENTER, 0)
        InputControlUtils.keyUp(KeyEvent.KEYCODE_ENTER, 0)

        return if (pollUntil(KEYGUARD_GONE_TIMEOUT_MS) { wm.isKeyguardLocked() == false }) {
            Ln.i("$TAG: unlocked (PIN accepted)")
            WakeUnlockResult.OK
        } else {
            // 不重试：连续输错会触发系统的锁定冷却，越试越进不去
            Ln.w("$TAG: still locked after PIN injection — wrong PIN, or keyguard ignores injected keys")
            WakeUnlockResult.CREDENTIAL_REJECTED
        }
    }

    /** 以 100ms 步进轮询到超时，最后一拍再判一次 / Polls in 100 ms steps until the deadline, with one final check */
    private inline fun pollUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cond()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return cond()
    }
}
