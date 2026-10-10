package com.azurpilot.ghio;

import android.view.Surface;
import com.azurpilot.ghio.ITouchEventCallback;
import com.azurpilot.ghio.IRunnerCallback;

/**
 * App 进程与特权服务之间的稳定 Binder 服务面。
 *
 * 服务可能由 Shizuku 或 root `app_process` 创建，并能短暂跨越 app 升级继续存活。因此所有
 * transaction ID 显式固定且只能追加，绝不能按声明顺序重排；新旧两端必须按同一 code 解释
 * 每个 Binder 事务。同步方法在特权服务的 Binder 线程池执行，`oneway` 方法绝不能依赖调用
 * 方收到异常或返回值。
 *
 * Stable Binder service surface between the app process and the privileged service.
 *
 * Shizuku or root `app_process` may create the service, and it can briefly survive an app upgrade.
 * Every transaction ID is therefore explicitly fixed and append-only: never reorder declarations,
 * because old and new endpoints must interpret each Binder transaction by the same code. Synchronous
 * methods run on the privileged service's Binder pool; `oneway` methods must not rely on the caller
 * receiving an exception or a return value.
 */
interface RemoteService {

    /**
     * 请求特权进程完整清理后退出。
     *
     * `16777114` 是此服务 `destroy()` 的显式稳定 ABI code。生命周期清理会由
     * `RootServiceStarter` 直接调用 `IBinder.transact`，因此不得改号或重用。
     *
     * Requests that the privileged process clean up completely and exit.
     *
     * `16777114` is this service's explicit stable ABI code for `destroy()`. Lifecycle cleanup
     * calls `IBinder.transact` directly from `RootServiceStarter`, so the code must not change or
     * be reused.
     */
    oneway void destroy() = 16777114;

    /**
     * 作为 `destroy()` 的兼容别名。
     *
     * Compatibility alias for `destroy()`.
     */
    void exit() = 1;

    /**
     * 返回特权进程、UID、PID 和 native bridge 的诊断摘要。
     *
     * Returns a diagnostic summary of the privileged process, UID, PID, and native bridge.
     */
    String version() = 2;

    /**
     * 返回当前特权进程 PID。
     *
     * Returns the current privileged-process PID.
     */
    int pid() = 3;

    /**
     * 上报 app 进程 PID，供特权侧看门狗检测 app 是否死亡。
     *
     * 调用为单向通知；app 进程必须周期性发送，服务不会确认接收。
     *
     * Reports the app-process PID so the privileged watchdog can detect app death.
     *
     * This is one-way notification. The app must send it periodically, and the service does not
     * acknowledge receipt.
     */
    oneway void heartbeat(int appPid) = 4;

    /**
     * 执行特权服务的一次性连接初始化。
     *
     * 参数会记入诊断日志，当前实现还会禁用 Android 12+ 的 phantom process killer。参数暂不
     * 改变特权服务的路径或调试配置；调用方不得把成功结果视为 Runtime 已配置或就绪。
     *
     * Performs one-time connection initialization for the privileged service.
     *
     * The parameters are recorded in diagnostic logs; the current implementation also disables
     * Android 12+ phantom process killing. They do not yet change privileged-service paths or
     * debugging. Callers must not treat success as Runtime configuration or readiness.
     */
    boolean setup(String piRoot, String logDir, boolean isDebug) = 5;

    /**
     * 选择主屏采集或独立虚拟屏模式；未知值返回 false。
     *
     * Selects primary-display capture or standalone virtual-display mode; unknown values return
     * false.
     */
    boolean setVirtualDisplayMode(int mode) = 10;

    /**
     * 设置下一次虚拟屏创建使用的尺寸和 DPI；活动虚拟屏会重建。
     *
     * Sets dimensions and DPI for virtual-display creation; an active virtual display is recreated.
     */
    void setVirtualDisplayResolution(int width, int height, int dpi) = 11;

