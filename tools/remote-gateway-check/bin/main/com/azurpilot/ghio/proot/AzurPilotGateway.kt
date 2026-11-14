package com.azurpilot.ghio.proot

import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.remoteaccess.RemotePairing
import com.azurpilot.ghio.remoteaccess.RemotePairings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * AzurPilot `/api/v1/ws` 网关客户端
 *
 * WebUI 前端用的就是这一条长连接：请求/响应信封 + 主题推送 + `seq` 序号。
 * 原生界面不碰 HTTP 静态资源，但业务能力全部来自这里——所以这个类要能表达协议的全部语义，
 * 而不只是「拿到没有」。
 *
 * **本机直连免密**：网关的 `is_local_client()` 只要求来源地址与 Host 头都是回环，
 * 且**没有 Origin 头**——OkHttp 的 WebSocket 天然不带 Origin，因此直接放行，无需 WebUI 密码。
 * 远程来源每次连接重新解析固定入口，用独立配对凭据登录，鉴权成功后才发布就绪与恢复订阅。
 * 本机收到 `authRequired` 时，登录入口由 [login] 提供。
 *
 * 信封（`module/api/protocol.py`）：
 * - 请求 `{"v":1,"type":"request","id":..,"method":..,"params":{..}}`
 * - 响应 `{"v":1,"type":"response","id":..,"ok":true,"result":{..}}`，失败为 `ok:false` + `error{code,message}`
 * - 事件 `{"v":1,"type":"event","topic":"overview|instances|logs|preview|statistics|session","seq":n,"data":{..}}`
 *
 * The AzurPilot `/api/v1/ws` gateway client.
 *
 * This is the very long-lived connection the WebUI frontend uses: request/
 * response envelopes, topic pushes and `seq` numbering. The native UI never
 * touches HTTP static assets, but all business capability comes from here —
 * so this class has to express the protocol's full semantics, not just "did it
 * arrive".
 *
 * **Local direct connections are password-free**:
 * the gateway's `is_local_client()` only requires the source address and the
 * Host header to be loopback with **no Origin header** — OkHttp's WebSocket
 * naturally sends none, so it is admitted directly, without the WebUI
 * password. Remote sources resolve the stable URL on each connection and log in with their
 * independent pairing credential before publishing readiness or restoring subscriptions.
 * If a local session reports `authRequired`, [login] provides the sign-in path.
 *
 * Envelope (`module/api/protocol.py`):
 * - request `{"v":1,"type":"request","id":..,"method":..,"params":{..}}`
 * - response `{"v":1,"type":"response","id":..,"ok":true,"result":{..}}`; on failure
 *   `ok:false` + `error{code,message}`
 * - event `{"v":1,"type":"event","topic":"overview|instances|logs|preview|statistics|session","seq":n,"data":{..}}`
 */
class AzurPilotGateway(private val scope: CoroutineScope, private val remoteStore: RemotePairings? = null) {
    private val targetMutex = Mutex()
    @Volatile private var remoteTarget: RemotePairing? = null
    private val _remoteName = MutableStateFlow<String?>(null)
    /** 当前原生控制台连接的远程 AP；null 表示本机。 / Remote AP name; null means local. */
    val remoteName: StateFlow<String?> = _remoteName.asStateFlow()
    private val _sourceId = MutableStateFlow("local")
    /** 页面缓存的来源标识。 / Source identity for native page caches. */
    val sourceId: StateFlow<String> = _sourceId.asStateFlow()
    /** 当前是否使用远程网关。 / Whether the gateway uses a remote AP. */
    val isRemote: Boolean get() = remoteTarget != null
    @Volatile private var generation = 0L

    /**
     * 切换网关来源，等待旧连接退出后重新解析固定入口；不修改本机 Runtime 地址。
     *
     * Switches gateway sources after closing the previous session, without changing local runtime URLs.
     */
    suspend fun selectTarget(pairing: RemotePairing?) = targetMutex.withLock {
        val previous = loop
        stop()
        previous?.cancelAndJoin()
        remoteTarget = pairing
        _sourceId.value = pairing?.deviceId ?: "local"
        _remoteName.value = pairing?.name?.ifBlank { pairing.deviceId.take(8) }
        subscription = null to emptyList()
        start()
    }

