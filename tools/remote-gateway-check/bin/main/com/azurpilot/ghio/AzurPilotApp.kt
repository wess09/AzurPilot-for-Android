package com.azurpilot.ghio

import android.app.Application
import android.os.Build
import com.azurpilot.ghio.constant.AppPaths
import com.azurpilot.ghio.di.AppCoroutineScope
import com.azurpilot.ghio.di.coreModule
import com.azurpilot.ghio.di.hostModule
import com.azurpilot.ghio.di.logModule
import com.azurpilot.ghio.di.overlayModule
import com.azurpilot.ghio.di.privilegedModule
import com.azurpilot.ghio.di.prootModule
import com.azurpilot.ghio.di.provisionModule
import com.azurpilot.ghio.di.viewModelModule
import com.azurpilot.ghio.keepalive.FairMemoryAdaptation
import com.azurpilot.ghio.keepalive.KeepAliveManager
import com.azurpilot.ghio.log.AppLogWriter
import com.azurpilot.ghio.log.CrashHandler
import com.azurpilot.ghio.log.LogCleaner
import com.azurpilot.ghio.log.LogTreeHolder
import com.azurpilot.ghio.overlay.OverlayController
import com.azurpilot.ghio.overlay.screensaver.ScreenSaverOverlayManager
import com.azurpilot.ghio.privileged.PermissionManager
import com.azurpilot.ghio.privileged.RemoteServiceManager
import com.azurpilot.ghio.proot.AzurPilotGateway
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.proot.AzurPilotRunController
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.service.HostState
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.widget.AzurPilotWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.qualifier.named
import timber.log.Timber

/**
 * Application 入口：初始化进程级全局，并按依赖顺序拉起各常驻组件
 *
 * 仅主进程走完整初始化；其余进程（:daemon 等守护进程）只挂调试日志树后返回。
 * 顺序：路径解析 → 崩溃兜底 → Koin → 日志写入器挂载；随后等设置异步加载完成，
 * 在主线程 [postCreate] 里拉起提权、宿主状态、运行控制器、网关与仓库、
 * 悬浮窗、保活与桌面小组件刷新。
 *
 * The Application entry point: initializes process-level globals and starts
 * the resident components in dependency order.
 *
 * Only the main process takes the full path; any other process (the :daemon
 * watchdog among them) plants the debug log tree and returns. Order: path
 * resolution → crash handler → Koin → log writer installation; then, once
 * settings finish loading asynchronously, [postCreate] runs on the main thread
 * to start privilege management, host state, the run controller, the gateway
 * and repository, overlays, keep-alive, and widget refresh.
 */
class AzurPilotApp : Application() {

    private val writer by inject<AppLogWriter>()
    private val settings by inject<AppSettingsManager>()

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
            Timber.d("AzurPilotApp: Secondary daemon process initialized (PID=${android.os.Process.myPid()})")
            return
        }
        AppPaths.init(this)
        CrashHandler().install()
        val app = this
        val koin = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(
                coreModule,
                privilegedModule,
                hostModule,
                logModule,
                overlayModule,
                provisionModule,
                prootModule,
                viewModelModule,
            )
        }.koin
        writer.setup()
        LogTreeHolder(writer).setup()
        koin.get<CoroutineScope>(named<AppCoroutineScope>()).launch {
            settings.loaded.first { it }
            // 自动清理门控：静默执行，失败不挡启动；汇总行由 LogCleaner 自己 Timber.w
            launch(AppDispatchers.IO) {
                runCatching {
                    if (settings.autoCleanLogs.value) koin.get<LogCleaner>().cleanOutdated()
                }.onFailure { Timber.w(it, "LogCleaner 执行失败") }
            }
            withContext(Dispatchers.Main) { postCreate(koin) }
        }
    }

    /**
     * 在主线程按依赖顺序拉起常驻组件；由 [onCreate] 在设置加载完成后调用
     *
     * 覆盖：提权端口初始化、宿主状态与运行控制器启动、WebSocket 网关与仓库
     * 订阅（仓库首个实例对齐运行控制器当前选中的配置）、小组件防抖刷新
     * （任一运行态变化后 300ms 合并一次）、悬浮窗装配、保活与金标联盟
     * 「公平运行内存」适配。
     *
     * Starts the resident components on the main thread in dependency order;
     * invoked by [onCreate] once settings finish loading.
     *
     * Covers: privilege port initialization, host state and run controller
     * startup, the WebSocket gateway and repository subscriptions (the
     * repository's first instance aligns with the run controller's selected
     * config), debounced widget refresh (coalesced 300 ms after any run-state
     * change), overlay setup, keep-alive, and the "fair memory" adaptation.
     */
    @OptIn(FlowPreview::class)
    fun postCreate(koin: Koin) {
        koin.get<PermissionManager>()
        val provider = koin.get<AppSettingsManager>().startupBackend::value
        RemoteServiceManager.initialize(this, provider)
        val hostState = koin.get<HostState>()
        hostState.start()
        val runController = koin.get<AzurPilotRunController>()
        runController.selectConfig(com.azurpilot.ghio.proot.AzurPilotRunState.DEFAULT_CONFIG)
        runController.start()
        koin.get<com.azurpilot.ghio.provision.RuntimeAutoUpdater>().start()
        // 富接口：与 /android/… 薄接口并存。实例选择归 repository 自己管，
        // 首次进入时对齐运行控制器选的那个配置——否则原生界面一打开就是空实例。
        koin.get<AzurPilotGateway>().start()
        val repository = koin.get<AzurPilotRepository>()
        repository.start()
        val prootHost = koin.get<ProotHost>()
        koin.get<CoroutineScope>(named<AppCoroutineScope>()).launch {
            runController.state
                .map { it.selectedConfig }
                .distinctUntilChanged()
                .collect { config ->
                    if (repository.selectedInstance.value == null) {
                        repository.selectInstance(config)
                    }
                }
        }
        koin.get<CoroutineScope>(named<AppCoroutineScope>()).launch {
            combine(
                runController.state,
                prootHost.state,
                hostState.snapshot,
                repository.overview,
            ) { _, _, _, _ -> Unit }
                .debounce(300)
                .collect {
                    AzurPilotWidgetUpdater.updateAll(this@AzurPilotApp)
                }
        }
        koin.get<OverlayController>().setup()
        koin.get<ScreenSaverOverlayManager>().setup()
        koin.get<KeepAliveManager>().start()
        // 首次初始化更新桌面小组件
        AzurPilotWidgetUpdater.updateAll(this)
        // 金标联盟「公平运行内存」适配：HyperOS 等系统内存超限预警/查杀的协议应答，
        // 无权限无副作用，非该机制系统上收不到广播，常开（见 FairMemoryAdaptation）
        FairMemoryAdaptation(this).start()
    }

    /**
     * 判断当前进程是否为主进程 / Returns whether the current process is the main process.
     *
     * API 28 起 [getProcessName] 直接可用；更早版本遍历 runningAppProcesses 按
     * pid 匹配，拿不到进程列表时按 packageName 兜底。
     *
     * [getProcessName] is available since API 28; older levels scan
     * runningAppProcesses by pid, falling back to packageName when the list is
     * unavailable.
     */
    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.runningAppProcesses?.find { it.pid == pid }?.processName ?: packageName
        }
        return processName == packageName
    }
}
