package com.azurpilot.ghio.privileged

import android.content.Context
import com.azurpilot.ghio.R
import com.azurpilot.ghio.i18n.UiText
import com.azurpilot.ghio.i18n.uiTextFormatted
import com.azurpilot.ghio.i18n.uiTextOf
import com.azurpilot.ghio.util.ScreenSize
import kotlinx.coroutines.withContext
import com.azurpilot.ghio.AppDispatchers
import timber.log.Timber

/**
 * 改写与撤回物理主屏分辨率，并判定当前比例是否满足前台模式
 *
 * 抽接口只为可测：单测里换成记录调用的替身，不去碰 binder 与 WindowManager。
 * 生产实现是 [DisplaySizeController]，由 Koin 显式注入，不做默认参数。
 *
 * 与后台虚拟屏无关——那边是自己建的屏，尺寸由外壳指定（`DefaultDisplayConfig`）；
 * 这里动的是物理主屏，改完整个系统的 UI 都会重排。
 *
 * Rewrites and reverts the physical primary display resolution, and decides
 * whether the current aspect ratio fits foreground mode.
 *
 * The interface exists for testability: unit tests substitute a recording fake
 * instead of touching binder or WindowManager. The production implementation is
 * [DisplaySizeController], injected explicitly by Koin with no default
 * parameters.
 *
 * Unrelated to the background virtual display — that screen is created by the
 * shell itself and sized via `DefaultDisplayConfig`; this gateway drives the
 * physical primary screen, and changing it reflows the whole system UI.
 */
interface DisplaySizeGateway {

    /**
     * 把主屏改成物理尺寸内能放下的最大 16:9
     *
     * Forces the primary display to the largest 16:9 size that fits within its
     * physical dimensions.
     */
    suspend fun applyFit16x9(): DisplaySizeResult

    /**
     * 撤回出厂分辨率
     *
     * Reverts the primary display to its factory resolution.
     */
    suspend fun reset(): DisplaySizeResult

    /**
     * 当前生效的主屏比例是否满足前台模式
     *
     * Whether the primary display's current aspect ratio satisfies foreground
     * mode.
     */
    fun isAspectSupported(): Boolean
}

/**
 * 主屏分辨率操作的结局；文案由 UI 层挑，这里只给分类
 *
 * The outcome of a display-resolution operation; the UI layer picks the copy,
 * this only classifies it.
 */
sealed interface DisplaySizeResult {

    /** 已改到 [width] x [height] / applied at [width] x [height] */
    data class Applied(val width: Int, val height: Int) : DisplaySizeResult

    /** 已撤回出厂分辨率 / reverted to the factory resolution */
    data object Cleared : DisplaySizeResult

    /**
     * 特权进程没连上；文案由 UI 挑，这里只给分类
     *
     * The privileged process is not connected; the UI picks the copy, this
     * only classifies.
     */
    data object ServiceUnavailable : DisplaySizeResult

    /**
     * 失败；[reason] 是可直接展示的本地化文案
     *
     * Failed; [reason] carries display-ready localized copy.
     */
    data class Failed(val reason: UiText) : DisplaySizeResult
}

/**
 * [DisplaySizeGateway] 的生产实现：改与撤经 [PrivilegedServicePort] 走特权进程，
 * 比例判定在 app 侧本地完成
 *
 * 目标尺寸由 [ScreenSize] 在 app 侧算好，特权调用只负责落到 WindowManager；
 * 挂起方法内部切到 IO dispatcher，bind 异常一律折算成
 * [DisplaySizeResult.ServiceUnavailable]。
 *
 * Production implementation of [DisplaySizeGateway]: apply and reset go through
 * the privileged process via [PrivilegedServicePort], while aspect checks run
 * locally in the app process.
 *
 * The target size is computed app-side by [ScreenSize]; the privileged call
 * only lands it in WindowManager. Suspending methods shift to the IO dispatcher
 * internally, and any bind failure folds into
 * [DisplaySizeResult.ServiceUnavailable].
 */
class DisplaySizeController(
    private val context: Context,
    private val servicePort: PrivilegedServicePort,
) : DisplaySizeGateway {

    override suspend fun applyFit16x9(): DisplaySizeResult {
        // 按物理尺寸算而不是当前尺寸：拿改过的值再算一次，连点两下会一路缩下去
        val (physicalWidth, physicalHeight) = ScreenSize.physical(context)
        val target = ScreenSize.fit16x9(physicalWidth, physicalHeight)
            ?: return DisplaySizeResult.Failed(
                uiTextOf(
                    R.string.foreground_resolution_too_small,
                    uiTextFormatted("${physicalWidth}x$physicalHeight"),
                ),
            )
        val (width, height) = target
        return call("setForcedDisplaySize") { it.setForcedDisplaySize(width, height) }
            ?.let { ok ->
                if (ok) DisplaySizeResult.Applied(width, height)
                else DisplaySizeResult.Failed(uiTextOf(R.string.foreground_resolution_apply_failed))
            }
            ?: DisplaySizeResult.ServiceUnavailable
    }

    override suspend fun reset(): DisplaySizeResult =
        call("clearForcedDisplaySize") { it.clearForcedDisplaySize() }
            ?.let { ok ->
                if (ok) DisplaySizeResult.Cleared
                else DisplaySizeResult.Failed(uiTextOf(R.string.foreground_resolution_reset_failed))
            }
            ?: DisplaySizeResult.ServiceUnavailable

    override fun isAspectSupported(): Boolean {
        val (width, height) = ScreenSize.current(context)
        return ScreenSize.isAspect16x9(width, height).also {
            if (!it) Timber.w("primary display %dx%d is not 16:9", width, height)
        }
    }

    /**
     * 走 `useService` 而不是 `serviceOrNull`：这两个动作是用户主动点的，没连上时
     * 顺带发起授权与重绑正是他要的。返回 null = 服务面拿不到
     *
     * Uses `useService` rather than `serviceOrNull`: both actions are
     * user-initiated, so triggering authorization and rebinding while
     * disconnected is exactly what the user wants. Returns null when the
     * service face is unreachable.
     */
    private suspend fun <R> call(name: String, action: (com.azurpilot.ghio.RemoteService) -> R): R? =
        withContext(AppDispatchers.IO) {
            runCatching { servicePort.useService { action(it) } }
                .onFailure { Timber.w(it, "%s failed", name) }
                .getOrNull()
        }
}