    /**
     * 一次调用的结果。`Offline` 与 `Err` 分开：前者可以重试，后者重试也是同一个错
     *
     * `raw` 不一定是对象——`instances.list` / `instances.importable` 按协议返回的是**裸数组**，
     * 只认对象的话这两个接口的返回值会被整段丢掉。所以原样留着，由调用方按 [obj] / [array] 取。
     *
     * The result of one call. `Offline` and `Err` are separate: the former is
     * retryable, the latter retried only yields the same error.
     *
     * `raw` is not necessarily an object — `instances.list` /
     * `instances.importable` return **bare arrays** by protocol, and accepting
     * only objects would drop those two endpoints' payloads wholesale. It is
     * kept verbatim; callers pick it up via [obj] / [array].
     */
    sealed interface Reply {
        /**
         * 成功态。`CompletableDeferred` 不接受 null 作为完成值，所以成功态本身带上结果一起传。
         *
         * Success. `CompletableDeferred` accepts no null completion value, so
         * the success state carries the payload along with it.
         */
        data class Ok(val raw: ApValue) : Reply {
            val obj: JSONObject get() = raw as? JSONObject ?: EMPTY_OBJECT
            val array: JSONArray? get() = raw as? JSONArray
        }

        /** 网关回的业务错误 / A business error returned by the gateway. */
        data class Err(val code: String, val message: String) : Reply

        /** 连接不在（未连上/已断/发送失败）：可重试 / No connection (never opened, lost, or send failed): retryable. */
        object Offline : Reply

        companion object {
            private val EMPTY_OBJECT = JSONObject()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接：读超时交给心跳与重连
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val _connected = MutableStateFlow(false)

    /** 与网关的长连接是否存活；唯一事实源在 [awaitSession] / Whether the gateway WebSocket is alive; the single source of truth is [awaitSession]. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * 网关要求先登录；本机直连时为 false
     *
     * The gateway demands a login first; false for local direct connections.
     */
    private val _authRequired = MutableStateFlow(false)
    val authRequired: StateFlow<Boolean> = _authRequired.asStateFlow()

    /**
     * 网关事件流（`session` 主题除外，那条在握手时就地消费）
     *
     * The gateway event stream (the `session` topic excluded — it is consumed
     * in place during the handshake).
     */
    private val _events = MutableSharedFlow<JSONObject>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events.asSharedFlow()

    /** 在途请求 id → 完成器 / In-flight request id → completion handle. */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Reply>>()

    @Volatile
    private var socket: WebSocket? = null

    private var loop: Job? = null

    /**
     * 当前订阅；断线重连后用它原样恢复，界面不需要感知重连
     *
     * The current subscription; restored verbatim after a reconnect, so the UI
     * need not know a reconnect happened.
     */
    @Volatile
    private var subscription: Pair<String?, List<String>> = null to emptyList()

    /**
     * 启动重连循环；幂等，已在跑直接返回
     *
     * Starts the reconnect loop; idempotent, returns at once when already
     * running. IO 调度器上执行 / Runs on the IO dispatcher.
     */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch(AppDispatchers.IO) {
            var backoff = RECONNECT_MIN_MS
            while (true) {
                // awaitSession 要挂到这条连接**结束**才返回；只在 onOpen 就返回会让循环
                // 每 2 秒另开一条，旧连接一直挂着，直到撞上网关的连接数上限被踢
                val opened = runCatching { awaitSession() }.getOrElse {
                    if (it is CancellationException) throw it
                    Timber.d(it, "azurpilot gateway connect failed")
                    false
                }
                _connected.value = false
                failAllPending()
                backoff = if (opened) RECONNECT_MIN_MS else (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
                delay(backoff)
            }
        }
    }

    /**
     * 停止重连循环并关闭连接；所有在途请求立刻作废
     *
     * Stops the reconnect loop and closes the connection; every in-flight
     * request is dropped at once.
     */
    fun stop() {
        generation++
        loop?.cancel()
        loop = null
        socket?.close(NORMAL_CLOSE, null)
        socket = null
        _connected.value = false
        _authRequired.value = false
        failAllPending()
    }

    /**
     * 订阅主题；`instance` 为 null 时只订 `instances` 这类与实例无关的主题
     *
     * 网关侧订阅会被整体替换（不是叠加），所以这里保存完整的一份用于重连恢复。
     *
     * Subscribes to topics; a null `instance` subscribes only to
     * instance-independent topics such as `instances`.
     *
     * The gateway-side subscription is replaced wholesale (not merged), so the
     * full copy is kept here for reconnect restoration.
     */
    suspend fun subscribe(instance: String?, topics: List<String>): Reply {
        subscription = instance to topics
        // 必须是 JSONArray：org.json 的 put 收到裸 List 会当成普通对象，
        // 序列化时走 toString() 变成 `"instances, overview"` 这样一个字符串，网关直接 INVALID_PARAMS
        val params = JSONObject().put("topics", JSONArray(topics))
        if (instance != null) params.put("instance", instance)
        return call("events.subscribe", params)
    }

    /** 登录成功后发布当前会话就绪。 / Publishes readiness after the current session authenticates. */
    suspend fun login(password: String): Reply {
        val current = socket ?: return Reply.Offline
        val sessionGeneration = generation
        val reply = call("auth.login", JSONObject().put("password", password))
        if (reply is Reply.Ok) sessionReady(current, sessionGeneration)
        return reply
    }

    /**
     * 发一次请求并等它的响应
     *
     * [timeoutMs] 默认取协议的 45s——`scheduler.stop` 要等最多 30s 才敢回话，超时设小了会
     * 把「正在停」误判成失败。
     *
     * Sends one request and awaits its response.
     *
     * [timeoutMs] defaults to the protocol's 45 s — `scheduler.stop` may take
     * up to 30 s before it dares to answer, and a smaller timeout would
     * misreport "stopping" as a failure.
     *
     * @param method 协议方法名 / the protocol method name
     * @param params 请求参数 / request parameters
     * @param timeoutMs 等响应的时限 / how long to wait for the response
     * @return [Reply.Ok] / [Reply.Err] / [Reply.Offline] 之一 / one of
     *   [Reply.Ok], [Reply.Err], [Reply.Offline]
     */
    suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): Reply {
        val ws = socket
        if (ws == null || (!_connected.value && method != "auth.login")) return Reply.Offline
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Reply>()
        pending[id] = deferred
        try {
            val payload = JSONObject()
                .put("v", PROTOCOL_VERSION)
                .put("type", "request")
                .put("id", id)
                .put("method", method)
                .put("params", params)
            if (!ws.send(payload.toString())) return Reply.Offline
            // 业务拒绝也是完整响应，不能与等待超时共用 null。
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: if (!_connected.value) Reply.Offline
                else Reply.Err("TIMEOUT", "请求超时（$method）")
        } finally {
            pending.remove(id)
        }
    }

