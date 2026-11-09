package com.azurpilot.ghio.proot

import android.graphics.BitmapFactory
import android.util.Base64
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.remoteaccess.RemotePairing
import kotlinx.coroutines.cancelAndJoin
import com.azurpilot.ghio.i18n.AppLocales
import com.azurpilot.ghio.settings.AppSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 网关状态在 App 侧的唯一归属
 *
 * WebUI 前端是「一条 WS + 一个全局 store」，这里照同样的分工：**订阅只有它一处发出**
 * （`events.subscribe` 是整体替换语义，两个地方各订一次会互相顶掉），事件也只有它一处消费，
 * 再按主题拆成一组 StateFlow 给界面用。
 *
 * 与 [AzurPilotRunController] 的分工：那个走 `/android/…` 薄接口，管「起停进程 + 日志尾」，
 * 悬浮窗和主页控制面板要用；这里补上「总览 / 配置 / 统计 / 设置」这些真正的内容面。
 *
 * The single app-side home of the gateway's state.
 *
 * The WebUI frontend is "one WebSocket + one global store"; the same split is
 * mirrored here: **subscriptions are issued from this one place only**
 * (`events.subscribe` replaces wholesale — two subscribers would knock each
 * other out), events are consumed here alone, and then split by topic into a
 * set of StateFlows for the UI.
 *
 * Division of labor with [AzurPilotRunController]: that one goes through the
 * `/android/…` thin API and manages "process start/stop + log tail" for the
 * overlay and the home control panel; this one supplies the real content
 * surfaces — overview / config / statistics / settings.
 */
