package com.azurpilot.ghio.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.domain.OverlayControlMode
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.domain.RunMode
import com.azurpilot.ghio.update.ReleaseUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * app 设置的唯一读写入口
 *
 * 各项以 StateFlow 暴露。读盘是异步的，[loaded] 置位之前 `.value` 还是 schema 默认值——
 * 同步 `.value` 是 [com.azurpilot.ghio.privileged.RemoteServiceManager] 那条链要的
 * （它收的是 `() -> RemoteBackend`，没有挂起点），所以读盘不能省，只能挪到构造之外
 *
 * **凡是在启动早期同步读 `.value` 的调用方都必须先等 [loaded]**：早读一步拿到的是
 * 默认值，Root 用户会被当成 Shizuku。启动首屏与 `AzurPilotApp.postCreate` 都挂在这上面
 *
 * 自持一条 IO 协程程域做读盘与迁移，进程级单例（Koin single）；所有写入都是
 * DataStore 事务，跨协程安全。
 *
 * The single read/write entry point for app settings.
 *
 * Each setting is exposed as a StateFlow. Disk reading is asynchronous, so
 * before [loaded] is set `.value` still holds the schema defaults — the
 * synchronous `.value` exists for the
 * [com.azurpilot.ghio.privileged.RemoteServiceManager] chain (it takes a
 * `() -> RemoteBackend` with no suspension point), so the disk read cannot be
 * dropped, only moved out of the constructor.
 *
 * **Every caller that synchronously reads `.value` early in startup must first
 * await [loaded]**: reading one step early yields the default value and a Root
 * user is treated as Shizuku. Both the startup screen and
 * `AzurPilotApp.postCreate` hang off this.
 *
 * The manager owns one IO coroutine scope for disk reads and migration, and is
 * a process-level singleton (Koin single); all writes are DataStore
 * transactions, safe across coroutines.
 */