    /** 只关心「拿到没有」的调用点用这个；错误码与数组结果都折成 null / For call sites that only care about "did it arrive"; error codes and array results collapse to null. */
    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject? =
        (call(method, params) as? Reply.Ok)?.obj

    /**
     * 建连，并**挂起直到这条连接结束**；返回期间是否真的建立过会话
     *
     * 返回 true 表示「连上过」（用来重置退避），false 表示连都没连上（用来加倍退避）。
     * 连接存续期间这个挂起就是「保持」——`_connected` 是唯一的连接态来源。
     *
     * Opens a connection and **suspends until that connection ends**; returns
     * whether a session was ever established in the meantime.
     *
     * A true return means "connected at some point" (used to reset the
     * backoff); false means it never connected (used to double the backoff).
     * For as long as the connection lives this suspension is the "stay" —
     * `_connected` is the single source of connection state.
     */
    private suspend fun awaitSession(): Boolean {
        val sessionGeneration = generation
        val target = remoteTarget
        val endpoint = if (target == null) RuntimeConnection.endpoint.wsUrl else {
            requireNotNull(remoteStore).resolve(target).replaceFirst("https://", "wss://") + "api/v1/ws"
        }
        return suspendCancellableCoroutine { cont ->
            var opened = false
            var resumed = false

            fun finish() {
                if (resumed) return
                resumed = true
                if (cont.isActive) cont.resume(opened)
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (sessionGeneration != generation) { webSocket.cancel(); return }
                    socket = webSocket
                    opened = true
                    Timber.d("azurpilot gateway connected")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (sessionGeneration == generation) handleMessage(text, webSocket, target, sessionGeneration)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // 对端先发起：回一个正常关闭，让 onClosed 走完
                    webSocket.close(NORMAL_CLOSE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) socket = null
                    finish()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) socket = null
                    Timber.d(t, "azurpilot gateway socket failure")
                    finish()
                }
            }
            val request = Request.Builder().url(endpoint).build()
            val boundSocket = client.newWebSocket(request, listener)
            socket = boundSocket
            cont.invokeOnCancellation {
                boundSocket.cancel()
                if (socket === boundSocket) socket = null
            }
        }
    }

    private fun sessionReady(webSocket: WebSocket, sessionGeneration: Long) {
        if (sessionGeneration != generation || socket !== webSocket) return
        _authRequired.value = false
        _connected.value = true
        val (instance, topics) = subscription
        if (topics.isNotEmpty()) scope.launch(AppDispatchers.IO) { subscribe(instance, topics) }
    }

    /** 按信封 `type` 分发：响应唤醒等待者，事件进流；session 主题就地消费 / Dispatches by envelope `type`: responses wake waiters, events enter the stream; the session topic is consumed in place. */
    private fun handleMessage(text: String, webSocket: WebSocket, target: RemotePairing?, sessionGeneration: Long) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "response" -> {
                val id = json.optString("id")
                val deferred = id.takeIf { it.isNotEmpty() }?.let { pending.remove(it) } ?: return
                if (json.optBoolean("ok")) {
                    // 缺席与显式 null 都收成 null；JSONObject.NULL 不能当值用
                    val raw = json.opt("result").takeIf { it !== JSONObject.NULL }
                    deferred.complete(Reply.Ok(raw))
                } else {
                    val error = json.optJSONObject("error")
                    val code = error?.optString("code").orEmpty()
                    val message = error?.optString("message").orEmpty()
                    Timber.w("azurpilot gateway %s: %s", code, message)
                    deferred.complete(Reply.Err(code, message))
                    if (target != null && code == "UNAUTHORIZED") {
                        _authRequired.value = true
                        _connected.value = false
                        webSocket.close(NORMAL_CLOSE, null)
                    }
                }
            }

            "event" -> {
                // 连接建立时网关先发一条 session：本机直连 authRequired=false，无需登录
                if (json.optString("topic") == "session") {
                    _authRequired.value = json.optJSONObject("data")?.optBoolean("authRequired") ?: false
                    if (_authRequired.value && target != null) {
                        scope.launch(AppDispatchers.IO) {
                            if (sessionGeneration != generation) return@launch
                            if (login(target.accessToken) !is Reply.Ok) webSocket.close(NORMAL_CLOSE, null)
                        }
                    } else if (!_authRequired.value) sessionReady(webSocket, sessionGeneration)
                    return
                }
                // 事件不阻塞读取循环：满了丢最旧（过期总览没有价值）
                _events.tryEmit(json.put("_gatewaySourceId", target?.deviceId ?: "local"))
            }
        }
    }

    private fun failAllPending() {
        pending.values.forEach { it.complete(Reply.Offline) }
        pending.clear()
    }

    companion object {
        /** 网关 WS 地址 / The gateway WebSocket URL. */
        private const val NORMAL_CLOSE = 1000
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val REQUEST_TIMEOUT_MS = 45_000L
        private const val PING_INTERVAL_MS = 20_000L
        private const val RECONNECT_MIN_MS = 2_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val EVENT_BUFFER = 256
        private const val PROTOCOL_VERSION = 1
    }
}

