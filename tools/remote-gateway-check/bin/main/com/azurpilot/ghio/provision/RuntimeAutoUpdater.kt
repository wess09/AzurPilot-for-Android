package com.azurpilot.ghio.provision

import android.app.Application
import android.content.Context
import android.os.Build
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.proot.AndroidControlAuth
import com.azurpilot.ghio.proot.ProotHost
import com.azurpilot.ghio.proot.ProotPhase
import com.azurpilot.ghio.settings.AppSettingsManager
import com.azurpilot.ghio.update.DownloadAborted
import com.azurpilot.ghio.update.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime 自动更新状态机
 *
 * 状态流转：
 * Idle → Checking → Downloading(done, total) → Extracting(done, total)
 *      → Suspending → Swapping → Verifying
 *      → Completed(version) 或 RollingBack → Failed(reason, rolledBack)
 *
 * State machine for Runtime automatic updates.
 */
sealed interface AutoUpdateState {
    /** 空闲等待中 / Idle waiting. */
    data object Idle : AutoUpdateState

    /** 正在检查新版本 / Checking for a new release. */
    data object Checking : AutoUpdateState

    /** 下载 rootfs 归档中 / Downloading the rootfs archive. */
    data class Downloading(val done: Long, val total: Long) : AutoUpdateState

    /** 解压到 staging 目录中 / Extracting to the staging directory. */
    data class Extracting(val done: Long, val total: Long) : AutoUpdateState

    /** 正在通知运行时挂起活跃实例 / Suspending running instances via the runtime API. */
    data object Suspending : AutoUpdateState

    /** 停止旧会话并原子切换目录 / Stopping the old session and swapping rootfs. */
    data object Swapping : AutoUpdateState

    /** 启动新版本并探活体检中 / Starting the new version and running health probes. */
    data object Verifying : AutoUpdateState

    /** 更新完成 / Update completed successfully. */
    data class Completed(val version: String) : AutoUpdateState

    /** 体检失败，正在回滚旧版本 / Verification failed; rolling back to previous version. */
    data class RollingBack(val reason: String) : AutoUpdateState

    /** 更新失败 / Update failed. */
    data class Failed(val reason: String, val rolledBack: Boolean) : AutoUpdateState
}

/**
 * Runtime 自动更新编排器
 *
 * 职责：
 * 1. 每日定时（按 [AppSettingsManager.autoUpdateHour]，默认 8:00 AM）触发后台更新检查
 * 2. 检测到全量 rootfs 更新（非 commitOnly 源码微更）时，下载归档并做 SHA-256 完整性校验
 * 3. 解压至暂存目录 `rootfs.tmp`，不干扰前台和正在运行的任务
 * 4. 调用上游运行时 `POST /android/update/suspend` 安全挂起所有运行实例（写入 `config/reloadalas`）
 * 5. 安全停止 proot 会话，通过原子换名（`rootfs` → `rootfs.previous`, `rootfs.tmp` → `rootfs`）完成热切换
 * 6. 重新启动 proot 会话并探活体检（`/android/status` + `/healthz`，90s 超时上限）
 * 7. 若体检健康，删除 `rootfs.previous`；运行时启动生命周期读取 `config/reloadalas` 自动复活被挂起实例
 * 8. 若体检失败，自动回滚至 `rootfs.previous` 并重启旧版，保障可用性
 * 9. 暴露 [busy] 状态流给 [com.azurpilot.ghio.proot.AzurPilotRunController]，防止源码热更与全量更新冲突
 *
 * Runtime automatic update orchestrator.
 *
 * Orchestrates daily scheduled checks, staging decompression, instance suspension,
 * atomic directory swapping, health validation, rollback on failure, and automatic instance revival.
 */
