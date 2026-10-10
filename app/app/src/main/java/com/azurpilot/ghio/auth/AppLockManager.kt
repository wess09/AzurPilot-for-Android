package com.azurpilot.ghio.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.azurpilot.ghio.settings.AppSettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 应用锁管理器：判定是否需要锁屏验证，并维护全局已解锁状态
 *
 * 契约：
 * 1. 仅当用户开启应用锁**且**设备配置了系统锁屏（PIN / 图案 / 密码）才需要验证；
 *    设备没设锁屏时自动免密放行——无凭据可验，强上验证只会把用户锁在门外；
 * 2. 已解锁状态只存于进程内存：应用退到后台（ON_STOP）或熄屏时由持有方调
 *    [lock] 复位，本类不自己监听生命周期；
 * 3. [authenticate] 走系统 BiometricPrompt（BIOMETRIC_STRONG + DEVICE_CREDENTIAL），
 *    回调经主线程 executor 派发。
 *
 * The app-lock manager: decides whether lock-screen verification is required
 * and owns the global unlocked state.
 *
 * Contract:
 * 1. Verification is required only when app lock is enabled in settings AND
 *    the device has a system screen lock (PIN/pattern/password); with no lock
 *    configured the app is bypassed automatically — there is no credential to
 *    check, and enforcing it anyway would lock the user out for good.
 * 2. The unlocked state lives only in process memory: the owner calls [lock]
 *    when the app backgrounds (ON_STOP) or the screen turns off; this class
 *    does not observe the lifecycle itself.
 * 3. [authenticate] drives the system BiometricPrompt (BIOMETRIC_STRONG +
 *    DEVICE_CREDENTIAL); callbacks are dispatched on the main-thread executor.
 */
class AppLockManager(
    private val appSettings: AppSettingsManager,
) {

    private val _isUnlocked = MutableStateFlow(false)

    /**
     * 当前是否已解锁；状态仅存于进程内存，进程死亡即回锁定
     * Whether the app is currently unlocked; in-process memory only, back to
     * locked on process death.
     */
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /**
     * Check that the system can currently present an authenticator accepted by
     * our prompt. KeyguardManager.isDeviceSecure is insufficient on cloud ROMs
     * with incomplete GateKeeper implementations (issue #11).
     */
    fun canAuthenticate(context: Context): Boolean {
        val result = runCatching {
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
        }.onFailure { Timber.w(it, "App lock capability check failed") }.getOrNull()
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * 判断是否需要应用锁验证：设置开关开启且设备设了系统锁屏；未设锁屏免密放行
     * Whether app-lock verification is required: enabled in settings AND the
     * device is secured; bypassed when no screen lock is configured.
     */
    fun isLockRequired(context: Context): Boolean {
        return appSettings.appLockEnabled.value && canAuthenticate(context)
    }

    /**
     * 标记为已解锁 / Marks the app as unlocked.
     */
    fun markUnlocked() {
        _isUnlocked.value = true
    }

    /**
     * 复位为锁定；应用退后台或熄屏时由持有方调用
     * Resets to locked; called by the owner when the app backgrounds or the
     * screen turns off.
     */
    fun lock() {
        _isUnlocked.value = false
    }

    /**
     * 发起系统原生验证（生物识别 + 系统 PIN / 图案 / 密码）
     *
     * 无需验证时直接标记解锁并回调成功，不弹系统界面。错误分流：
     * 用户取消（ERROR_USER_CANCELED / ERROR_NEGATIVE_BUTTON / ERROR_CANCELED）
     * 走 [onCancel]；其余错误优先走 [onError]，未提供时退回 [onCancel]。
     * 单次识别失败（指纹不对）不回调任何一方，系统界面保持等待重试；
     * Activity 已 finishing / destroyed 时静默返回。
     *
     * Prompts native authentication (biometrics + device PIN/pattern/password).
     *
     * When verification is not required, marks unlocked and invokes [onSuccess]
     * without any system UI. Errors split two ways: user cancellation
     * (ERROR_USER_CANCELED / ERROR_NEGATIVE_BUTTON / ERROR_CANCELED) goes to
     * [onCancel]; any other error prefers [onError], falling back to [onCancel]
     * when it is absent. A single mismatched scan keeps the system UI up for a
     * retry and invokes neither side; a finishing/destroyed [activity] returns
     * silently.
     *
     * @param activity 承载 BiometricPrompt 的 Activity / the Activity hosting
     *   the prompt
     * @param title 系统验证界面标题 / system prompt title
     * @param subtitle 系统验证界面副标题 / system prompt subtitle
     * @param onSuccess 验证成功（或免密放行）后的回调 / invoked after success
     *   (or when no lock is required)
     * @param onError 非取消失败的回调，携带系统错误码与文案；可空 / invoked on
     *   non-cancellation failures with the system code and message; nullable
     * @param onCancel 用户取消的回调；可空 / invoked when the user cancels;
     *   nullable
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: ((errorCode: Int, errString: CharSequence) -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
    ) {
        if (!isLockRequired(activity)) {
            markUnlocked()
            onSuccess()
            return
        }

        if (activity.isFinishing || activity.isDestroyed) {
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    markUnlocked()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                        // Some vendor ROMs report canAuthenticate() == SUCCESS
                        // but have no usable GateKeeper credential at prompt time.
                        // This specific error is unrecoverable by retrying.
                        Timber.w("App lock: no device credential; unlocking to avoid permanent lockout")
                        markUnlocked()
                        onSuccess()
                    } else if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onCancel?.invoke()
                    } else {
                        Timber.w("BiometricPrompt error code=$errorCode message=$errString")
                        onError?.invoke(errorCode, errString) ?: onCancel?.invoke()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // 单次指纹识别未通过，系统界面保持并等待重试，不中断流程
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                AUTHENTICATORS
            )
            .build()

        try {
            prompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch BiometricPrompt")
            onError?.invoke(-1, e.message.orEmpty()) ?: onCancel?.invoke()
        }
    }

    private companion object {
        val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
