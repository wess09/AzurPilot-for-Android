package com.azurpilot.ghio.proot

import com.azurpilot.ghio.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * 编辑器的整体状态；界面按路径查 [saving]/[failed] 决定那一行的状态标记
 *
 * The editor's overall state; the UI looks paths up in [saving]/[failed] to
 * decide each row's status marker.
 */
data class AzurPilotEditorState(
    /** 当前编辑的实例名；null 表示尚未加载 / the instance being edited; null before anything is loaded. */
    val instance: String? = null,
    /** 服务端给的乐观锁版本，`config.patch` 原样回传 / server-provided optimistic-lock revision, echoed by `config.patch`. */
    val revision: String = "",
    /** 界面该显示的值：服务端快照与本地在途编辑合并后的树 / what the UI shows: the server snapshot merged with local in-flight edits. */
    val values: ApConfigValues = emptyMap(),
    /** 整份配置拉取中 / a full config load is in flight. */
    val loading: Boolean = false,
    /** 拉取失败的文案；Offline 不算错，保持 null / load failure text; Offline is not an error and leaves this null. */
    val error: String? = null,
    /** 正在保存或已排队保存的路径 / paths being saved or queued to save. */
    val saving: Set<String> = emptySet(),
    /** 刚刚保存成功的路径；给一个短暂的「已保存」确认，随后自动消失 / paths just saved; a short-lived "saved" confirmation that fades on its own. */
    val saved: Set<String> = emptySet(),
    /** 保存失败且未被覆盖的路径 → 网关给的文案 / failed paths not yet re-edited → the gateway's message. */
    val failed: Map<String, String> = emptyMap(),
)

/**
 * 配置的本地副本 + 自动保存队列
 *
 * WebUI 的编辑体验有两条硬要求，这里照做：
 * 1. **改完立刻提交**，不等失焦也不等防抖——「改了没生效」比「多打一次请求」糟得多；
 * 2. **同一参数连改多次只发最后一次**，一次保存里的变更合并成一个事务，避免中间态触发
 *    商店高级模式那类跨字段校验。
 *
 * 本地值是在途编辑的**唯一事实**：网关上别人改了什么不覆盖用户手上正在改的值，
 * 但服务端回的快照仍会整体吸收（例如改 `XValue` 时服务端顺手刷新的 `XRecord` 时间戳）。
 *
 * A local copy of the config plus an autosave queue.
 *
 * The WebUI editing experience has two hard requirements, honored here:
 * 1. **Commit immediately on change**, neither on blur nor after a debounce —
 *    "edited but not applied" is far worse than "one extra request";
 * 2. **Several rapid edits of the same argument send only the last one**, and
 *    the changes of one save merge into a single transaction, so intermediate
 *    states cannot trigger cross-field validation such as the shop's
 *    high-level strategy checks.
 *
 * Local values are the **sole source of truth** for in-flight edits: whatever
 * others change on the gateway never overwrites a value the user is editing,
 * while server-sent snapshots are still absorbed wholesale (e.g. the `XRecord`
 * timestamp the server refreshes alongside an edited `XValue`).
 */
