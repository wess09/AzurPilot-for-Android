package com.azurpilot.ghio.overlay

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import org.koin.core.component.KoinComponent

/**
 * 给悬浮窗里的 `ComposeView` 供三个 Owner
 *
 * 悬浮窗不在 Activity 树里，ComposeView 没有 lifecycle / ViewModelStore /
 * SavedState 可挂，Compose 组合与 `collectAsState` 全靠这三个 Owner 撑。
 * 生命周期由 [OverlayController] 手动驱动：面板显示走 [start]（CREATED → RESUMED），
 * 隐藏走 [stop]（退回 CREATED，不销毁，VM 与组合状态跨显隐保留）；
 * 进程内单实例，主线程驱动
 *
 * Supplies the three owners a `ComposeView` inside an overlay window needs.
 *
 * An overlay window sits outside the Activity tree, so its ComposeView has no
 * lifecycle / ViewModelStore / SavedState to attach to — Compose composition
 * and `collectAsState` depend entirely on these owners. The lifecycle is
 * driven manually by [OverlayController]: showing the panel calls [start]
 * (CREATED → RESUMED), hiding it calls [stop] (back to CREATED, never
 * destroyed, so VM and composition state survive show/hide cycles); a single
 * instance per process, driven on the main thread.
 */
class OverlayViewModelOwner : ViewModelStoreOwner,
    LifecycleOwner,
    SavedStateRegistryOwner,
    HasDefaultViewModelProviderFactory,
    KoinComponent {

    private val store = ViewModelStore()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val viewModelStore get() = store

    override val lifecycle get() = lifecycleRegistry

    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    /**
     * 悬浮窗里取不到 Activity 的 VM，一律从 Koin 拿
     *
     * No Activity VM is reachable from inside an overlay window, so every
     * ViewModel is resolved from Koin.
     */
    override val defaultViewModelProviderFactory by lazy {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                getKoin().get(modelClass.kotlin)
        }
    }

    override val defaultViewModelCreationExtras get() = MutableCreationExtras()

    /** 面板显示时调用：生命周期推进到 RESUMED / Called when the panel shows: the lifecycle advances to RESUMED. */
    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** 面板隐藏时调用：退回 CREATED，组合与 VM 保留 / Called when the panel hides: back to CREATED, keeping composition and ViewModels. */
    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }
}