class AppSettingsManager(private val context: Context) : AppSettingsGateway {

    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.IO)

    companion object {
        /** 单名 DataStore；属性委托保证全进程只有一个实例 / The single-name DataStore; the property delegate guarantees one instance per process. */
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
    }

    /** 整包 [AppSettings] 的原始流，未解析、含全部字段 / The raw flow of the whole [AppSettings], unparsed with all fields. */
    val settings: Flow<AppSettings> = with(AppSettingsSchema) { context.dataStore.flow }

    private val defaults = AppSettings()

    private val _ocrHardwareAccelerationEnabled = MutableStateFlow(true)

    /** OCR 硬件加速开关，默认开启。 / OCR hardware acceleration flag, enabled by default. */
    val ocrHardwareAccelerationEnabled: StateFlow<Boolean> = _ocrHardwareAccelerationEnabled.asStateFlow()

    private val _virtualDisplayRefreshRate = MutableStateFlow(0f)

    /** 虚拟屏刷新率，非法盘上值一律回落 0f / The virtual display refresh rate; invalid stored values always fall back to 0f. */
    val virtualDisplayRefreshRate: StateFlow<Float> = _virtualDisplayRefreshRate.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /**
     * 首次读盘是否已落到下面各 StateFlow 上；置位后 `.value` 才是盘上的值
     *
     * 等待点：启动首屏（`MainActivity`）与 `AzurPilotApp.postCreate`（`RemoteServiceManager`
     * 一初始化就同步读 startupBackend）
     *
     * Whether the first disk read has landed in the StateFlows below; only
     * after this is set does `.value` hold the on-disk values.
     *
     * Awaiting points: the startup screen (`MainActivity`) and
     * `AzurPilotApp.postCreate` (RemoteServiceManager synchronously reads
     * startupBackend as soon as it initializes).
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _startupBackend = MutableStateFlow(parseBackend(defaults.startupBackend))

    /** 启动期提权后端 / The privileged backend used at startup. */
    val startupBackend: StateFlow<RemoteBackend> = _startupBackend.asStateFlow()

    private val _skipShizukuCheck = MutableStateFlow(defaults.skipShizukuCheck.toBoolean())

    /** 是否跳过 Shizuku 引导提醒 / Whether the Shizuku onboarding reminder is skipped. */
    val skipShizukuCheck: StateFlow<Boolean> = _skipShizukuCheck.asStateFlow()

    private val _shizukuLaunchPackage = MutableStateFlow(defaults.shizukuLaunchPackage)

    /** Shizuku 管理器包名 / The Shizuku manager package name. */
    val shizukuLaunchPackage: StateFlow<String> = _shizukuLaunchPackage.asStateFlow()

    private val _runMode = MutableStateFlow(parseRunMode(defaults.runMode))
    override val runMode: StateFlow<RunMode> = _runMode.asStateFlow()

    private val _overlayControlMode = MutableStateFlow(parseOverlayMode(defaults.overlayControlMode))
    override val overlayControlMode: StateFlow<OverlayControlMode> = _overlayControlMode.asStateFlow()

    private val _screenSaverEnabled = MutableStateFlow(defaults.screenSaverEnabled.toBoolean())
    override val screenSaverEnabled: StateFlow<Boolean> = _screenSaverEnabled.asStateFlow()

    private val _autoCleanLogs = MutableStateFlow(defaults.autoCleanLogs.toBoolean())
    override val autoCleanLogs: StateFlow<Boolean> = _autoCleanLogs.asStateFlow()

    private val _keepAliveEnabled = MutableStateFlow(defaults.keepAliveEnabled.toBoolean())
    override val keepAliveEnabled: StateFlow<Boolean> = _keepAliveEnabled.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(defaults.appLockEnabled.toBoolean())
    override val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _compatNoticeShown = MutableStateFlow(defaults.compatNoticeShown.toBoolean())

    /** 首启「机型支持列表」弹窗是否已处理过 / Whether the first-launch device-support dialog has been handled. */
    val compatNoticeShown: StateFlow<Boolean> = _compatNoticeShown.asStateFlow()

    private val _communityNoticeAcknowledged = MutableStateFlow(defaults.communityNoticeAcknowledged.toBoolean())

    /** 社区规范与免责警告弹窗是否已确认 / Whether the community guidelines warning dialog has been acknowledged. */
    val communityNoticeAcknowledged: StateFlow<Boolean> = _communityNoticeAcknowledged.asStateFlow()

    private val _hotUpdateEnabled = MutableStateFlow(defaults.hotUpdateEnabled.toBoolean())

    /** 允许运行时热更新 / Whether runtime hot updates are allowed. */
    val hotUpdateEnabled: StateFlow<Boolean> = _hotUpdateEnabled.asStateFlow()

    private val _lanControlEnabled = MutableStateFlow(defaults.lanControlEnabled.toBoolean())

    /** 局域网控制（重启 Runtime 生效）/ Whether LAN control is on (effective after a Runtime restart). */
    val lanControlEnabled: StateFlow<Boolean> = _lanControlEnabled.asStateFlow()

    private val _remoteAccessEnabled = MutableStateFlow(defaults.remoteAccessEnabled.toBoolean())

    /** 远程访问（重启 Runtime 生效）/ Whether remote access is on (effective after a Runtime restart). */
    val remoteAccessEnabled: StateFlow<Boolean> = _remoteAccessEnabled.asStateFlow()

    private val _autoUpdateHour = MutableStateFlow(defaults.autoUpdateHour.toIntOrNull()?.coerceIn(0, 23) ?: 8)

    /** 每日自动更新检查时刻（0~23）/ The hour of day (0–23) for the daily auto-update check. */
    val autoUpdateHour: StateFlow<Int> = _autoUpdateHour.asStateFlow()

    private val _githubMirror = MutableStateFlow(defaults.githubMirror)

    /** Release 下载源 / The release download source. */
    val githubMirror: StateFlow<String> = _githubMirror.asStateFlow()

    private val _githubMirrorCustom = MutableStateFlow(defaults.githubMirrorCustom)

    /** 自定义镜像前缀 / The custom mirror prefix. */
    val githubMirrorCustom: StateFlow<String> = _githubMirrorCustom.asStateFlow()

    init {
        migrateLegacyMirrorSwitch()
        // 一处 collect 铺开到各字段，而不是每个字段各起一条 stateIn：
        // 那样 loaded 置位与各字段拿到首值是两件并发的事，早读的人仍可能读到默认值
        scope.launch {
            settings.collect { s ->
                _ocrHardwareAccelerationEnabled.value = s.ocrHardwareAccelerationEnabled.toBooleanStrictOrNull() ?: true
                _virtualDisplayRefreshRate.value = s.virtualDisplayRefreshRate.toFloatOrNull()
                    ?.takeIf { it.isFinite() && it >= 0f } ?: 0f
                _startupBackend.value = parseBackend(s.startupBackend)
                _skipShizukuCheck.value = s.skipShizukuCheck.toBoolean()
                _shizukuLaunchPackage.value = s.shizukuLaunchPackage
                _runMode.value = parseRunMode(s.runMode)
                _overlayControlMode.value = parseOverlayMode(s.overlayControlMode)
                _screenSaverEnabled.value = s.screenSaverEnabled.toBoolean()
                _autoCleanLogs.value = s.autoCleanLogs.toBoolean()
                _keepAliveEnabled.value = s.keepAliveEnabled.toBoolean()
                _appLockEnabled.value = s.appLockEnabled.toBoolean()
                _compatNoticeShown.value = s.compatNoticeShown.toBoolean()
                _communityNoticeAcknowledged.value = s.communityNoticeAcknowledged.toBoolean()
                _hotUpdateEnabled.value = s.hotUpdateEnabled.toBoolean()
                _lanControlEnabled.value = s.lanControlEnabled.toBoolean()
                _remoteAccessEnabled.value = s.remoteAccessEnabled.toBoolean()
                _autoUpdateHour.value = s.autoUpdateHour.toIntOrNull()?.coerceIn(0, 23) ?: 8
                _githubMirror.value = s.githubMirror
                _githubMirrorCustom.value = s.githubMirrorCustom
                // 必须是最后一行：置位即宣告上面全部就位
                _loaded.value = true
            }
        }
    }

    /**
     * 旧版是布尔镜像开关（useGithubMirror=true 即 ghproxy.net）；新键不存在而旧开关为 true
     * 时迁到对应镜像。迁移先于上面的 collect 完成（同一 scope 顺序 launch）。
     *
     * Legacy versions used a boolean mirror switch (useGithubMirror=true meant
     * ghproxy.net); when the new key is absent and the old switch is true, the
     * value migrates to the corresponding mirror. The migration completes
     * before the collect above (sequential launches on the same scope).
     */
    private fun migrateLegacyMirrorSwitch() {
        scope.launch {
            with(AppSettingsSchema) {
                context.dataStore.edit { prefs ->
                    // 生成键名走 camelToSnakeCase：旧字段 useGithubMirror 落盘为 use_github_mirror
                    if (prefs[githubMirror] == null &&
                        prefs[stringPreferencesKey("use_github_mirror")] == "true"
                    ) {
                        prefs[githubMirror] = ReleaseUrls.MIRRORS.first()
                    }
                }
            }
        }
    }

    /** 写入启动期提权后端 / Writes the startup privileged backend. */
    suspend fun setStartupBackend(backend: RemoteBackend) = with(AppSettingsSchema) {
        context.dataStore.edit { it[startupBackend] = backend.name }
    }

    /** 保存 OCR 硬件加速开关。 / Persists the OCR hardware acceleration flag. */
    suspend fun setOcrHardwareAccelerationEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[ocrHardwareAccelerationEnabled] = enabled.toString() }
    }

    /** 写入虚拟屏刷新率；负数或非有限值直接抛 [IllegalArgumentException] / Writes the virtual display refresh rate; a negative or non-finite value throws [IllegalArgumentException] immediately. */
    suspend fun setVirtualDisplayRefreshRate(rate: Float): Unit = with(AppSettingsSchema) {
        require(rate.isFinite() && rate >= 0f)
        context.dataStore.edit { it[virtualDisplayRefreshRate] = rate.toString() }
    }

    /** 写入「跳过 Shizuku 提醒」 / Writes the "skip Shizuku reminder" flag. */
    suspend fun setSkipShizukuCheck(skip: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[skipShizukuCheck] = skip.toString() }
    }

    override suspend fun setRunMode(mode: RunMode): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[runMode] = mode.name }
    }

    override suspend fun setOverlayControlMode(mode: OverlayControlMode): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[overlayControlMode] = mode.name }
    }

    override suspend fun setScreenSaverEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[screenSaverEnabled] = enabled.toString() }
    }

    override suspend fun setAutoCleanLogs(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[autoCleanLogs] = enabled.toString() }
    }

    override suspend fun setKeepAliveEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[keepAliveEnabled] = enabled.toString() }
    }

    override suspend fun setAppLockEnabled(enabled: Boolean): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[appLockEnabled] = enabled.toString() }
    }

    /** 写入「机型支持列表弹窗已处理」 / Writes the "device-support dialog handled" flag. */
    suspend fun setCompatNoticeShown(shown: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[compatNoticeShown] = shown.toString() }
    }

    /** 写入「社区规范警告弹窗已确认」 / Writes the "community guidelines warning acknowledged" flag. */
    suspend fun setCommunityNoticeAcknowledged(acknowledged: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[communityNoticeAcknowledged] = acknowledged.toString() }
    }

    /** 写入「允许运行时热更新」 / Writes the "runtime hot updates allowed" flag. */
    suspend fun setHotUpdateEnabled(enabled: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[hotUpdateEnabled] = enabled.toString() }
    }

    /** 写入「局域网控制」 / Writes the "LAN control" flag. */
    suspend fun setLanControlEnabled(enabled: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[lanControlEnabled] = enabled.toString() }
    }

    /** 写入「远程访问」 / Writes the "remote access" flag. */
    suspend fun setRemoteAccessEnabled(enabled: Boolean) = with(AppSettingsSchema) {
        context.dataStore.edit { it[remoteAccessEnabled] = enabled.toString() }
    }

    /** 写入每日自动更新检查时刻 / Writes the daily auto-update check hour (0–23). */
    suspend fun setAutoUpdateHour(hour: Int) = with(AppSettingsSchema) {
        context.dataStore.edit { it[autoUpdateHour] = hour.coerceIn(0, 23).toString() }
    }

    /** 写入 Release 下载源 / Writes the release download source. */
    suspend fun setGithubMirror(mirror: String): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[githubMirror] = mirror }
    }

    /** 写入自定义镜像前缀 / Writes the custom mirror prefix. */
    suspend fun setGithubMirrorCustom(prefix: String): Unit = with(AppSettingsSchema) {
        context.dataStore.edit { it[githubMirrorCustom] = prefix }
    }

    /**
     * 解析后端枚举；盘上是历史遗留或手改的非法值时回落默认，不让设置读取本身抛异常
     *
     * Parses the backend enum; a legacy or hand-edited invalid stored value
     * falls back to the default instead of making the settings read throw.
     */
    private fun parseBackend(raw: String): RemoteBackend =
        runCatching { RemoteBackend.valueOf(raw) }.getOrDefault(RemoteBackend.SHIZUKU)

    /** 解析运行模式枚举，非法值回落 [RunMode.BACKGROUND] / Parses the run mode enum; invalid values fall back to [RunMode.BACKGROUND]. */
    private fun parseRunMode(raw: String): RunMode =
        runCatching { RunMode.valueOf(raw) }.getOrDefault(RunMode.BACKGROUND)

    /** 解析悬浮控制模式枚举，非法值回落 [OverlayControlMode.FLOAT_BALL] / Parses the overlay control mode enum; invalid values fall back to [OverlayControlMode.FLOAT_BALL]. */
    private fun parseOverlayMode(raw: String): OverlayControlMode =
        runCatching { OverlayControlMode.valueOf(raw) }.getOrDefault(OverlayControlMode.FLOAT_BALL)
}
