package com.azurpilot.ghio.proot

import android.content.Context
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.service.HostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AzurPilot 调度器运行态：悬浮窗「开始/停止挂机」与日志板的数据源
 *
 * 数据全部来自网关的 Android 薄接口（`127.0.0.1:25548`，`module/api/android.py`，
 * 需要 `X-AzurPilot-Android-Token`）：
 * - GET `android/status` → runner_alive/pid/config/gui_alive/log_lines
 * - POST `android/start?config=N`、`android/stop` → 调度器启停（幂等；stop 内部
 *   SIGTERM→3s→SIGKILL，响应偏慢）
 * - GET `android/logs?tail=N` → 纯文本日志尾。语义：按 mtime 取 log/ 下最新 txt——
 *   调度器在跑时是它的当日文件，没跑时多半是 gui 启动日志，均够悬浮窗一瞥
 * - GET `android/configs` → config/ 下的实例配置名列表（运行配置下拉的数据源）
 *
 * 4s 轮询；接口不可达不视为错误（proot 会话没起/正在起，reachable=false 即可）。
 * 运行配置选择持久化在 SharedPreferences，start 时透传给 runner；
 * 调度器在跑时 status 回报的 config 才是生效配置，下拉选择下次启动生效。
 * 控制面分工：进程的启停只走这条薄接口，原生界面只做内容面（总览 / 配置 / 日志 / 统计 / 设置），
 * 不再另开一条启停路径——两条控制面同时操作设备会互相打架。
 *
 * The AzurPilot scheduler's runtime state: data source for the overlay's
 * "start/stop farming" and the log panel.
 *
 * All data comes from the gateway's Android thin API (`127.0.0.1:25548`,
 * `module/api/android.py`, requires `X-AzurPilot-Android-Token`):
 * - GET `android/status` → runner_alive/pid/config/gui_alive/log_lines
 * - POST `android/start?config=N`, `android/stop` → scheduler start/stop
 *   (idempotent; stop does SIGTERM→3 s→SIGKILL internally, so it answers slowly)
 * - GET `android/logs?tail=N` → a plain-text log tail. Semantics: the newest
 *   txt under log/ by mtime — the scheduler's file of the day while it runs,
 *   mostly the gui startup log otherwise; either suffices for an overlay glance
 * - GET `android/configs` → the instance config names under config/ (the data
 *   source of the run-config dropdown)
 *
 * Polls every 4 s; an unreachable API is not an error (the proot session is
 * down or coming up — reachable=false suffices). The run-config choice is
 * persisted in SharedPreferences and passed through to the runner at start;
 * while the scheduler runs, the config reported by status is the effective
 * one, and a dropdown choice takes effect on the next start. Control-plane
 * split: process start/stop goes only through this thin API, while the native
 * UI handles the content surfaces (overview / config / logs / statistics /
 * settings) — no second start/stop path, two control planes acting on the
 * device at once would fight each other.
 */
data class AzurPilotRunState(
    /** 薄接口是否可达 / Whether the thin API answers. */
    val reachable: Boolean = false,
    /** 调度器 runner 是否在跑 / Whether the scheduler runner is alive. */
    val runnerAlive: Boolean = false,
    /** runner 的 pid；没跑为 null / The runner's pid; null when not running. */
    val pid: Int? = null,
    /** WebUI/gui 进程是否活着 / Whether the WebUI/gui process is alive. */
    val guiAlive: Boolean = false,
    /** 当日日志文件的行数 / Line count of the day's log file. */
    val logLines: Int = 0,
    /** 日志尾（悬浮窗展示用） / The log tail, shown on the overlay. */
    val logTail: List<String> = emptyList(),
    /** 一次启停请求在途 / A start/stop request is in flight. */
    val busy: Boolean = false,
    /** config/ 下的实例配置名列表 / The instance config names under config/. */
    val configs: List<String> = emptyList(),
    /** 下拉选中的运行配置，start 时透传 / The run-config picked in the dropdown, passed through at start. */
    val selectedConfig: String = DEFAULT_CONFIG,
    /** 正在跑的实例名（/status 回报）；没在跑为 null / The instance actually running (reported by /status); null when idle. */
    val runningConfig: String? = null,
    /** AzurPilot 工具（半自动点击/活动剧情）是否在跑（/status 回报） / Whether an AzurPilot tool (semi-auto click / event story) runs (reported by /status). */
    val toolAlive: Boolean = false,
    /** 在跑的工具名（TOOL_* 常量）；没在跑为 null / The running tool's name (the TOOL_* constants); null when idle. */
    val toolName: String? = null,
    /**
     * 最近一次轮询是否确认了任务状态；失败时其余运行字段保留上次已知值。
     *
     * Whether the latest poll confirmed task status; failed polls retain the last known run fields.
     */
    val statusKnown: Boolean = false,
) {
    companion object {
        /** 与 seed_azurpilot.py 播种的实例名一致（上游 DEFAULT_CONFIG_NAME），否则首启会选到一个不存在的配置 */
        const val DEFAULT_CONFIG = "ap"

        /** wrapper /tool/start 认识的工具名：半自动点击、活动剧情 / Tool names /tool/start understands: semi-auto click and event story. */
        const val TOOL_SEMI_AUTO = "daemon"
        const val TOOL_EVENT_STORY = "event_story"
    }
}