    /**
     * 创建当前模式对应的显示器，失败时返回 `DISPLAY_NONE`。
     *
     * Creates the display for the current mode; returns `DISPLAY_NONE` on failure.
     */
    int startVirtualDisplay() = 12;

    /**
     * 停止显示器以及它依赖的看门狗和保活资源。
     *
     * Stops the display and its dependent watchdog and keep-alive resources.
     */
    void stopVirtualDisplay() = 13;

    /**
     * 判断目标应用是否仍在当前虚拟屏上；无虚拟屏时返回 true。
     *
     * Reports whether the target app remains on the current virtual display; returns true when no
     * virtual display exists.
     */
    boolean isAppOnVirtualDisplay(String packageName) = 14;

    /**
     * 将目标应用任务固定回当前虚拟屏；无活动屏时返回 false。
     *
     * Pins the target app task back to the current virtual display; returns false without an active
     * display.
     */
    boolean moveAppToVirtualDisplay(String packageName) = 15;

    /**
     * 设置虚拟屏启动时是否强制全屏窗口模式。
     *
     * Sets whether virtual-display launches force fullscreen windowing.
     */
    oneway void setForceFullscreenOnVirtualDisplay(boolean enabled) = 16;

    /**
     * 请求切换物理主屏电源状态。
     *
     * Requests a physical primary-display power-state change.
     */
    oneway void setDisplayPower(boolean on) = 17;

    /**
     * 强制修改物理主屏尺寸，而不是后台虚拟屏尺寸。
     *
     * 这是全系统 UI 重排操作；调用方应在成功后负责提供恢复路径。
     *
     * Forces the physical primary-display size, not the background virtual-display size.
     *
     * This reflows the whole system UI. Callers must provide a recovery path after success.
     */
    boolean setForcedDisplaySize(int width, int height) = 18;

    /**
     * 撤销 `setForcedDisplaySize()` 的主屏尺寸覆盖。
     *
     * Clears the primary-display size override created by `setForcedDisplaySize()`.
     */
    boolean clearForcedDisplaySize() = 19;

    /**
     * 设置可选预览 Surface；传 null 会关闭预览。
     *
     * Sets the optional preview Surface; null disables preview.
     */
    void setMonitorSurface(in Surface surface) = 20;

    /**
     * 注册预览手势的 app 侧回调；传 null 解除回调。
     *
     * Registers the app-side callback for preview gestures; null unregisters it.
     */
    oneway void setTouchCallback(ITouchEventCallback callback) = 21;

    /**
     * 向当前虚拟屏注入单指按下；主屏模式或无屏时服务会忽略。
     *
     * Injects a single-pointer down event into the current virtual display; the service ignores it
     * in primary mode or without a display.
     */
    oneway void touchDown(int x, int y) = 30;

    /**
     * 向当前虚拟屏注入单指移动；主屏模式或无屏时服务会忽略。
     *
     * Injects a single-pointer move event into the current virtual display; the service ignores it
     * in primary mode or without a display.
     */
    oneway void touchMove(int x, int y) = 31;

    /**
     * 向当前虚拟屏注入单指抬起；主屏模式或无屏时服务会忽略。
     *
     * Injects a single-pointer up event into the current virtual display; the service ignores it
     * in primary mode or without a display.
     */
    oneway void touchUp(int x, int y) = 32;

    /**
     * 以特权身份检查目标包是否已安装。
     *
     * Checks whether the target package is installed using privileged identity.
     */
    boolean isPackageInstalled(String packageName) = 40;

    /**
     * 尝试向 app 授予请求的 `PrivilegedGrant` 位。
     *
     * `permissions` 和返回值均为位掩码。使用整数而非 Parcelable，使旧特权进程仍能与升级后
     * 的 app 交换稳定线格式。
     *
     * Attempts to grant the requested `PrivilegedGrant` bits to the app.
     *
     * Both `permissions` and the return value are bit masks. Integers, rather than Parcelable,
     * keep the wire format stable when an old privileged process talks to an upgraded app.
     */
    int grantPermissions(String packageName, int uid, int permissions) = 41;