/**
 * 网关回的业务错误；[code] 是协议里的错误码，[message] 是可直接展示的中文文案
 *
 * A business error returned by the gateway; [code] is the protocol error code
 * and [message] is display-ready text.
 */
class AzurPilotException(val code: String, override val message: String) : Exception(message)

/**
 * 失败态折成 `Result`
 *
 * 走 `Result` 的调用点需要「错误文案能显示出来」，而 [AzurPilotGateway.Reply.Err] 里已经带着
 * 可直接展示的中文了——这里只做转换，不再拼文案。
 *
 * Folds the failure states into a `Result`.
 *
 * Call sites on `Result` need "an error message that can be shown", and
 * [AzurPilotGateway.Reply.Err] already carries display-ready text — this is a
 * pure conversion, no message crafting.
 */
fun AzurPilotGateway.Reply.asFailure(): Result<Nothing> = when (this) {
    is AzurPilotGateway.Reply.Err -> Result.failure(AzurPilotException(code, message))
    AzurPilotGateway.Reply.Offline ->
        Result.failure(AzurPilotException("DISCONNECTED", "未连接到 AzurPilot"))

    is AzurPilotGateway.Reply.Ok ->
        Result.failure(AzurPilotException("INTERNAL_ERROR", "结果格式不正确"))
}