class AzurPilotConfigEditor(
    private val scope: CoroutineScope,
    private val gateway: AzurPilotGateway,
) {

    private val _state = MutableStateFlow(AzurPilotEditorState())

    /** 对外只读的状态流 / The externally read-only state flow. */
    val state: StateFlow<AzurPilotEditorState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** 待发送的变更：路径 → 值；同路径后写覆盖先写 / Pending changes: path → value; a later write wins on the same path. */
    private val queue = LinkedHashMap<String, ApValue>()

    /** 本地在途值：优先级高于服务端快照 / Local in-flight values; take precedence over the server snapshot. */
    private val overrides = HashMap<String, ApValue>()

    /** 服务端最后一次快照 / The server's last snapshot. */
    private var serverValues: ApConfigValues = emptyMap()

    private var worker: Job? = null

    /**
     * 丢掉整份本地副本（换实例时用）
     *
     * 状态先同步清空：worker 的下一拍看到 `instance == null` 就会直接返回，不会把上一个实例的
     * 结果写回新实例的界面。队列与快照交给 worker 持有的锁慢慢清。
     *
     * Discards the whole local copy (used when switching instances).
     *
     * The state is cleared synchronously first: on its next tick the worker
     * sees `instance == null` and returns at once, so results from the previous
     * instance never land in the new instance's UI. The queue and snapshot are
     * cleared later under the worker-held lock.
     */
    fun reset() {
        _state.value = AzurPilotEditorState()
        scope.launch(AppDispatchers.IO) {
            mutex.withLock {
                queue.clear()
                overrides.clear()
                serverValues = emptyMap()
            }
        }
    }

    /**
     * 拉取整个实例的配置；重复调用同一实例会被忽略（除非 [force]）
     *
     * Loads an instance's whole config; repeat calls for the same instance are
     * ignored (unless [force]). IO 调度器上执行 / Runs on the IO dispatcher.
     */
    fun load(instance: String, force: Boolean = false) {
        if (!force && _state.value.instance == instance && serverValues.isNotEmpty()) return
        _state.value = _state.value.copy(instance = instance, loading = true, error = null)
        scope.launch(AppDispatchers.IO) {
            val reply = gateway.call("config.get", JSONObject().put("instance", instance))
            when (reply) {
                is AzurPilotGateway.Reply.Ok -> {
                    val values = reply.obj.optJSONObject("values")?.toConfigValues().orEmpty()
                    mutex.withLock {
                        serverValues = values
                        // 换实例时把旧实例的在途编辑丢掉，它们不属于这份配置
                        if (_state.value.instance != instance) {
                            queue.clear()
                            overrides.clear()
                        }
                    }
                    publish(instance, reply.obj.optString("revision"))
                }

                is AzurPilotGateway.Reply.Err ->
                    _state.value = _state.value.copy(loading = false, error = reply.message)

                AzurPilotGateway.Reply.Offline ->
                    _state.value = _state.value.copy(loading = false, error = null)
            }
        }
    }

    /**
     * 改一个参数：本地立刻生效，同时排进保存队列
     *
     * [task]/[group]/[arg] 拼成网关要求的 `Task.Group.Argument`。
     *
     * Changes one argument: it takes effect locally at once and is queued for
     * saving.
     *
     * [task]/[group]/[arg] concatenate into the gateway's `Task.Group.Argument` path.
     */
    fun update(task: String, group: String, arg: String, value: ApValue) {
        val path = "$task.$group.$arg"
        scope.launch(AppDispatchers.IO) {
            mutex.withLock {
                overrides[path] = value
                queue[path] = value
            }
            publish(_state.value.instance, _state.value.revision)
            ensureWorker()
            signal.trySend(Unit)
        }
    }

    /**
     * 失败后重试：把用户改过的值再发一遍（改动本身没错，错的是那一次网络或时序）
     *
     * Retries after a failure: re-sends the user's value (the edit itself was
     * fine; that particular request hit a network or timing issue).
     */
    fun retry(path: String) {
        scope.launch(AppDispatchers.IO) {
            val value = mutex.withLock { overrides[path] ?: serverValueAt(path) }
            mutex.withLock {
                queue[path] = value
                _state.value = _state.value.copy(failed = _state.value.failed - path)
            }
            ensureWorker()
            signal.trySend(Unit)
        }
    }

    private fun serverValueAt(path: String): ApValue {
        val parts = path.split('.')
        if (parts.size != 3) return null
        return serverValues[parts[0]]?.get(parts[1])?.get(parts[2])
    }

    /**
     * 等到队列清空（或超时）
     *
     * 启动任务前要调它：调度器读的是磁盘上的配置，手上还有没落盘的改动就直接起，
     * 跑的是上一版参数。
     *
     * Waits until the queue drains (or times out).
     *
     * Call it before starting a task: the scheduler reads the on-disk config,
     * so starting with unsaved changes still in hand runs the previous
     * parameter set.
     *
     * @param timeoutMs 最长等待毫秒数 / the maximum wait, in milliseconds
     * @return 是否在时限内清空 / whether the queue drained within the limit
     */
    suspend fun settle(timeoutMs: Long = 30_000): Boolean {
        val settled = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val busy = mutex.withLock { queue.isNotEmpty() || _state.value.saving.isNotEmpty() }
                if (!busy) return@withTimeoutOrNull true
                delay(120)
            }
            @Suppress("UNREACHABLE_CODE") true
        }
        return settled == true
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch(AppDispatchers.IO) {
            while (true) {
                signal.receive()
                // 合并同一拍的连续改动，避免每个按键一个事务
                delay(BATCH_DEBOUNCE_MS)
                drainOnce()
            }
        }
    }

    private suspend fun drainOnce() {
        val instance = _state.value.instance ?: return
        val batch = mutex.withLock {
            if (queue.isEmpty()) return
            val copy = LinkedHashMap(queue)
            queue.clear()
            _state.value = _state.value.copy(saving = _state.value.saving + copy.keys)
            copy
        }
        val changes = JSONArray()
        batch.forEach { (path, value) ->
            changes.put(JSONObject().put("path", path).put("value", value.toJsonCompatible()))
        }
        val params = JSONObject().put("instance", instance).put("changes", changes)
        when (val reply = gateway.call("config.patch", params)) {
            is AzurPilotGateway.Reply.Ok -> {
                val values = reply.obj.optJSONObject("values")?.toConfigValues().orEmpty()
                mutex.withLock {
                    serverValues = values
                    batch.keys.forEach { overrides.remove(it) }
                    // 成功要显式收尾：只靠 publish 里的减法清不掉这批键，"保存中"会一直挂着
                    _state.value = _state.value.copy(saving = _state.value.saving - batch.keys)
                }
                publish(instance, reply.obj.optString("revision"))
                markSaved(instance, batch.keys)
            }

            is AzurPilotGateway.Reply.Err -> {
                // 值留在本地让用户改正：回滚会让输入框在打字途中被清掉
                mutex.withLock {
                    _state.value = _state.value.copy(
                        saving = _state.value.saving - batch.keys,
                        failed = _state.value.failed + batch.keys.associateWith { reply.message },
                    )
                }
                Timber.w("config.patch rejected: %s", reply.message)
            }

            AzurPilotGateway.Reply.Offline -> {
                mutex.withLock {
                    _state.value = _state.value.copy(saving = _state.value.saving - batch.keys)
                    batch.forEach { (path, value) -> queue[path] = value }
                }
            }
        }
    }

    /**
     * 服务端快照 + 本地在途覆盖 = 界面看到的值；实例已切走则丢弃这一份
     *
     * Publishes server snapshot + local in-flight overrides = the values the
     * UI sees; discarded when the instance has already switched away.
     */
    private fun publish(instance: String?, revision: String) {
        if (instance != null && _state.value.instance != instance) return
        val merged = mergeValues(serverValues, HashMap(overrides))
        _state.value = _state.value.copy(
            instance = instance,
            revision = revision.ifEmpty { _state.value.revision },
            values = merged,
            loading = false,
            error = null,
        )
    }

    /**
     * 记下「刚保存成功」，过一会再撤掉
     *
     * 「改完到底存上了没有」是配置页最常被问的问题，一个会自己消失的对勾比一行永远挂着的
     * 「已保存」更有信息量——它能让人分辨「这次刚存上」和「一直是这样」。
     *
     * Marks paths as "just saved", then unmarks them a moment later.
     *
     * "Did my edit actually save?" is the config page's most-asked question, and
     * a checkmark that fades by itself carries more information than a
     * permanently pinned "saved" — it tells "just saved this time" apart from
     * "always been like that".
     */
    private fun markSaved(instance: String?, paths: Collection<String>) {
        if (paths.isEmpty()) return
        _state.value = _state.value.copy(saved = _state.value.saved + paths)
        scope.launch(AppDispatchers.IO) {
            delay(SAVED_LINGER_MS)
            if (_state.value.instance == instance) {
                _state.value = _state.value.copy(saved = _state.value.saved - paths.toSet())
            }
        }
    }

    private fun mergeValues(base: ApConfigValues, overrides: Map<String, ApValue>): ApConfigValues {
        if (overrides.isEmpty()) return base
        val out = HashMap<String, MutableMap<String, MutableMap<String, ApValue>>>(base.size + 8)
        base.forEach { (task, groups) ->
            val groupsOut = HashMap<String, MutableMap<String, ApValue>>(groups.size)
            groups.forEach { (group, args) -> groupsOut[group] = HashMap(args) }
            out[task] = groupsOut
        }
        overrides.forEach { (path, value) ->
            val parts = path.split('.')
            if (parts.size != 3) return@forEach
            val (task, group, arg) = parts
            out.getOrPut(task) { HashMap() }.getOrPut(group) { HashMap() }[arg] = value
        }
        return out
    }

    /**
     * 读取某参数当前该显示的值：本地在途优先，其次配置，最后才是 schema 默认值
     *
     * Reads the value an argument should currently show: the local in-flight
     * value first, then the config, then the schema default.
     */
    fun valueOf(task: String, group: String, arg: String, fallback: ApValue): ApValue =
        _state.value.values[task]?.get(group)?.get(arg) ?: fallback

    private companion object {
        /** 批量去抖窗口：合并同一拍的连续按键，又不至于让人感到延迟 / Debounce window that coalesces consecutive keystrokes without feeling laggy. */
        const val BATCH_DEBOUNCE_MS = 220L

        /** 「已保存」对勾停留时长 / How long the "saved" checkmark lingers. */
        const val SAVED_LINGER_MS = 2_000L
    }
}