    /**
     * 注册 Runtime 执行事件回调；当前特权实现保留 ABI 但不执行运行控制。
     *
     * Registers the Runtime execution-event callback. The current privileged implementation keeps
     * the ABI but does not run execution control.
     */
    oneway void setRunnerCallback(IRunnerCallback callback) = 50;

    /**
     * 请求开始 Runtime 执行；当前特权实现保留 ABI 但返回 false。
     *
     * Requests Runtime execution start. The current privileged implementation keeps the ABI but
     * returns false.
     */
    boolean startRun(String runPlanJson) = 51;

    /**
     * 请求停止 Runtime 执行；当前特权实现保留 ABI 但返回 false。
     *
     * Requests Runtime execution stop. The current privileged implementation keeps the ABI but
     * returns false.
     */
    boolean stopRun() = 52;

    /**
     * 查询 Runtime 是否执行中；当前特权实现保留 ABI 但返回 false。
     *
     * Queries whether the Runtime is executing. The current privileged implementation keeps the
     * ABI but returns false.
     */
    boolean isRunning() = 53;

    /**
     * 返回 Runtime native 版本；当前特权实现保留 ABI 但返回 null。
     *
     * Returns the Runtime native version. The current privileged implementation keeps the ABI but
     * returns null.
     */
    String nativeVersion() = 54;

    /**
     * 返回 app 看门狗状态码：0=IDLE、1=WATCHING、2=APP_DIED。
     *
     * Returns the app-watchdog state code: 0=IDLE, 1=WATCHING, 2=APP_DIED.
     */
    int watchdogState() = 60;

    /**
     * 返回看门狗当前推断的目标包名；未知时为空串。
     *
     * Returns the target package currently inferred by the watchdog; empty when unknown.
     */
    String watchdogTargetPackage() = 61;

    /**
     * 解锁并点亮屏幕；`credential` 仅支持数字 PIN，空串表示无凭证锁屏。
     *
     * Unlocks and wakes the screen. `credential` supports only a numeric PIN, and an empty string
     * means an unsecured keyguard.
     */
    int unlock(String credential) = 70;

    /**
     * 执行锁屏、息屏、再解锁的设置页自测。
     *
     * Runs the settings self-test: lock, sleep, then unlock.
     */
    int testUnlock(String credential) = 71;

    /**
     * 立即上锁并请求息屏。
     *
     * Immediately locks the keyguard and requests sleep.
     */
    int lockAndSleep() = 72;

    /**
     * 强停看门狗推断的目标应用；尚无目标时返回 false。
     *
     * Force-stops the target application inferred by the watchdog; returns false when no target
     * exists.
     */
    boolean stopTargetApp() = 73;

    /**
     * 宽松查询主屏是否亮起；底层读取失败时返回 true。
     *
     * Leniently queries whether the primary screen is on; returns true when the underlying read
     * fails.
     */
    boolean isScreenOn() = 74;

    /**
     * 将特权控制器缓存的截图保存到调用方指定路径；大图使用文件以避免 Binder 1 MB 缓冲限制。
     *
     * Saves the privileged controller's cached screenshot at the caller-provided path. Large images
     * use a file to avoid Binder's 1 MB buffer limit.
     */
    boolean saveCachedImage(String path) = 75;

    /**
     * 设置下一次虚拟屏创建使用的请求刷新率；0 表示跟随系统，仅 Android 14+ 生效。
     *
     * Sets the requested refresh rate for the next virtual-display creation; 0 follows the system
     * and is effective only on Android 14+.
     */
    void setVirtualDisplayRefreshRate(float rate) = 76;

    /** Actual UID of the privileged process; used for cloud-ROM backend compatibility. */
    int processUid() = 77;
}