/**
 * 运行时热更状态快照（/android/update/status 的解析结果）
 *
 * Snapshot of the runtime hot-update state (parsed from /android/update/status).
 */
data class HotUpdateState(
    /** 运行时侧是否具备热更条件（已注入 dist 下载基址）/ Whether the runtime can hot-update (dist base URL injected). */
    val enabled: Boolean = false,
    /** 当前源码提交 / The current source commit. */
    val localHead: String? = null,
    /** 上游分支头提交 / The upstream branch-head commit. */
    val upstreamHead: String? = null,
    /** 有可用更新（上游更新且其预构建前端已发布）/ An update is available (upstream moved and its prebuilt frontend is published). */
    val available: Boolean = false,
    /** 上游头的预构建 dist 是否已发布 / Whether the upstream head's prebuilt dist is published. */
    val distReady: Boolean = false,
    /** 更新事务进行中 / An update transaction is in flight. */
    val busy: Boolean = false,
    /** 当前阶段（git/dist/manifest/reload）/ The current phase (git/dist/manifest/reload). */
    val phase: String? = null,
    /** 上次失败的错误信息 / The last failure message. */
    val error: String = "",
)

/**
 * AzurPilot 调度器与工具的运行态控制器
 *
 * 4s 轮询薄接口产出 [AzurPilotRunState] 给悬浮窗与日志板，并暴露调度器/工具的启停：
 * 进程的启停只走这条薄接口（内容面归 [AzurPilotRepository]，见其类头的分工说明）。
 * 每个请求都带 [AndroidControlAuth] 口令；轮询与请求全部在 IO 调度器上，
 * [start] 挂在 App 生命周期上整个前台期间持续轮询。
 *
 * Controller for the AzurPilot scheduler and tool runtime state.
 *
 * Polls the thin API every 4 s to produce [AzurPilotRunState] for the overlay
 * and the log panel, and exposes scheduler/tool start and stop: process
 * control goes only through this thin API (content surfaces belong to
 * [AzurPilotRepository] — see its class doc for the split). Every request
 * carries the [AndroidControlAuth] token; polling and requests all run on the
 * IO dispatcher, with [start] pinned to the app lifecycle so polling lasts the
 * whole foreground period.
 */