class RuntimeAutoUpdater(
    private val app: Application,
    private val scope: CoroutineScope,
    private val settings: AppSettingsManager,
    private val provisioner: RootfsProvisioner,
    private val prootHost: ProotHost,
) {
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<AutoUpdateState>(AutoUpdateState.Idle)

    /** 对外只读的自动更新状态机 / Externally read-only update state. */
    val state: StateFlow<AutoUpdateState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** 当前是否正处于更新事务中（互斥锁）/ Whether an update transaction is in progress. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val started = AtomicBoolean(false)
    private val updateMutex = Mutex()

    /**
     * 启动定时轮询守护协程（幂等）
     *
     * Starts the scheduled polling coroutine (idempotent).
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(AppDispatchers.IO) {
            while (true) {
                scheduledTick()
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * 检查当前是否到达每日设定的更新时机。
     *
     * 触发判据是「不早于设定时刻的当天首次 tick」：进程在设定整点没活着（被杀/Doze 压住）
     * 或跨过了整点才醒来时照样补跑，而不是整天错过；设定时刻之前绝不提前触发。
     *
     * Checks if the daily scheduled hour has arrived. Fires on the first tick
     * of the day at or after the configured hour: a process that was dead or
     * dozing across the hour still catches up instead of missing the whole
     * day, and never fires before the hour.
     */
    private suspend fun scheduledTick() {
        if (!settings.oobeCompleted.value || settings.remoteClientMode.value) return
        if (!settings.hotUpdateEnabled.value) return
        // 首次部署由前台显示进度，尚未在位时不消耗当天的自动检查机会。
        if (!provisioner.isInstalled()) return
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val targetHour = settings.autoUpdateHour.value
        val today = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
        )
        val lastDate = prefs.getString(KEY_LAST_CHECK_DATE, null)
        if (hour >= targetHour && lastDate != today) {
            prefs.edit().putString(KEY_LAST_CHECK_DATE, today).apply()
            Timber.i("auto update: scheduled trigger at %02d:00 for %s", hour, today)
            checkAndApplyUpdate(force = false)
        }
    }

    /**
     * 为热更独占整包更新共用的流水线，避免两个通道同时修改运行时。
     *
     * Reserves the shared pipeline for a hot update so both channels cannot modify the runtime.
     */
    internal fun tryBeginHotUpdate(): Boolean {
        if (!provisioner.tryBeginPipeline()) return false
        if (!updateMutex.tryLock()) {
            provisioner.endPipeline()
            return false
        }
        _busy.value = true
        return true
    }

    /** 释放热更取得的流水线。 / Releases the pipeline reserved by a hot update. */
    internal fun endHotUpdate() {
        _busy.value = false
        updateMutex.unlock()
        provisioner.endPipeline()
    }

    /**
     * 执行一次检查与更新流程。
     *
     * Performs one check-and-apply update cycle.
     *
     * @param force 是否强制执行（忽略 commitOnly 检查与每日频次限制）/ whether to force update
     * @return 更新是否成功 / whether the update succeeded
     */
    suspend fun checkAndApplyUpdate(force: Boolean = false): Boolean = withContext(AppDispatchers.IO) {
        // Application 启动早于部署页；自动更新不能抢走首次部署的流水线。
        if (!provisioner.isInstalled()) {
            Timber.d("auto update: runtime not installed, leaving first deployment to provisioner")
            return@withContext false
        }
        // 与首启部署/手动整包更新（applyUpdate）共用同一把流水线锁：三条链都写 rootfs.tmp
        // 并对 rootfs 做换名，并发会互相覆盖；抢不到锁直接让位
        if (!provisioner.tryBeginPipeline()) {
            Timber.d("auto update: provision pipeline busy, skipping")
            return@withContext false
        }
        if (!updateMutex.tryLock()) {
            Timber.d("auto update: another update is in progress, skipping")
            provisioner.endPipeline()
            return@withContext false
        }
        _busy.value = true
        var staged = false
        var swapped = false
        var wasActive = false
        val archive = File(app.filesDir, ARCHIVE_NAME)

        try {
            _state.value = AutoUpdateState.Checking
            val info = provisioner.fetchIndex()
            if (!info.has("rootfsVersion") && !info.has("runtimes")) {
                Timber.d("auto update: no runtime section in release manifest")
                _state.value = AutoUpdateState.Idle
                return@withContext false
            }

            val abi = RuntimeArch.deviceAbi()
                ?: throw IOException("设备架构不受支持：${Build.SUPPORTED_ABIS.firstOrNull()}")
            val runtime = provisioner.parseRuntime(info, abi)
                ?: throw IOException("发布渠道暂无 $abi 的 Runtime")

            val installed = provisioner.installedVersion()
            if (!runtimeUpdateNeeded(installed, runtime.version)) {
                Timber.d(
                    "auto update: AP is up-to-date (installed=%s latest=%s)", installed, runtime.version,
                )
                _state.value = AutoUpdateState.Idle
                return@withContext false
            }

            // 若仅上游提交差异（commitOnly），说明基础镜像无变化，交给轻量热更通道处理，
            // 不重复下载整包。但热更通道依赖运行时的 /android/update 接口：运行时没在跑或
            // 版本太旧没有该接口时，commitOnly 永远无法送达——此时必须退回整包更新，
            // 否则这次发布在该设备上永远装不上。
            val commitOnly = installed != null &&
                runtime.version.substringAfterLast('-') == installed.substringAfterLast('-')
            val hotStatus = hotUpdateStatus()
            if (hotStatus?.optBoolean("busy") == true) {
                _state.value = AutoUpdateState.Idle
                return@withContext false
            }
            val canHotUpdate = hotStatus?.let {
                it.optBoolean("enabled") && !it.optBoolean("requiresRuntimeUpdate")
            } == true
            if (commitOnly && !force && canHotUpdate) {
                Timber.i("auto update: commitOnly update detected, letting runtime hot-update handle it")
                _state.value = AutoUpdateState.Idle
                return@withContext false
            }

            provisioner.checkDisk()

            // 1. 下载 rootfs 归档
            while (true) {
                val prefix = provisioner.downloadSource()
                try {
                    provisioner.downloadArchive(runtime, prefix, archive) { done, total ->
                        _state.value = AutoUpdateState.Downloading(done, total)
                    }
                    break
                } catch (changed: DownloadAborted) {
                    if (!provisioner.sourceSwitched(prefix)) throw IOException("下载被中止", changed)
                    Timber.i("auto update download source changed, restarting")
                    archive.delete()
                    _state.value = AutoUpdateState.Downloading(0, runtime.size)
                }
            }

            // 2. 校验 SHA-256
            check(sha256Hex(archive).equals(runtime.sha256, ignoreCase = true)) {
                "rootfs 下载校验失败：sha256 不匹配"
            }

            // 3. 解压到 staging 目录 rootfs.tmp
            _state.value = AutoUpdateState.Extracting(0, runtime.size)
            provisioner.extractToStaging({ archive.inputStream() }, runtime.size, runtime.version) { read, total ->
                _state.value = AutoUpdateState.Extracting(read, total)
            }
            archive.delete()
            staged = true

            // 4. 通知运行时挂起活跃实例并写入 reloadalas 恢复清单。
            // 挂起失败必须中止：suspend 同时负责写复活清单，硬着头皮换目录会把
            // 运行中的调度器连根杀掉且更新后不再复活
            _state.value = AutoUpdateState.Suspending
            wasActive = prootHost.startRequested || prootHost.state.value.phase != ProotPhase.IDLE
            if (prootHost.state.value.phase == ProotPhase.RUNNING && !suspendRuntime()) {
                throw IOException("运行时挂起失败，为保住运行中的任务已中止本次更新")
            }

            // 5. 停止旧会话并原子替换 rootfs
            _state.value = AutoUpdateState.Swapping
            prootHost.stopAndAwait()
            provisioner.commitStaging()
            swapped = true

            // 6. 启动新版本并探活体检
            _state.value = AutoUpdateState.Verifying
            prootHost.ensureStarted()
            val healthy = prootHost.awaitServices(HEALTH_CHECK_TIMEOUT_MS)

            if (healthy) {
                Timber.i("auto update succeeded: %s", runtime.version)
                provisioner.cleanupPrevious()
                if (!wasActive) {
                    // 更新前会话本就没在跑：体检完恢复原状，别替用户把运行时拉起来
                    prootHost.stopAndAwait()
                }
                provisioner.refreshUpdateCheck()
                _state.value = AutoUpdateState.Completed(runtime.version)
                true
            } else {
                Timber.e("auto update health check failed after %dms, rolling back", HEALTH_CHECK_TIMEOUT_MS)
                _state.value = AutoUpdateState.RollingBack("新版本健康检查超时")
                prootHost.stopAndAwait()
                provisioner.rollbackStaging()
                prootHost.ensureStarted()
                val restored = prootHost.awaitServices(HEALTH_CHECK_TIMEOUT_MS)
                if (!wasActive) prootHost.stopAndAwait()
                provisioner.refreshUpdateCheck()
                _state.value = if (restored) {
                    AutoUpdateState.Failed("新版本启动超时，已自动回滚", rolledBack = true)
                } else {
                    AutoUpdateState.Failed("新版本启动超时，已回滚但旧版本未能恢复服务", rolledBack = true)
                }
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "auto update failed")
            archive.delete()
            if (staged && !swapped) {
                runCatching { File(app.filesDir, "rootfs.tmp").deleteRecursively() }
            }
            if (swapped) {
                runCatching {
                    prootHost.stopAndAwait()
                    provisioner.rollbackStaging()
                    prootHost.ensureStarted()
                    prootHost.awaitServices(HEALTH_CHECK_TIMEOUT_MS)
                    if (!wasActive) prootHost.stopAndAwait()
                }
                provisioner.refreshUpdateCheck()
                _state.value = AutoUpdateState.Failed(e.message ?: "自动更新异常，已回滚", rolledBack = true)
            } else {
                _state.value = AutoUpdateState.Failed(e.message ?: "自动更新失败", rolledBack = false)
            }
            false
        } finally {
            _busy.value = false
            updateMutex.unlock()
            provisioner.endPipeline()
        }
    }

    /**
     * 读取热更能力及忙闲状态；运行时不可达时返回 null。
     *
     * commitOnly 型发布只能经热更通道送达；接口不存在（太旧的运行时）或会话没在跑时
     * 返回 null，让调用方退回整包更新。服务端要联网查上游提交，读超时放宽。
     *
     * Reads hot-update capability and activity, returning null when unreachable.
     * Returns null when the API is missing or the session is not running, so the caller falls back
     * to a full update. The server queries the upstream commit over the
     * network, hence the generous read timeout.
     */
    private fun hotUpdateStatus(): JSONObject? {
        if (prootHost.state.value.phase != ProotPhase.RUNNING) return null
        return runCatching {
            val conn = URL("${com.azurpilot.ghio.proot.RuntimeConnection.endpoint.httpBase}/android/update/status")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("X-AzurPilot-Android-Token", AndroidControlAuth.get(app))
            conn.connectTimeout = 3_000
            conn.readTimeout = 45_000
            try {
                if (conn.responseCode != 200) return@runCatching null
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * 调用上游 API 安全挂起所有运行实例；失败重试一次后放弃并返回 false。
     *
     * 调用方收到 false 必须中止整包更新：suspend 同时负责写 reloadalas 复活清单，
     * 继续换目录会把运行中的调度器连根杀掉且更新后不再复活。优雅停实例在运行时侧走
     * SIGTERM→3s→SIGKILL，多实例时可能显著超过 15s，读超时放宽到 60s。
     *
     * Calls the upstream API to safely suspend all running instances; retries
     * once on failure, then gives up and returns false.
     *
     * The caller must abort the full update on false: suspend also writes the
     * reloadalas revival manifest, and proceeding with the swap would kill
     * running schedulers that will not be revived afterwards. The graceful
     * stop on the runtime side goes SIGTERM→3 s→SIGKILL per instance, which
     * can exceed 15 s with several instances, hence the 60 s read timeout.
     */
    private suspend fun suspendRuntime(): Boolean {
        repeat(2) { attempt ->
            val ok = runCatching {
                val url = URL("${com.azurpilot.ghio.proot.RuntimeConnection.endpoint.httpBase}/android/update/suspend")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-AzurPilot-Android-Token", AndroidControlAuth.get(app))
                conn.connectTimeout = 3_000
                conn.readTimeout = 60_000
                val code = conn.responseCode
                if (code == 200) {
                    val resp = conn.inputStream.use { it.readBytes().decodeToString() }
                    Timber.i("auto update: suspended instances: %s", resp)
                    true
                } else {
                    Timber.w("auto update: suspend rejected with %d", code)
                    false
                }
            }.onFailure {
                Timber.w(it, "auto update: suspend call failed (attempt %d)", attempt + 1)
            }.getOrDefault(false)
            if (ok) return true
            if (attempt == 0) delay(2_000)
        }
        return false
    }

    companion object {
        private const val TICK_INTERVAL_MS = 60_000L
        private const val HEALTH_CHECK_TIMEOUT_MS = 90_000L
        private const val ARCHIVE_NAME = "rootfs-autoupdate.tar.xz"
        private const val PREFS_NAME = "runtime_auto_updater"
        private const val KEY_LAST_CHECK_DATE = "last_check_date"
    }
}