class AzurPilotRepository(
    private val scope: CoroutineScope,
    private val gateway: AzurPilotGateway,
    private val store: AzurPilotPreferenceStore,
    private val settings: AppSettingsManager,
) {

    private val _connected = MutableStateFlow(false)

    /** 网关长连接是否存活（自轮询同步） / Whether the gateway WebSocket is alive (synced by polling). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * 网关要求先登录（本机直连时为 false）；为真时界面出登录页
     *
     * The gateway demands a login first (false for local direct connections);
     * the UI shows a login page while true.
     */
    private val _authRequired = MutableStateFlow(false)
    val authRequired: StateFlow<Boolean> = _authRequired.asStateFlow()

    /** 当前浏览的实例名；null 表示未选（此时只订与实例无关的主题） / The instance being browsed; null means none selected (only instance-independent topics are subscribed then). */
    private val _selectedInstance = MutableStateFlow<String?>(AzurPilotRunState.DEFAULT_CONFIG)
    /** 原生控制台当前连接的 AP 名称。 / AP connected to the native console. */
    val remoteName = gateway.remoteName
    /** 原生页面的来源缓存键。 / Source key for native page state. */
    val sourceId = gateway.sourceId
    /** 当前是否连接远程 AP。 / Whether the console connects to a remote AP. */
    val isRemote: Boolean get() = gateway.isRemote

    /**
     * 保存编辑后切换 AP，清理旧来源的缓存和订阅；本机 PRoot 及悬浮窗保持原连接。
     *
     * Settles edits and switches AP sources, clearing cached data without retargeting local PRoot.
     */
    fun connectTarget(pairing: RemotePairing?, onDone: (Boolean) -> Unit) {
        scope.launch(AppDispatchers.IO) {
            if (!configEditor.settle()) {
                withContext(Dispatchers.Main) { onDone(false) }
                return@launch
            }
            gateway.stop()
            schemaJob?.cancelAndJoin()
            updaterJob?.cancelAndJoin()
            updaterJob = null
            refreshMutex.withLock {
                configEditor.reset()
                _connected.value = false
                _authRequired.value = false
                _schema.value = null
                schemaLanguage = null
                _schemaError.value = null
                _selectedInstance.value = if (pairing == null) AzurPilotRunState.DEFAULT_CONFIG else null
                _instances.value = emptyList()
                _overview.value = null
                _startup.value = null
                _logs.value = emptyList()
                _preview.value = null
                _deploy.value = null
                _updater.value = null
                _announcement.value = null
                _meowfficer.value = null
                logCursor = 0
                lastSubscription = null
                _schedulerBusy.value = false
                gateway.selectTarget(pairing)
            }
            withContext(Dispatchers.Main) { onDone(true) }
        }
    }
    val selectedInstance: StateFlow<String?> = _selectedInstance.asStateFlow()

    private val _schema = MutableStateFlow<AzurPilotSchema?>(null)

    /** 界面骨架；null 表示还没拉到 / The UI skeleton; null until first fetched. */
    val schema: StateFlow<AzurPilotSchema?> = _schema.asStateFlow()

    private val _schemaError = MutableStateFlow<String?>(null)

    /** 最近一次骨架拉取失败的文案 / The last skeleton fetch failure, as text. */
    val schemaError: StateFlow<String?> = _schemaError.asStateFlow()

    /** schema 当前语言；语言没变时不重复拉取 / The schema's current language; no refetch until it changes. */
    private var schemaLanguage: String? = null
    private var schemaJob: Job? = null

    private val _instances = MutableStateFlow<List<AzurPilotInstance>>(emptyList())

    /** 实例列表（`instances` 主题推送 + 首次 `instances.list`） / The instance list (`instances` topic push plus the first `instances.list`). */
    val instances: StateFlow<List<AzurPilotInstance>> = _instances.asStateFlow()

    private val _overview = MutableStateFlow<AzurPilotOverview?>(null)

    /** 当前实例的总览快照 / The current instance's overview snapshot. */
    val overview: StateFlow<AzurPilotOverview?> = _overview.asStateFlow()

    private val _startup = MutableStateFlow<AzurPilotStartup?>(null)

    /** 当前实例的开机自启设置 / The current instance's auto-start settings. */
    val startup: StateFlow<AzurPilotStartup?> = _startup.asStateFlow()

    private val _logs = MutableStateFlow<List<AzurPilotLogEntry>>(emptyList())

    /** 日志板（增量推送 + 重连补拉，按 id 去重，最多 [LOG_LIMIT] 条） / The log panel (incremental pushes plus reconnect backfill, deduped by id, capped at [LOG_LIMIT]). */
    val logs: StateFlow<List<AzurPilotLogEntry>> = _logs.asStateFlow()

    private val _preview = MutableStateFlow<AzurPilotPreview?>(null)

    /** 截图预览帧 / The screenshot preview frame. */
    val preview: StateFlow<AzurPilotPreview?> = _preview.asStateFlow()

    private val _deploy = MutableStateFlow<AzurPilotDeploySettings?>(null)

    /** 部署设置页数据 / The deploy settings page data. */
    val deploySettings: StateFlow<AzurPilotDeploySettings?> = _deploy.asStateFlow()

    private val _updater = MutableStateFlow<AzurPilotUpdateStatus?>(null)

    /** 运行时更新器状态 / The runtime updater status. */
    val updater: StateFlow<AzurPilotUpdateStatus?> = _updater.asStateFlow()

    private val _announcement = MutableStateFlow<AzurPilotAnnouncement?>(null)

    /** 运行时公告（弹一次） / The runtime announcement (shown once). */
    val announcement: StateFlow<AzurPilotAnnouncement?> = _announcement.asStateFlow()

    private val _meowfficer = MutableStateFlow<AzurPilotMeowfficerReport?>(null)

    /** 指挥喵评分报告 / The meowfficer scoring report. */
    val meowfficer: StateFlow<AzurPilotMeowfficerReport?> = _meowfficer.asStateFlow()

    /**
     * 统计数据有变（网关只在指纹变化时推）；界面收到就重取当前分类
     *
     * Statistics data changed (the gateway pushes only when its fingerprint
     * changes); the UI refetches the current category on receipt.
     */
    private val _statisticsTick = MutableStateFlow(0L)
    val statisticsTick: StateFlow<Long> = _statisticsTick.asStateFlow()

    /**
     * 界面上的瞬时提示（运行/停止/保存失败等），一次性消费
     *
     * Transient toasts for the UI (run/stop/save failure and the like),
     * consumed once.
     */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    /**
     * 调度器启停正在进行
     *
     * stop 在服务端要等进程真正退出（最长 30s）才回话，这期间按钮必须自己禁用——
     * 重复点会连发好几条 stop，而且界面上分不出哪一次是「新的」。
     */
    private val _schedulerBusy = MutableStateFlow(false)
    val schedulerBusy: StateFlow<Boolean> = _schedulerBusy.asStateFlow()

    /** 配置编辑器：自动保存队列与本地在途值都在它里面 / The config editor: the autosave queue and local in-flight values live in it. */
    val configEditor = AzurPilotConfigEditor(scope, gateway)

    private var eventsJob: Job? = null
    private var pollJob: Job? = null
    private var updaterJob: Job? = null
    private val refreshMutex = Mutex()

    /**
     * 最近一次日志游标；`logs.get` 增量拉取与事件合并共用它
     *
     * The last log cursor; shared by incremental `logs.get` backfill and event
     * merging.
     */
    private var logCursor = 0L

    /**
     * 启动事件消费与轮询；幂等，重复调用直接返回
     *
     * Starts event consumption and polling; idempotent, repeat calls return at
     * once. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    fun start() {
        if (eventsJob == null) {
            eventsJob = scope.launch(AppDispatchers.IO) {
                gateway.events.collect(::onEvent)
            }
        }
        if (pollJob == null) {
            pollJob = scope.launch(AppDispatchers.IO) {
                while (true) {
                    val connected = gateway.connected.value
                    if (connected != _connected.value) {
                        _connected.value = connected
                        if (connected) onConnected()
                    }
                    _authRequired.value = gateway.authRequired.value
                    if (connected) refreshMutex.withLock { refreshLocked() }
                    delay(POLL_MS)
                }
            }
        }
    }

    private fun onConnected() {
        scope.launch(AppDispatchers.IO) {
            ensureSchema()
            syncSubscription(force = true)
        }
    }

    /**
     * 把订阅对齐到「当前实例 + 全部主题」
     *
     * 四个主题一起订：日志与截图的推送只在订阅期间产生，等到打开对应页面再订会先空一段时间；
     * 而它们本身是按需产出的，常订不产生额外负载。
     *
     * Aligns the subscription to "current instance + all topics".
     *
     * All four topics subscribe together: log and screenshot pushes exist only
     * while subscribed, so subscribing after the page opens leaves it empty for
     * a while first; they are produced on demand anyway, so a standing
     * subscription costs nothing extra.
     */
    private suspend fun syncSubscription(force: Boolean = false) {
        if (!gateway.connected.value) return
        val instance = _selectedInstance.value
        val topics = wantedTopics(instance)
        if (!force && topics == lastSubscription?.second && instance == lastSubscription?.first) return
        lastSubscription = instance to topics
        when (val reply = gateway.subscribe(instance, topics)) {
            is AzurPilotGateway.Reply.Ok -> {
                logCursor = 0L
                if (instance != null) loadLogsLocked(instance, after = 0)
            }

            is AzurPilotGateway.Reply.Err -> Timber.w("subscribe failed: %s", reply.message)
            AzurPilotGateway.Reply.Offline -> Unit
        }
    }

    private var lastSubscription: Pair<String?, List<String>>? = null

    /**
     * 该订哪些主题
     *
     * 界面上看不见时把 `preview` 去掉：截图帧是 base64 的大包，而它只在订阅期间才推——
     * 停在别的主页时还照收，纯属白耗流量与内存。
     *
     * Which topics should be subscribed.
     *
     * `preview` drops out while the UI cannot see it: a screenshot frame is a
     * large base64 packet pushed only while subscribed — receiving it on some
     * other page burns data and memory for nothing.
     */
    private fun wantedTopics(instance: String?): List<String> = when {
        instance == null -> listOf(TOPIC_INSTANCES)
        foreground -> TOPICS
        else -> TOPICS.filterNot { it == TOPIC_PREVIEW }
    }

    /** 界面是否可见；离开时截图订阅会摘掉 / Whether the UI is visible; the screenshot subscription drops while away. */
    @Volatile
    private var foreground = true

    /**
     * 切换浏览的实例：清空所有实例相关数据并对齐订阅
     *
     * Switches the browsed instance: clears every instance-bound piece of data
     * and realigns the subscription. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    fun selectInstance(name: String?) {
        // Android 只呈现默认实例；保留后台兼容接口但不再允许切换到其他实例。
        if (!gateway.isRemote && name != AzurPilotRunState.DEFAULT_CONFIG) return
        if (gateway.isRemote && name != null && _instances.value.none { it.name == name }) return
        if (_selectedInstance.value == name) return
        _selectedInstance.value = name
        if (!gateway.isRemote) store.selectedInstance = name
        _overview.value = null
        _startup.value = null
        _logs.value = emptyList()
        _preview.value = null
        logCursor = 0L
        // 在途标记跟着实例走，否则切走再切回来会卡在禁用态
        _schedulerBusy.value = false
        configEditor.reset()
        scope.launch(AppDispatchers.IO) {
            lastSubscription = null
            syncSubscription(force = true)
        }
    }

    /**
     * 界面语言对应的 schema 语言；语言切换后下一次拉取自动换成新语言
     *
     * The schema language for the UI language; after a switch the next fetch
     * picks up the new one automatically.
     */
    private fun currentSchemaLanguage(): String = when (AppLocales.currentTag()?.lowercase()) {
        "en" -> "en-US"
        "ja" -> "ja-JP"
        "zh-tw" -> "zh-TW"
        else -> "zh-CN"
    }

    /**
     * 拉取界面骨架；同语言且已有缓存时跳过（除非 [force]）
     *
     * Fetches the UI skeleton; skipped when the language is unchanged and a
     * copy is cached (unless [force]). IO 调度器上执行 / Runs on the IO dispatcher.
     */
    fun ensureSchema(force: Boolean = false) {
        val language = currentSchemaLanguage()
        if (!force && schemaLanguage == language && _schema.value != null) return
        if (!force && schemaJob?.isActive == true) return
        schemaJob = scope.launch(AppDispatchers.IO) {
            when (val reply = gateway.call("schema.get", JSONObject().put("language", language))) {
                is AzurPilotGateway.Reply.Ok -> {
                    schemaLanguage = language
                    _schema.value = AzurPilotSchema.from(reply.obj)
                    _schemaError.value = null
                }

                is AzurPilotGateway.Reply.Err -> _schemaError.value = reply.message
                AzurPilotGateway.Reply.Offline -> Unit
            }
        }
    }

    /** 按主题分发事件；实例相关主题先过滤，别的数据直接落对应流 / Dispatches events by topic; instance-bound topics filter first, the rest land directly in their flows. */
    private fun onEvent(event: JSONObject) {
        if (event.optString("_gatewaySourceId") != gateway.sourceId.value) return
        when (event.optString("topic")) {
            "instances" -> {
                val list = event.optJSONArray("data")
                _instances.value = parseInstances(list)
                if (gateway.isRemote && _instances.value.none { it.name == _selectedInstance.value }) {
                    selectInstance(_instances.value.firstOrNull()?.name)
                }
            }

            "overview" -> applyOverview(event.optJSONObject("data"))

            "logs" -> applyLogs(event.optJSONObject("data"))

            "preview" -> applyPreview(event.optJSONObject("data"))

            "statistics" -> {
                val instance = event.optJSONObject("data")?.optText("instance")
                if (instance == null || instance == _selectedInstance.value) {
                    _statisticsTick.update { it + 1 }
                }
            }
        }
    }

    private fun parseInstances(array: JSONArray?): List<AzurPilotInstance> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@List null
            val name = item.optString("name")
            if (name.isEmpty()) null else AzurPilotInstance(
                name = name,
                status = AzurPilotStatus.of(item.optString("status")),
                currentTask = item.optText("currentTask"),
                serial = item.optString("serial"),
                server = item.optString("server"),
            )
        }.filterNotNull()
    }

    /**
     * 落一份总览；别的实例的总览直接丢弃
     *
     * Applies one overview; overviews of other instances are dropped outright.
     */
    private fun applyOverview(data: JSONObject?) {
        if (data == null) return
        val instance = data.optString("instance")
        if (instance.isNotEmpty() && instance != _selectedInstance.value) return
        val tasks = data.optJSONArray("tasks")?.let { array ->
            List(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@List null
                AzurPilotTask(
                    name = item.optString("name"),
                    nextRun = item.optString("nextRun"),
                    pending = item.optBoolean("pending"),
                    state = AzurPilotTaskState.of(item.optString("state")),
                )
            }.filterNotNull()
        }.orEmpty()
        val resources = data.optJSONArray("resources")?.let { array ->
            List(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@List null
                AzurPilotResource(
                    name = item.optString("name"),
                    label = item.optString("label"),
                    value = (item.opt("value") as? Number)?.toDouble(),
                    limit = (item.opt("limit") as? Number)?.toDouble(),
                    total = (item.opt("total") as? Number)?.toDouble(),
                    record = item.optText("record"),
                )
            }.filterNotNull()
        }.orEmpty()
        _overview.value = AzurPilotOverview(
            instance = instance,
            revision = data.optString("revision"),
            status = AzurPilotStatus.of(data.optString("status")),
            tasks = tasks,
            resources = resources,
        )
    }

    /**
     * 落一批日志：`reset` 整体替换，否则按 id 增量去重合并
     *
     * Applies one batch of logs: `reset` replaces wholesale, otherwise the
     * batch merges incrementally, deduped by id.
     */
    private fun applyLogs(data: JSONObject?) {
        if (data == null) return
        val instance = data.optString("instance")
        if (instance.isNotEmpty() && instance != _selectedInstance.value) return
        val entries = data.optJSONArray("entries")?.let(::parseLogEntries).orEmpty()
        val cursor = data.optLong("cursor", 0L)
        if (data.optBoolean("reset")) {
            _logs.value = entries.takeLast(LOG_LIMIT)
            logCursor = cursor
            return
        }
        if (entries.isEmpty()) {
            logCursor = maxOf(logCursor, cursor)
            return
        }
        // 增量按 id 去重：重连后 `logs.get` 的补拉与推送可能重复同一批
        val known = _logs.value.lastOrNull()?.id ?: Long.MIN_VALUE
        val fresh = entries.filter { it.id > known }
        if (fresh.isEmpty()) {
            logCursor = maxOf(logCursor, cursor)
            return
        }
        _logs.value = (_logs.value + fresh).takeLast(LOG_LIMIT)
        logCursor = maxOf(logCursor, cursor)
    }

    private fun parseLogEntries(array: JSONArray): List<AzurPilotLogEntry> =
        List(array.length()) { index ->
            val item = array.optJSONObject(index) ?: return@List null
            AzurPilotLogEntry(
                id = item.optLong("id"),
                level = item.optString("level", "INFO"),
                text = item.optString("text"),
            )
        }.filterNotNull()

    /**
     * 落一帧截图；base64 解码在 Default 调度器上做，别把事件线程卡住
     *
     * Applies one screenshot frame; the base64 decode runs on the Default
     * dispatcher so the event thread never stalls.
     */
    private fun applyPreview(data: JSONObject?) {
        if (data == null) return
        val instance = data.optString("instance")
        if (instance.isNotEmpty() && instance != _selectedInstance.value) return
        val raw = data.optText("image")
        val capturedAt = data.optText("capturedAt")
        val runId = data.optText("runId")
        if (raw == null) {
            _preview.value = AzurPilotPreview(capturedAt, runId, null)
            return
        }
        scope.launch(AppDispatchers.Default) {
            val bitmap = decodeDataUri(raw)
            _preview.value = AzurPilotPreview(capturedAt, runId, bitmap)
        }
    }

    private fun decodeDataUri(value: String): android.graphics.Bitmap? = runCatching {
        val payload = value.substringAfter("base64,", value)
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** 消费掉当前瞬时提示 / Dismisses the current transient message. */
    fun dismissMessage() {
        _messages.value = null
    }

    /** 塞一条瞬时提示给界面 / Posts one transient message to the UI. */
    private fun report(text: String) {
        _messages.value = text
    }

    /**
     * 网关之外的场景（如导入时读本地文件失败）也走同一条瞬时提示
     *
     * Scenarios beyond the gateway (e.g. failing to read a local file during
     * import) surface through the same transient message.
     */
    fun reportTransient(text: String) {
        report(text)
    }

    private suspend fun loadLogsLocked(instance: String, after: Long) {
        val reply = gateway.call(
            "logs.get",
            JSONObject().put("instance", instance).put("after", after),
        )
        if (reply is AzurPilotGateway.Reply.Ok) applyLogs(reply.obj)
    }

    /**
     * 一轮周期刷新：骨架、更新器、部署设置、实例列表、总览与自启设置逐个补齐
     *
     * One polling pass: skeleton, updater, deploy settings, instance list,
     * overview and auto-start settings each topped up in turn.
     */
    private suspend fun refreshLocked() {
        if (!gateway.connected.value) return
        ensureSchema()
        // 运行时版本与实例无关，设置页没进过更新页也要能显示
        if (_updater.value == null) refreshUpdater()
        // 同理：部署设置卡在用「共 N 组」当副标题，不先拉一次就永远停在「正在读取…」
        if (_deploy.value == null) loadDeploySettings()
        if (_instances.value.isEmpty()) {
            // 协议里这是个裸数组，不是 {"instances": [...]}
            (gateway.call("instances.list") as? AzurPilotGateway.Reply.Ok)?.let { reply ->
                _instances.value = parseInstances(reply.array)
                if (gateway.isRemote && _selectedInstance.value == null) {
                    selectInstance(_instances.value.firstOrNull()?.name)
                }
            }
        }
        // 订阅可能因重连丢失：每次刷新补订一次（相同订阅会被 syncSubscription 短路）
        syncSubscription()
        val instance = _selectedInstance.value ?: return
        (gateway.call("overview.get", JSONObject().put("instance", instance)) as? AzurPilotGateway.Reply.Ok)
            ?.let { applyOverview(it.obj) }
        loadStartup(instance)
    }

    private suspend fun loadStartup(instance: String) {
        (gateway.call("startup.get", JSONObject().put("instance", instance)) as? AzurPilotGateway.Reply.Ok)
            ?.let { reply ->
                _startup.value = AzurPilotStartup(
                    enabled = reply.obj.optBoolean("enabled"),
                    remember = reply.obj.optBoolean("remember"),
                    run = reply.obj.optJSONArray("run")?.let { array ->
                        List(array.length()) { array.optString(it) }
                    }.orEmpty(),
                )
            }
    }

    /**
     * 启动/停止调度器；成功与否都用 [messages] 报出来
     *
     * Starts or stops the scheduler; the outcome is reported via [messages]
     * either way. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    fun setSchedulerRunning(running: Boolean) {
        val instance = _selectedInstance.value ?: return
        if (_schedulerBusy.value) return
        _schedulerBusy.value = true
        scope.launch(AppDispatchers.IO) {
            // 启动前等编辑队列落盘：调度器读的是磁盘上的配置，手上没保存的改动会白改
            if (running) configEditor.settle()
            val method = if (running) "scheduler.start" else "scheduler.stop"
            when (val reply = gateway.call(method, JSONObject().put("instance", instance))) {
                is AzurPilotGateway.Reply.Ok -> applyOverview(reply.obj)
                is AzurPilotGateway.Reply.Err -> report(reply.message)
                AzurPilotGateway.Reply.Offline -> Unit
            }
            _schedulerBusy.value = false
        }
    }

    /** 单跑一个任务；结果走 [overview] 与 [messages] / Runs one task on its own; the outcome lands in [overview] and [messages]. */
    fun runTask(task: String) {
        val instance = _selectedInstance.value ?: return
        scope.launch(AppDispatchers.IO) {
            val params = JSONObject().put("instance", instance).put("task", task)
            when (val reply = gateway.call("tasks.run", params)) {
                is AzurPilotGateway.Reply.Ok -> applyOverview(reply.obj)
                is AzurPilotGateway.Reply.Err -> report(reply.message)
                AzurPilotGateway.Reply.Offline -> Unit
            }
        }
    }

    /**
     * 改开机自启设置；传 null 的字段不动
     *
     * Updates the auto-start settings; fields passed as null stay untouched.
     */
    fun setStartup(enabled: Boolean? = null, remember: Boolean? = null) {
        val instance = _selectedInstance.value ?: return
        scope.launch(AppDispatchers.IO) {
            val params = JSONObject().put("instance", instance)
            enabled?.let { params.put("enabled", it) }
            remember?.let { params.put("remember", it) }
            val reply = gateway.call("startup.set", params)
            when (reply) {
                is AzurPilotGateway.Reply.Ok -> loadStartup(instance)
                is AzurPilotGateway.Reply.Err -> report(reply.message)
                AzurPilotGateway.Reply.Offline -> Unit
            }
        }
    }

    /**
     * 建实例：默认空配置，可指定模板 source 或导入文件；成功后自动切到新实例
     *
     * Creates an instance: an empty config by default, with an optional
     * template `source` or an import file; switches to the new instance on
     * success. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    fun createInstance(
        name: String,
        source: String? = null,
        importFile: String? = null,
        onCreated: (String) -> Unit = {},
    ) {
        scope.launch(AppDispatchers.IO) {
            val params = JSONObject().put("name", name)
            source?.let { params.put("source", it) }
            importFile?.let { params.put("import_file", it) }
            when (val reply = gateway.call("instances.create", params)) {
                is AzurPilotGateway.Reply.Ok -> {
                    val created = reply.obj.optString("instance", name)
                    selectInstance(created)
                    onCreated(created)
                }

                is AzurPilotGateway.Reply.Err -> report(reply.message)
                AzurPilotGateway.Reply.Offline -> Unit
            }
        }
    }

    /**
     * 删除要带 revision：网关拿它挡住「读到旧快照后删掉别人刚改的配置」
     *
     * Deletion must carry the revision: the gateway uses it to block "delete
     * the config someone else just changed, based on a stale snapshot".
     */
    fun deleteInstance(name: String) {
        scope.launch(AppDispatchers.IO) {
            val config = gateway.call("config.get", JSONObject().put("instance", name))
            if (config !is AzurPilotGateway.Reply.Ok) {
                if (config is AzurPilotGateway.Reply.Err) report(config.message)
                return@launch
            }
            val revision = config.obj.optString("revision")
            val params = JSONObject().put("instance", name).put("revision", revision)
            when (val reply = gateway.call("instances.delete", params)) {
                is AzurPilotGateway.Reply.Ok -> {
                    val remaining = _instances.value.filterNot { it.name == name }
                    _instances.value = remaining
                    if (_selectedInstance.value == name) selectInstance(remaining.firstOrNull()?.name)
                }

                is AzurPilotGateway.Reply.Err -> report(reply.message)
                AzurPilotGateway.Reply.Offline -> Unit
            }
        }
    }

    /** 导入一份配置文件内容；成功后回调拿到实例名 / Imports one config file's content; the callback receives the instance name on success. */
    fun importConfig(name: String, content: String, onImported: (String) -> Unit = {}) {
        scope.launch(AppDispatchers.IO) {
            val schema = _schema.value
            val sanitized = if (schema != null) {
                runCatching { AzurPilotConfigImport.sanitizeContent(content, schema) }.getOrDefault(content)
            } else {
                content
            }
            val params = JSONObject().put("name", name).put("content", sanitized)
            when (val reply = gateway.call("instances.importConfig", params)) {
                is AzurPilotGateway.Reply.Ok -> onImported(reply.obj.optString("name", name))
                is AzurPilotGateway.Reply.Err -> report(reply.message)
                AzurPilotGateway.Reply.Offline -> Unit
            }
        }
    }

    /**
     * 将导入文件的可编辑参数覆盖到 Android 默认实例，不创建新实例。
     *
     * 在 IO 线程等待自动保存完成后读取最新配置，按参数组分批提交变化项。
     * 保留 Android 设备连接参数；任一批次失败即停止，并刷新已提交的配置。
     *
     * Imports editable settings into the Android default instance without creating an instance.
     *
     * Waits for autosave and reads the latest config on an IO thread, then submits changed fields
     * in group-preserving batches. Preserves Android connection settings. A failed batch stops
     * the import and refreshes any already committed settings.
     */
    fun overwriteDefaultConfig(content: String, onComplete: (Boolean, String?) -> Unit) {
        scope.launch(AppDispatchers.IO) {
            val schema = _schema.value
            if (schema == null) {
                onComplete(false, "Schema is not ready")
                return@launch
            }
            if (_overview.value?.status == AzurPilotStatus.Running || _schedulerBusy.value) {
                onComplete(false, "Stop the scheduler before importing")
                return@launch
            }
            if (!configEditor.settle()) {
                onComplete(false, "Configuration changes are still saving")
                return@launch
            }
            val instance = AzurPilotRunState.DEFAULT_CONFIG
            val current = when (
                val reply = gateway.call("config.get", JSONObject().put("instance", instance))
            ) {
                is AzurPilotGateway.Reply.Ok -> parseConfig(reply.obj).values
                is AzurPilotGateway.Reply.Err -> {
                    onComplete(false, reply.message)
                    return@launch
                }
                AzurPilotGateway.Reply.Offline -> {
                    onComplete(false, "Runtime is offline")
                    return@launch
                }
            }
            val batches = runCatching { AzurPilotConfigImport.batches(content, schema, current) }
                .getOrElse {
                    onComplete(false, it.message ?: "Invalid JSON file")
                    return@launch
                }
            for (changes in batches) {
                when (
                    val reply = gateway.call(
                        "config.patch",
                        JSONObject().put("instance", instance).put("changes", changes),
                    )
                ) {
                    is AzurPilotGateway.Reply.Ok -> Unit
                    is AzurPilotGateway.Reply.Err -> {
                        configEditor.load(instance, force = true)
                        onComplete(false, reply.message)
                        return@launch
                    }
                    AzurPilotGateway.Reply.Offline -> {
                        configEditor.load(instance, force = true)
                        onComplete(false, "Runtime is offline")
                        return@launch
                    }
                }
            }
            configEditor.load(instance, force = true)
            onComplete(true, null)
        }
    }

    /**
     * 在 IO 线程读取完整配置，供导出及导入前备份使用。
     *
     * Reads the complete config on an IO thread for export and pre-import backups.
     */
    suspend fun exportConfig(instance: String): AzurPilotConfig? =
        (gateway.call("config.get", JSONObject().put("instance", instance)) as? AzurPilotGateway.Reply.Ok)
            ?.obj?.let(::parseConfig)

    private fun parseConfig(result: JSONObject): AzurPilotConfig = AzurPilotConfig(
        instance = result.optString("instance"),
        revision = result.optString("revision"),
        values = result.optJSONObject("values")?.toConfigValues().orEmpty(),
    )

    /**
     * 商店高级模式的脚本校验；返回诊断而不是抛错，编辑器要把行列标出来
     *
     * Validates the shop high-level strategy script; diagnostics are returned
     * rather than thrown, since the editor must mark rows and columns.
     */
    suspend fun validateShopStrategy(instance: String, task: String, script: String): AzurPilotShopValidation? {
        val params = JSONObject().put("instance", instance).put("task", task).put("script", script)
        val reply = gateway.call("shop_strategy.validate", params)
        if (reply !is AzurPilotGateway.Reply.Ok) return null
        val diagnostics = reply.obj.optJSONArray("diagnostics")?.let { array ->
            List(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@List null
                AzurPilotDiagnostic(
                    code = item.optString("code"),
                    message = item.optString("message"),
                    line = item.opt("line")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                    column = item.opt("column")?.takeIf { it != JSONObject.NULL }?.let { (it as Number).toInt() },
                )
            }.filterNotNull()
        }.orEmpty()
        return AzurPilotShopValidation(reply.obj.optBoolean("valid"), diagnostics)
    }

    /** 加载指定实例的统计报表。 / Loads the statistics report for the requested instance. */
    suspend fun statisticsReport(instance: String, query: AzurPilotStatsQuery): Result<AzurPilotStatisticsReport> =
        withContext(AppDispatchers.Default) {
            val params = JSONObject().put("instance", instance).put("category", query.category.key)
                .put("days", query.days).put("period", query.period).put("series", query.series)
                .put("scope", query.scope)
            query.month?.let { params.put("month", it) }
            query.task?.let { params.put("task", it) }
            when (val reply = gateway.call("statistics.report", params)) {
                is AzurPilotGateway.Reply.Ok -> runCatching {
                    decodeStatisticsReport(reply.obj, currentSchemaLanguage()).also {
                        check(it.instance == instance && it.category == query.category.key)
                    }
                }
                else -> reply.asFailure()
            }
        }

    /** 重新扫描实例的掉落记录。 / Rescans the instance's saved loot records. */
    suspend fun refreshLoot(instance: String): Result<Unit> =
        when (val reply = gateway.call("statistics.refreshLoot", JSONObject().put("instance", instance))) {
            is AzurPilotGateway.Reply.Ok -> Result.success(Unit)
            else -> reply.asFailure()
        }

    /** 启动仓库扫描前等待配置保存。 / Waits for pending configuration writes before starting a warehouse scan. */
    suspend fun runStorageStatistics(instance: String): Result<Unit> {
        val source = sourceId.value
        if (!configEditor.settle() || instance != selectedInstance.value || source != sourceId.value) {
            return AzurPilotGateway.Reply.Offline.asFailure()
        }
        val params = JSONObject().put("instance", instance).put("task", "StorageStatistics")
        return when (val reply = gateway.call("tasks.run", params)) {
            is AzurPilotGateway.Reply.Ok -> {
                applyOverview(reply.obj)
                Result.success(Unit)
            }
            else -> reply.asFailure()
        }
    }

    /** 加载资源汇总与一页流水。 / Loads resource totals and one page of transactions. */
    suspend fun resourceFlows(instance: String, query: AzurPilotFlowQuery): Result<AzurPilotFlowReport> =
        withContext(AppDispatchers.Default) {
            val params = JSONObject().put("instance", instance).put("days", query.days)
                .put("offset", query.offset).put("limit", query.limit)
            query.start?.let { params.put("start", it) }
            query.end?.let { params.put("end", it) }
            query.resource?.let { params.put("resource", it) }
            query.task?.let { params.put("task", it) }
            query.throughId?.let { params.put("through_id", it) }
            when (val reply = gateway.call("statistics.resourceFlows", params)) {
                is AzurPilotGateway.Reply.Ok -> runCatching {
                    check(reply.obj.getString("instance") == instance)
                    decodeResourceFlows(reply.obj, currentSchemaLanguage())
                }
                else -> reply.asFailure()
            }
        }

    /** 原子保存石油控制开关和目标值。 / Saves the oil-control toggle and target together. */
    suspend fun saveOilControl(instance: String, enabled: Boolean, target: Int): Result<Unit> {
        val source = sourceId.value
        if (!configEditor.settle()) return AzurPilotGateway.Reply.Offline.asFailure()
        if (instance != selectedInstance.value || source != sourceId.value) return AzurPilotGateway.Reply.Offline.asFailure()
        val params = JSONObject().put("instance", instance)
        val current = gateway.call("config.get", params)
        if (current !is AzurPilotGateway.Reply.Ok) return current.asFailure()
        if (instance != selectedInstance.value || source != sourceId.value) return AzurPilotGateway.Reply.Offline.asFailure()
        val changes = JSONArray()
            .put(JSONObject().put("path", "General.OilControl.Enable").put("value", enabled))
            .put(JSONObject().put("path", "General.OilControl.Target").put("value", target))
        val patch = params.put("revision", current.obj.optString("revision")).put("changes", changes)
        return when (val reply = gateway.call("config.patch", patch)) {
            is AzurPilotGateway.Reply.Ok -> {
                if (instance == selectedInstance.value && source == sourceId.value) configEditor.load(instance, force = true)
                Result.success(Unit)
            }
            else -> reply.asFailure()
        }
    }

    /**
     * 拉整份部署设置；结果同时落 [deploySettings]
     *
     * Fetches the whole deploy settings; the result also lands in
     * [deploySettings]. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    suspend fun loadDeploySettings(): AzurPilotDeploySettings? {
        val reply = gateway.call("settings.get")
        if (reply !is AzurPilotGateway.Reply.Ok) return null
        val data = reply.obj
        val groups = data.optJSONArray("groups")?.let { array ->
            List(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@List null
                AzurPilotDeployGroup(
                    key = item.optString("key"),
                    label = item.optString("label"),
                    fields = item.optJSONArray("fields")?.let { fields ->
                        List(fields.length()) { f ->
                            val field = fields.optJSONObject(f) ?: return@List null
                            AzurPilotDeployField(
                                key = field.optString("key"),
                                type = field.optString("type"),
                                label = field.optString("label"),
                                help = field.optString("help"),
                                value = field.opt("value").asApValue(),
                                options = field.optJSONArray("options")?.toValueList().orEmpty(),
                            )
                        }.filterNotNull()
                    }.orEmpty(),
                )
            }.filterNotNull()
        }.orEmpty()
        val remote = data.optJSONObject("remote")?.let {
            AzurPilotRemoteAccess(
                enabled = it.optBoolean("enabled"),
                state = it.optString("state"),
                address = it.optText("address").orEmpty(),
                error = it.optText("error").orEmpty(),
            )
        }
        // 同步运行时的远程访问与局域网控制状态到 App 偏好，防止外部或 WebUI 的配置在下次重启时被还原
        remote?.takeUnless { gateway.isRemote }?.let {
            if (settings.remoteAccessEnabled.value != it.enabled) {
                settings.setRemoteAccessEnabled(it.enabled)
            }
        }
        val webuiGroup = groups.firstOrNull { it.key == "Webui" }
        val webuiHost = webuiGroup?.fields?.firstOrNull { it.key == "WebuiHost" }?.value as? String
        if (webuiHost != null && !gateway.isRemote) {
            val lanEnabled = webuiHost == "0.0.0.0" || webuiHost == "::"
            if (settings.lanControlEnabled.value != lanEnabled) {
                settings.setLanControlEnabled(lanEnabled)
            }
        }
        return AzurPilotDeploySettings(
            groups = groups,
            notice = data.optString("notice"),
            demo = data.optBoolean("demo"),
            remote = remote,
        ).also { _deploy.value = it }
    }

    /**
     * 保存部署设置，并同步 App 的访问开关，避免重启覆盖刚保存的配置。空口令的处理由 Runtime 版本决定。
     *
     * Saves deploy settings and syncs app access toggles so startup preserves the saved values.
     * Empty password handling depends on the runtime version.
     *
     * @param onDone (是否成功，失败文案；Offline 时文案为 null) / (success flag,
     *   failure text; text is null when offline)
     */
    fun patchDeploySettings(values: Map<String, ApValue>, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        scope.launch(AppDispatchers.IO) {
            val payload = JSONObject()
            values.forEach { (key, value) -> payload.put(key, value.toJsonCompatible()) }
            val params = JSONObject().put("values", payload)
            when (val reply = gateway.call("settings.patch", params)) {
                is AzurPilotGateway.Reply.Ok -> {
                    (values["WebuiHost"] as? String)?.takeUnless { gateway.isRemote }?.let {
                        val lanEnabled = it == "0.0.0.0" || it == "::"
                        settings.setLanControlEnabled(lanEnabled)
                        settings.lanControlEnabled.first { saved -> saved == lanEnabled }
                    }
                    (values["EnableRemoteAccess"] as? Boolean)?.takeUnless { gateway.isRemote }?.let {
                        settings.setRemoteAccessEnabled(it)
                        settings.remoteAccessEnabled.first { saved -> saved == it }
                    }
                    loadDeploySettings()
                    withContext(Dispatchers.Main) { onDone(true, null) }
                }

                is AzurPilotGateway.Reply.Err -> {
                    report(reply.message)
                    withContext(Dispatchers.Main) { onDone(false, reply.message) }
                }

                AzurPilotGateway.Reply.Offline -> withContext(Dispatchers.Main) { onDone(false, null) }
            }
        }
    }

    /**
     * 起更新器轮询；幂等，页面在自己可见时起它
     *
     * Starts updater polling; idempotent. The updater is global, instance
     * independent — pages start and stop it as they become visible. IO 调度器上执行
     * / Runs on the IO dispatcher.
     */
    fun startUpdaterPolling() {
        if (updaterJob?.isActive == true) return
        updaterJob = scope.launch(AppDispatchers.IO) {
            while (true) {
                refreshUpdater()
                delay(UPDATER_POLL_MS)
            }
        }
    }

    /** 停更新器轮询 / Stops updater polling. */
    fun stopUpdaterPolling() {
        updaterJob?.cancel()
        updaterJob = null
    }

    /**
     * 拉一次更新器状态并落 [updater]
     *
     * Fetches the updater status once and lands it in [updater]. IO 调度器上执行
     * / Runs on the IO dispatcher.
     */
    suspend fun refreshUpdater() {
        val reply = gateway.call("updater.status") as? AzurPilotGateway.Reply.Ok ?: return
        val data = reply.obj
        _updater.value = AzurPilotUpdateStatus(
            state = data.optString("state"),
            localHead = data.optText("localHead"),
            upstreamHead = data.optText("upstreamHead"),
            branch = data.optString("branch"),
            ahead = data.optInt("ahead"),
            behind = data.optInt("behind"),
            available = data.optBoolean("available"),
            busy = data.optBoolean("busy"),
            canApply = data.optBoolean("canApply"),
            canCancel = data.optBoolean("canCancel"),
            error = data.optString("error"),
            managedByAndroid = data.optBoolean("managedByAndroid"),
        )
    }

    /**
     * 拉一页提交历史
     *
     * Fetches one page of commit history. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    suspend fun updaterCommits(offset: Int, limit: Int): AzurPilotCommitHistory? {
        val params = JSONObject().put("offset", offset).put("limit", limit)
        val reply = gateway.call("updater.commits", params)
        if (reply !is AzurPilotGateway.Reply.Ok) return null
        val data = reply.obj
        return AzurPilotCommitHistory(
            entries = data.optJSONArray("entries")?.let { array ->
                List(array.length()) { index ->
                    val item = array.optJSONObject(index) ?: return@List null
                    AzurPilotCommit(
                        sha = item.optString("sha"),
                        author = item.optString("author"),
                        date = item.optString("date"),
                        message = item.optString("message"),
                    )
                }.filterNotNull()
            }.orEmpty(),
            total = data.optInt("total"),
            hasMore = data.optBoolean("hasMore"),
            localHead = data.optText("localHead"),
            upstreamHead = data.optText("upstreamHead"),
        )
    }

    /**
     * 发一个更新器动作（apply/cancel/check 等，透传给 `updater.<action>`）
     *
     * Sends one updater action (apply/cancel/check etc., passed through as
     * `updater.<action>`).
     */
    fun updaterAction(action: String) {
        scope.launch(AppDispatchers.IO) {
            when (val reply = gateway.call("updater.$action")) {
                is AzurPilotGateway.Reply.Ok -> refreshUpdater()
                is AzurPilotGateway.Reply.Err -> report(reply.message)
                AzurPilotGateway.Reply.Offline -> Unit
            }
        }
    }

    /**
     * 拉运行时公告；网关侧有缓存，[force] 才绕过
     *
     * Fetches the runtime announcement; gateway-side caching applies unless
     * [force].
     */
    fun refreshAnnouncement(force: Boolean = false) {
        scope.launch(AppDispatchers.IO) {
            val reply = gateway.call("announcement.get", JSONObject().put("force", force))
            if (reply !is AzurPilotGateway.Reply.Ok) return@launch
            val data = reply.obj
            val id = data.optString("announcementId")
            if (id.isEmpty()) return@launch
            _announcement.value = AzurPilotAnnouncement(
                id = id,
                title = data.optString("title"),
                content = data.optString("content"),
                url = data.optText("url"),
            )
        }
    }

    /**
     * 拉指挥喵评分报告并落 [meowfficer]；未选实例时以 NOT_FOUND 失败
     *
     * Fetches the meowfficer scoring report and lands it in [meowfficer];
     * fails with NOT_FOUND when no instance is selected. IO 调度器上执行
     * / Runs on the IO dispatcher.
     */
    suspend fun loadMeowfficerReport(limit: Int = 100): Result<AzurPilotMeowfficerReport> {
        val instance = _selectedInstance.value ?: return Result.failure(AzurPilotException("NOT_FOUND", "未选择实例"))
        val params = JSONObject().put("instance", instance).put("limit", limit)
        return when (val reply = gateway.call("meowfficer.scoreReport", params)) {
            is AzurPilotGateway.Reply.Ok -> {
                val data = reply.obj
                val cats = data.optJSONArray("cats")?.let { array ->
                    List(array.length()) { index ->
                        array.optJSONObject(index)?.let(::parseCat)
                    }.filterNotNull()
                }.orEmpty()
                val report = AzurPilotMeowfficerReport(
                    generatedAt = data.optString("generatedAt"),
                    count = data.optInt("count"),
                    cats = cats,
                )
                _meowfficer.value = report
                Result.success(report)
            }

            is AzurPilotGateway.Reply.Err ->
                Result.failure(AzurPilotException(reply.code, reply.message))

            AzurPilotGateway.Reply.Offline ->
                Result.failure(AzurPilotException("DISCONNECTED", "未连接到 AzurPilot"))
        }
    }

    private fun parseCat(item: JSONObject): AzurPilotCat {
        val advice = item.optJSONObject("advice")
        val primary = item.optJSONArray("rubrics")?.let { rubrics ->
            List(rubrics.length()) { rubrics.optJSONObject(it) }.filterNotNull()
                .firstOrNull { it.optBoolean("primary") } ?: run {
                List(rubrics.length()) { rubrics.optJSONObject(it) }.filterNotNull().firstOrNull()
            }
        }
        return AzurPilotCat(
            cat = item.optString("cat"),
            level = (item.opt("level") as? Number)?.toInt(),
            maxed = item.optBoolean("maxed"),
            fixed = item.optBoolean("fixed"),
            source = item.optText("source"),
            note = item.optText("note"),
            talents = item.optJSONArray("talents")?.let { array ->
                List(array.length()) { index ->
                    val talent = array.optJSONObject(index) ?: return@List null
                    AzurPilotTalent(
                        name = talent.optString("name"),
                        level = (talent.opt("level") as? Number)?.toInt(),
                        inferred = talent.optBoolean("inferred"),
                    )
                }.filterNotNull()
            }.orEmpty(),
            score = (primary?.opt("score") as? Number)?.toDouble(),
            tier = primary?.optText("tier"),
            verdict = advice?.optText("verdict"),
            headline = advice?.optText("headline"),
            reason = primary?.optText("label"),
            adviceReason = advice?.optText("reason"),
            costText = advice?.optText("costText"),
            pointsSpent = (advice?.opt("pointsSpent") as? Number)?.toInt()
                ?: (item.opt("pointsSpent") as? Number)?.toInt(),
            targets = advice?.optJSONArray("targets")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty(),
            tags = item.optJSONArray("tags")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty(),
        )
    }

    /** 清掉评分报告；本地状态与网关侧一起清 / Clears the scoring report, both locally and gateway-side. */
    fun clearMeowfficerReport() {
        val instance = _selectedInstance.value ?: return
        scope.launch(AppDispatchers.IO) {
            when (val reply = gateway.call(
                "meowfficer.clearReport",
                JSONObject().put("instance", instance),
            )) {
                is AzurPilotGateway.Reply.Ok -> _meowfficer.value = null
                is AzurPilotGateway.Reply.Err -> report(reply.message)
                AzurPilotGateway.Reply.Offline -> Unit
            }
        }
    }

    /**
     * 界面可见性变化：离开时把截图订阅摘掉，回来再补上
     *
     * UI visibility changed: the screenshot subscription drops on leave and is
     * restored on return.
     */
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!gateway.connected.value) return
        scope.launch(AppDispatchers.IO) {
            lastSubscription = null
            syncSubscription(force = true)
        }
    }

    private companion object {
        /** 主轮询周期 / The main polling period. */
        const val POLL_MS = 5_000L

        /** 更新器轮询周期 / The updater polling period. */
        const val UPDATER_POLL_MS = 3_000L

        /** 日志板内存上限（条） / The log panel's in-memory cap, in entries. */
        const val LOG_LIMIT = 1_000
        const val TOPIC_INSTANCES = "instances"
        const val TOPIC_PREVIEW = "preview"

        /** 常订主题；`preview` 可按可见性摘除 / The standing topics; `preview` may drop by visibility. */
        // 精简后的 Android 控制台不显示实时画面，避免后台解码/传输预览帧。
        val TOPICS = listOf("instances", "overview", "logs")
    }
}

/**
 * 少量要跨重启留存的界面选择（选中实例）
 *
 * A handful of UI selections kept across restarts (the selected instance).
 */
interface AzurPilotPreferenceStore {
    /** 上次选中的实例名 / The last selected instance name. */
    var selectedInstance: String?
}