class AzurPilotRunController(
    context: Context,
    private val scope: CoroutineScope,
    private val hostState: HostState,
    private val settings: com.azurpilot.ghio.settings.AppSettingsManager,
    private val autoUpdateBusy: StateFlow<Boolean> = MutableStateFlow(false),
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val controlToken = AndroidControlAuth.get(context)

    private val _state = MutableStateFlow(
        AzurPilotRunState(
            selectedConfig = prefs.getString(KEY_SELECTED_CONFIG, AzurPilotRunState.DEFAULT_CONFIG)
                ?: AzurPilotRunState.DEFAULT_CONFIG,
        )
    )

    /** 对外只读的运行态 / The externally read-only runtime state. */
    val state = _state.asStateFlow()

    private val _hotUpdate = MutableStateFlow<HotUpdateState?>(null)

    /** 对外只读的热更状态；null = 尚未获取 / The externally read-only hot-update state; null = not fetched yet. */
    val hotUpdate = _hotUpdate.asStateFlow()

    private val started = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    private var lastHotUpdateCheck = 0L

    /**
     * 幂等：挂到 AzurPilotApp.postCreate，轮询整个 App 生命周期
     *
     * Idempotent; hooked at AzurPilotApp.postCreate so polling lasts the whole
     * app lifecycle.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(AppDispatchers.IO) {
            while (true) {
                refreshMutex.withLock { refreshLocked() }
                hotUpdateTick()
                delay(POLL_MS)
            }
        }
    }

    /**
     * 热更慢频 Tick：按 [HOT_UPDATE_CHECK_MS] 间隔刷一次 /android/update/status；
     * 有可用更新且调度器与工具都空闲时自动应用（运行中的任务不打断）。
     * 开关关闭或接口不可达时静默跳过。
     *
     * The slow hot-update tick: refreshes /android/update/status every
     * [HOT_UPDATE_CHECK_MS]; applies automatically when an update is available
     * and both the scheduler and tools are idle (running tasks are never
     * interrupted). Silently skipped when the toggle is off or the API is
     * unreachable.
     */
    private suspend fun hotUpdateTick() {
        if (!settings.hotUpdateEnabled.value) return
        if (autoUpdateBusy.value) return
        val now = System.currentTimeMillis()
        if (now - lastHotUpdateCheck < HOT_UPDATE_CHECK_MS) return
        lastHotUpdateCheck = now
        val status = fetchHotUpdateState() ?: return
        _hotUpdate.value = status
        val run = _state.value
        if (run.statusKnown && status.available && !status.busy && !run.runnerAlive && !run.toolAlive) {
            Timber.i("hot update: auto-applying %s", status.upstreamHead)
            applyHotUpdate()
        }
    }

    /** 拉取并解析 /android/update/status；不可达返回 null（保留上次已知态） / Fetches and parses /android/update/status; null when unreachable (the last known state is kept). */
    private fun fetchHotUpdateState(): HotUpdateState? = parseHotUpdate(
        // 服务端 status() 要联网查上游提交并探测 dist，1.5s 探针超时必然不够
        // （实测真机每次 read timeout），热更状态请求单独放宽
        get("$BASE/update/status", HOT_UPDATE_HTTP_TIMEOUT_MS),
    )

    /** 手动刷新热更状态（设置页「检查更新」按钮） / Manually refreshes the hot-update status (the settings "check" button). */
    fun refreshHotUpdate() {
        scope.launch(AppDispatchers.IO) {
            fetchHotUpdateState()?.let { _hotUpdate.value = it }
        }
    }

    private fun parseHotUpdate(body: String?): HotUpdateState? = body?.let {
        runCatching {
            val j = JSONObject(it)
            HotUpdateState(
                enabled = j.optBoolean("enabled"),
                localHead = j.optStringOrNull("localHead"),
                upstreamHead = j.optStringOrNull("upstreamHead"),
                available = j.optBoolean("available"),
                distReady = j.optBoolean("distReady"),
                busy = j.optBoolean("busy"),
                phase = j.optStringOrNull("phase"),
                error = j.optString("error"),
            )
        }.onFailure { Timber.d(it, "hot update status parse failed") }.getOrNull()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    /**
     * 手动触发热更：POST /android/update/apply 后按 3s 轮询阶段直到收尾；
     * 重启间隙接口不可达不终止轮询，以 30 分钟为上限。
     *
     * Triggers a hot update manually: POSTs /android/update/apply, then polls
     * the phase every 3 s until it settles; unreachable windows during the
     * WebUI restart keep the poll alive, capped at 30 minutes.
     */
    fun applyHotUpdate() {
        scope.launch(AppDispatchers.IO) {
            runCatching {
                val conn = URL("$BASE/update/apply").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-AzurPilot-Android-Token", controlToken)
                conn.connectTimeout = HTTP_TIMEOUT_MS
                conn.readTimeout = POST_READ_TIMEOUT_MS
                if (conn.responseCode != 200) {
                    Timber.w("hot update apply rejected: %s", conn.responseCode)
                    return@launch
                }
                conn.inputStream.use { it.readBytes() }
            }.onFailure { Timber.w(it, "hot update apply failed") }
            var guard = 0
            while (guard++ < HOT_UPDATE_POLL_LIMIT) {
                delay(3_000)
                val status = fetchHotUpdateState() ?: continue
                _hotUpdate.value = status
                if (!status.busy) break
            }
        }
    }

    /**
     * 选择运行配置：持久化，下次 /start 生效；调度器在跑时不拦，但生效要等下次启动
     *
     * Picks the run config: persisted, effective on the next /start; not
     * blocked while the scheduler runs, but it applies only from the next start.
     */
    fun selectConfig(name: String) {
        prefs.edit().putString(KEY_SELECTED_CONFIG, name).apply()
        _state.update { it.copy(selectedConfig = name) }
    }

    /** 启动调度器（带下拉选中的配置）；环境未就绪时先拉环境 / Starts the scheduler (with the picked config); brings the environment up first when it is not ready. */
    fun startRunner() {
        val config = URLEncoder.encode(_state.value.selectedConfig, "UTF-8")
        startAfterEnvironmentReady("$BASE/start?config=$config")
    }

    /**
     * 停止调度器：**必须显式带 config**
     *
     * 上游 `instance()` 在没给 config 时回落到硬编码的实例名再做 `configs.path()` 校验，
     * 那个名字在本部署里不存在 → 400 NOT_FOUND → 表现就是「点了停止没反应」。
     * 正在跑时以 /status 回报的实例为准，否则退回下拉选中的那个。
     *
     * Stops the scheduler: **the config must be passed explicitly**.
     *
     * Without a config, upstream `instance()` falls back to a hardcoded
     * instance name and then validates it through `configs.path()`; that name
     * does not exist in this deployment → 400 NOT_FOUND → the user sees
     * "pressed stop, nothing happened". While running, the instance reported
     * by /status wins; otherwise the dropdown's pick is used.
     */
    fun stopRunner() = postThenRefresh("$BASE/stop?config=${encodedConfig(running = true)}")

    /**
     * 工具启停：与调度器同一条 postThenRefresh 通道。
     * 互斥（启工具先停 runner、启 runner 先停工具）由 wrapper 集中执行，这里不做门控
     *
     * Tool start/stop: the same postThenRefresh channel as the scheduler.
     * Mutual exclusion (starting a tool stops the runner first and vice versa)
     * is enforced centrally by the wrapper; no gating here.
     */
    fun startTool(name: String) {
        val tool = URLEncoder.encode(name, "UTF-8")
        startAfterEnvironmentReady("$BASE/tool/start?name=$tool&config=${encodedConfig()}")
    }

    /** 同 [stopRunner]：不带 config 会落到上游那个必然不存在的回落实例名上 / Same as [stopRunner]: without a config it lands on upstream's fallback instance name that cannot exist. */
    fun stopTool() = postThenRefresh("$BASE/tool/stop?config=${encodedConfig(running = true)}")

    /**
     * 当前该对哪个实例说话：优先 /status 回报的在跑实例，否则用下拉选中项
     *
     * Which instance to talk to right now: the instance reported running by
     * /status takes precedence, otherwise the dropdown's pick.
     */
    private fun encodedConfig(running: Boolean = false): String {
        val state = _state.value
        val name = if (running) state.runningConfig ?: state.selectedConfig else state.selectedConfig
        return URLEncoder.encode(name, "UTF-8")
    }

    /** 先确保特权环境（虚拟屏）就绪再发请求；环境起不来就放弃并记日志 / Fires the request only after the privileged environment (virtual display) is up; gives up with a log line when it cannot start. */
    private fun startAfterEnvironmentReady(url: String) {
        scope.launch {
            _state.update { it.copy(busy = true) }
            if (!hostState.ensureEnvironmentStarted()) {
                Timber.w("refusing AzurPilot start: Android environment is unavailable")
                _state.update { it.copy(busy = false) }
                return@launch
            }
            postThenRefresh(url)
        }
    }

    /** POST + 立刻刷新一次状态；失败只记日志，可达性由刷新兜底 / POSTs and immediately refreshes the state; failures only log — the refresh picks up reachability. */
    private fun postThenRefresh(url: String) {
        scope.launch(AppDispatchers.IO) {
            _state.update { it.copy(busy = true) }
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-AzurPilot-Android-Token", controlToken)
                conn.connectTimeout = HTTP_TIMEOUT_MS
                // /stop 要等进程组死掉（SIGTERM→3s→SIGKILL），读超时给足
                conn.readTimeout = POST_READ_TIMEOUT_MS
                conn.inputStream.use { it.readBytes() }
            }.onFailure { Timber.w(it, "azurpilot POST %s failed", url) }
            refreshMutex.withLock { refreshLocked() }
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * 一次刷新
     *
     * 顺序有讲究：`/configs` 不解析实例（直接回 `config/` 下的文件名列表），因此它是
     * 「WebUI 进程活着」最可靠的探针——首次部署、实例还没播种、调度器没起时它照样能答。
     * 先用它定可达性并把选中的实例自愈到列表里，后面带 `config=` 的调用才不会撞上
     * 上游那个必然不存在的回落实例名。
     *
     * One refresh.
     *
     * The order matters: `/configs` parses no instance (it returns the file
     * names under `config/` directly), making it the most reliable probe of
     * "the WebUI process is alive" — it answers even on first deploy, before
     * instances are seeded, with the scheduler down. Reachability is decided
     * with it first and the selected config self-heals into the list, so the
     * later `config=`-bearing calls never hit upstream's fallback instance name
     * that cannot exist.
     */
    private fun refreshLocked() {
        val configs = runCatching {
            val arr = JSONObject(get("$BASE/configs", HTTP_TIMEOUT_MS) ?: return@runCatching null)
                .getJSONArray("configs")
            List(arr.length()) { arr.getString(it) }
        }.getOrNull()
        if (configs == null) {
            _state.update {
                it.copy(
                    reachable = false, statusKnown = false, configs = emptyList(),
                )
            }
            return
        }
        // 持久化的选择可能已被 WebUI 删掉；列表非空时自愈回第一项
        if (configs.isNotEmpty() && _state.value.selectedConfig !in configs) {
            selectConfig(configs.first())
        }

        val body = get("$BASE/status?config=${encodedConfig()}", HTTP_TIMEOUT_MS)
        val j = body?.let {
            runCatching {
                JSONObject(it).also { status ->
                    // 缺失或损坏的任务状态不能用 optBoolean 的默认 false 冒充已停止。
                    status.getBoolean("runner_alive")
                    if (status.has("tool_alive")) status.getBoolean("tool_alive")
                }
            }.getOrNull()
        }
        if (j == null) {
            // WebUI 可达不等于任务已停止；超时、异常响应和解析失败都保留上次运行态。
            _state.update {
                it.copy(reachable = true, statusKnown = false, configs = configs)
            }
            return
        }
        val runnerAlive = j.optBoolean("runner_alive")
        val pid = if (j.isNull("pid")) null else j.optInt("pid")
        val runningConfig = if (j.isNull("config")) null else j.optString("config")
        val toolAlive = j.optBoolean("tool_alive")
        val toolName = if (j.isNull("tool_name")) null else j.optString("tool_name")
        val guiAlive = j.optBoolean("gui_alive")
        val logLines = j.optInt("log_lines")
        // 日志同样要带 config：不带的话上游会去解析那个不存在的回落实例名
        val tail = get("$BASE/logs?tail=$LOG_TAIL&config=${encodedConfig()}", HTTP_TIMEOUT_MS)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: _state.value.logTail
        _state.update {
            it.copy(
                reachable = true, statusKnown = true, runnerAlive = runnerAlive, pid = pid,
                guiAlive = guiAlive, logLines = logLines, logTail = tail,
                configs = configs, runningConfig = runningConfig,
                toolAlive = toolAlive, toolName = toolName,
            )
        }
    }

    /** 带口令的 GET；非 200 或任何异常都以 null 收场 / A token-carrying GET; a non-200 or any exception ends in null. */
    private fun get(url: String, timeoutMs: Int): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("X-AzurPilot-Android-Token", controlToken)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        if (conn.responseCode != 200) return null
        conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
    }.onFailure { Timber.d(it, "azurpilot GET %s failed", url) }.getOrNull()

    private companion object {
        const val BASE = "http://127.0.0.1:${ProotHost.WEBUI_PORT}/android"

        /** 轮询周期：悬浮窗数字跳动别太快，又要在 4s 内跟手 / Poll period: keeps overlay numbers from flickering yet still feels responsive. */
        const val POLL_MS = 4_000L

        /** GET 探针的超时：探活要快，超时即按不可达处理 / GET probe timeout: probing must be quick; a timeout counts as unreachable. */
        const val HTTP_TIMEOUT_MS = 1_500

        /** /stop 要等进程组死掉（SIGTERM→3s→SIGKILL），读超时给足 / /stop waits for the process group to die (SIGTERM→3 s→SIGKILL); the read timeout must cover it. */
        const val POST_READ_TIMEOUT_MS = 12_000

        /** 悬浮窗展示的日志尾行数 / Log tail lines shown on the overlay. */
        const val LOG_TAIL = 50

        /** 热更状态检查间隔 / Interval between hot-update status checks. */
        const val HOT_UPDATE_CHECK_MS = 30 * 60_000L

        /**
         * 热更状态 GET 的超时：服务端 status() 要联网查上游提交并探测 dist 资产，
         * 不同于毫秒级的本地探针 / Timeout for the hot-update status GET: the
         * server side queries the upstream commit and probes the dist asset
         * over the network, unlike the millisecond-level local probes.
         */
        const val HOT_UPDATE_HTTP_TIMEOUT_MS = 12_000

        /** 手动热更后的轮询上限：600 × 3s = 30 分钟 / Poll cap after a manual apply: 600 × 3 s = 30 minutes. */
        const val HOT_UPDATE_POLL_LIMIT = 600

        const val PREFS_NAME = "azurpilot_android"
        const val KEY_SELECTED_CONFIG = "selected_config"
    }
}
