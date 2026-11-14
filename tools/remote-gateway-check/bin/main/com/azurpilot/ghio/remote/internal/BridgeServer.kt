package com.azurpilot.ghio.remote.internal

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import com.azurpilot.ghio.bridge.InputControlUtils
import com.azurpilot.ghio.bridge.NativeBridgeLib
import com.azurpilot.ghio.third.FakeContext
import com.azurpilot.ghio.third.Ln
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * 特权进程内的本地设备桥服务。
 *
 * 服务仅绑定回环地址 `127.0.0.1:22301`。请求与常规响应均为一行 JSON；`screencap` 成功时，
 * 元数据 JSON 行之后立即写入 `length` 字节的原始 BGR 数据。当前端点为 `ping`、`screencap`、
 * `click`、`swipe`、`equipment_code_read`、`equipment_code_input`、`app_prepare`、`app_focus` 和
 * `shell`，
 * 每个响应都会回显请求的 `id`。协议的兼容性以此实现和
 * `doc/privileged-bridge-protocol.md` 为准，不应凭不存在的历史脚本或未固定的上游补丁推断。
 *
 * 一个线程接受连接，每个客户端使用一个守护线程。屏幕读取、触摸和装备码操作由
 * [DEVICE_LOCK] 串行，装备码输入仅接受有界 Base64 文本并始终绑定当前虚拟屏。
 * 启动聚焦同样持锁，校验目标包后向当前副屏发送按下与取消序列。
 * `shell` 不持有该锁。 [start] 在 [RemoteServiceImpl] 初始化期间调用；绑定失败只记录日志。
 * [SharedFrameServer] 提供独立共享内存截图通道，ping 返回其能力及 Unix socket 名称；
 * 此通道不取得 [DEVICE_LOCK]，仅保留按连接顺序取帧的约束。
 *
 * Loopback device bridge service in the privileged process.
 *
 * The service binds only `127.0.0.1:22301`. Requests and ordinary responses are one JSON line;
 * after a successful `screencap` metadata line it immediately writes `length` bytes of raw BGR data.
 * Current endpoints are `ping`, `screencap`, `click`, `swipe`, `equipment_code_read`,
 * `equipment_code_input`, `app_prepare`, `app_focus`, and `shell`, and every response echoes
 * the request `id`. Compatibility is defined by this implementation and
 * `doc/privileged-bridge-protocol.md`; do not infer it from absent historical scripts or an
 * unpinned upstream patch.
 *
 * One thread accepts connections and each client has a daemon thread. [DEVICE_LOCK] serializes
 * screen reads, touch injection, and equipment-code operations. Equipment input accepts only
 * bounded Base64 text on the current virtual display. Launch focus takes the same lock, verifies
 * the target package, and sends down/cancel on the current secondary display.
 * `shell` does not take the lock. [start] is
 * called while [RemoteServiceImpl] initializes, and a bind failure is logged only.
 * [SharedFrameServer] provides independent shared-memory capture; ping reports its capability
 * and Unix socket name. It does not take [DEVICE_LOCK] and requires sequential per-client reads.
 */
object BridgeServer {

    private const val TAG = "BridgeServer"
    private const val LISTEN_HOST = "127.0.0.1"
    private const val LISTEN_PORT = 22301

    /** 请求行上限，防呆（m0 MAX_LINE 同款）/ Request-line cap, sanity guard (same as m0's MAX_LINE) */
    private const val MAX_LINE = 64 * 1024

    /** shell 端点 stdout/stderr 单向上限：响应是单行 JSON，转义后膨胀，客户端行上限 256KB / Per-stream stdout/stderr cap for the shell endpoint: the response is a single JSON line and escaping inflates it; the client's line cap is 256 KB */
    private const val SHELL_OUT_CAP = 64 * 1024
    private const val SHELL_PATH_PREFIX = "/system/bin:/system_ext/bin:/vendor/bin:"
    private const val DEFAULT_SHELL_TIMEOUT_SEC = 30.0
    private const val CLICK_HOLD_MS = 50L
    private const val SWIPE_STEP_MS = 16L

    /**
     * down 注入重试预算：游戏被 am start 拉上 VD 后 SurfaceFlinger 已出帧（screencap 可见），
     * 但 input 窗注册滞后 ~1s，此间 WAIT_FOR_FINISH 注入原生返 false（debug.md 同款）。
     * AzurPilot 单击失败即 ScriptError 死调度器，故在有界预算内重试把瞬态竞态对客户端隐身；
     * 真空 VD（游戏崩溃/未启动）仍报错，只是晚 ~3s。
     *
     * Retry budget for touch-down injection: after the game is brought onto the VD via
     * am start, SurfaceFlinger has already produced frames (visible to screencap), but input
     * window registration lags ~1 s, during which WAIT_FOR_FINISH injection returns false from
     * native (same as debug.md). A single click failure kills AzurPilot's scheduler with a
     * ScriptError, so retrying within a bounded budget hides the transient race from the
     * client; a truly empty VD (game crashed / not started) still errors, just ~3 s later.
     */
    private const val DOWN_RETRY_BUDGET_MS = 3000L
    private const val DOWN_RETRY_INTERVAL_MS = 200L
    private const val DEFAULT_SWIPE_MS = 500L
    private const val JOIN_AFTER_KILL_MS = 1000L

    /**
     * 装备码文本上限，远小于协议行上限，限制逐键输入和剪贴板输出量。
     *
     * Equipment-code text cap, below the line cap, bounding key input and clipboard output.
     */
    private const val MAX_EQUIPMENT_CODE_LENGTH = 4096
    private val EQUIPMENT_CODE_PATTERN = Regex("[A-Za-z0-9+/]+={0,2}")

    /**
     * 帧读取、触摸与装备码操作的串行锁：并发抢设备通道会堵死，shell 不进这把锁。
     *
     * Global lock serializing frame reads, touches, and equipment codes. Concurrent device-channel
     * access can wedge; the shell endpoint does not take this lock.
     */
    val DEVICE_LOCK = ReentrantLock()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var startedAtMs = 0L
    private val clientCounter = AtomicInteger(0)

    /**
     * 幂等；bind 失败记日志不抛——构造期调用方是 RemoteServiceImpl.init，抛了 binder 回不去
     *
     * Idempotent; a bind failure is logged, not thrown — the constructor-time caller is
     * RemoteServiceImpl.init, and a throw there never makes it back over the binder.
     */
    @Synchronized
    fun start() {
        if (serverSocket != null) return
        // 原调用在被删的 Runner.prepare 里；保持 native 输入路径确定性单 contact 语义
        runCatching { NativeBridgeLib.setContactSupport(false) }
            .onFailure { Ln.w("$TAG: setContactSupport(false) failed: ${it.message}") }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(LISTEN_HOST), LISTEN_PORT), 8)
        } catch (e: IOException) {
            Ln.e("$TAG: bind $LISTEN_HOST:$LISTEN_PORT failed", e)
            runCatching { socket.close() }
            return
        }
        serverSocket = socket
        SharedFrameServer.start()
        startedAtMs = SystemClock.elapsedRealtime()
        thread(isDaemon = true, name = "azurpilot-bridge-accept") { acceptLoop(socket) }
        Ln.i("$TAG: listening on $LISTEN_HOST:$LISTEN_PORT")
    }

    /**
     * 停止监听并关闭 server socket（accept 阻塞因此解除）；幂等
     *
     * Stops listening and closes the server socket (waking the blocked accept); idempotent.
     */
    @Synchronized
    fun stop() {
        SharedFrameServer.stop()
        val socket = serverSocket ?: return
        serverSocket = null
        runCatching { socket.close() }
            .onFailure { Ln.w("$TAG: close server socket failed: ${it.message}") }
        Ln.i("$TAG: stopped")
    }

    /** 是否在监听 / Whether the server socket is open */
    fun isRunning(): Boolean = serverSocket != null

    private fun acceptLoop(socket: ServerSocket) {
        while (serverSocket === socket) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                // stop() 关 socket 唤醒 accept 是正常退出；引用比较防 stop→start 快速重开误杀新循环
                if (serverSocket !== socket) break
                Ln.w("$TAG: accept failed: ${e.message}")
                Thread.sleep(1000)
                continue
            }
            val no = clientCounter.incrementAndGet()
            thread(isDaemon = true, name = "azurpilot-client-$no") { serveClient(client, no) }
        }
    }

    private fun serveClient(socket: Socket, clientNo: Int) {
        val conn = Conn(socket)
        Ln.i("$TAG: client #$clientNo connected from ${socket.remoteSocketAddress}")
        try {
            while (true) {
                val line = conn.readLine() ?: break
                if (line.isBlank()) continue
                val request = try {
                    JSONObject(line)
                } catch (e: JSONException) {
                    // 协议错误：回错误帧再断开，避免坏数据拖死 accept 循环
                    runCatching {
                        conn.sendLine(
                            JSONObject()
                                .put("id", JSONObject.NULL)
                                .put("ok", false)
                                .put("error", "bad request: ${e.message}")
                        )
                    }
                    break
                }
                handleRequest(conn, request)
            }
        } catch (e: IOException) {
            Ln.w("$TAG: client #$clientNo error: ${e.message}")
        } catch (e: Exception) {
            // 特权进程里线程未捕获异常会杀整个进程，兜底不能不写
            Ln.e("$TAG: client #$clientNo unexpected error", e)
        } finally {
            Ln.i("$TAG: client #$clientNo disconnected")
            conn.close()
        }
    }

    private fun handleRequest(conn: Conn, request: JSONObject) {
        val reqId: Any = request.opt("id") ?: JSONObject.NULL
        val method = if (request.isNull("method")) null else request.optString("method")

        fun reply(payload: JSONObject) {
            payload.put("id", reqId)
            conn.sendLine(payload)
        }

        try {
            when (method) {
                "ping" -> reply(
                    JSONObject()
                        .put("ok", true)
                        .put("pong", true)
                        .put("capabilities", JSONObject().put("equipmentCode", true)
                            .put("appFocus", true)
                            .put("nativeGameResolution", true)
                            .put("sharedMemoryScreencap", SharedFrameServer.isRunning()))
                        .put("sharedFrameSocket", SharedFrameServer.SOCKET_NAME)
                        .put("displayId", VirtualDisplayManager.getDisplayId())
                        .put("capture", if (NativeBridgeLib.LOADED) NativeBridgeLib.getCaptureDiagnostics() else "native not loaded")
                        .put("uptime", (SystemClock.elapsedRealtime() - startedAtMs) / 1000.0)
                )

                "screencap" -> handleScreencap(conn, ::reply)
                "click" -> handleClick(request, ::reply)
                "swipe" -> handleSwipe(request, ::reply)
                "equipment_code_read" -> handleEquipmentCodeRead(::reply)
                "equipment_code_input" -> handleEquipmentCodeInput(request, ::reply)
                "shell" -> handleShell(request, ::reply)
                "app_prepare" -> DEVICE_LOCK.withLock {
                    GameResolutionController.prepare(
                        request.getString("package"), request.getInt("displayId")
                    )
                    reply(ok())
                }
                "app_focus" -> DEVICE_LOCK.withLock {
                    val packageName = request.getString("package")
                    val displayId = request.getInt("displayId")
                    if (packageName.isBlank() ||
                        !ActivityUtils.focusAppOnDisplay(packageName, displayId)
                    ) {
                        reply(err("no ready target window on display $displayId"))
                    } else {
                        reply(JSONObject().put("ok", true).put("displayId", displayId))
                    }
                }
                else -> reply(err(if (method == null) "unknown method: None" else "unknown method: '$method'"))
            }
        } catch (e: Exception) {
            // 单请求失败不能拖垮服务：记日志 + 错误帧，连接保持
            Ln.e("$TAG: request '$method' failed", e)
            runCatching { reply(err(e.message ?: e.toString())) }
        }
    }

    /** screencap 端点：响应行后紧跟裸字节帧 / The screencap endpoint: a bare byte frame follows the response line */
    private fun handleScreencap(conn: Conn, reply: (JSONObject) -> Unit) {
        DEVICE_LOCK.withLock {
            if (!NativeBridgeLib.LOADED) {
                reply(err("native bridge not loaded"))
                return@withLock
            }
            val bytes = NativeBridgeLib.getFrameBufferBytes()
            if (bytes == null) {
                reply(err("no frame available: display=${VirtualDisplayManager.getDisplayId()} " +
                    NativeBridgeLib.getCaptureDiagnostics()))
                return@withLock
            }
            val cfg = VirtualDisplayManager.getConfig()
            if (bytes.size != cfg.width * cfg.height * 3) {
                // VD 重启半途：native 帧缓冲还是旧尺寸，直接发给客户端会 reshape 炸掉
                reply(err("frame size mismatch: got ${bytes.size}, expected ${cfg.width}x${cfg.height}x3"))
                return@withLock
            }
            reply(
                JSONObject()
                    .put("ok", true)
                    .put("width", cfg.width)
                    .put("height", cfg.height)
                    .put("channels", 3)
                    .put("length", bytes.size)
                    // 本轮帧缓冲生命周期内的帧序号：客户端比对相邻 screencap 的值即可识别
                    // 画面冻结（进程被 ROM 冻结时值不再增长）
                    .put("frames", NativeBridgeLib.getFrameCount())
            )
            conn.sendRaw(bytes)
        }
    }

    /**
     * 有界重试的 down：见 [DOWN_RETRY_BUDGET_MS] 注释。失败事件未投递无悬挂状态，可安全重试。
     *
     * Touch-down with a bounded retry: see the [DOWN_RETRY_BUDGET_MS] note. A failed down
     * delivers no event and leaves no dangling state, so retrying is safe.
     */
    private fun downWithRetry(x: Int, y: Int, displayId: Int): Boolean {
        val deadline = SystemClock.uptimeMillis() + DOWN_RETRY_BUDGET_MS
        var attempts = 0
        while (true) {
            attempts++
            if (InputControlUtils.down(x, y, 0, displayId)) {
                if (attempts > 1) Ln.w("$TAG: touch down ok after $attempts attempts (window-register race absorbed)")
                return true
            }
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0) {
                Ln.e("$TAG: touch down failed after $attempts attempts / ${DOWN_RETRY_BUDGET_MS}ms on display $displayId")
                return false
            }
            Thread.sleep(minOf(DOWN_RETRY_INTERVAL_MS, remaining))
        }
    }

    private fun handleClick(request: JSONObject, reply: (JSONObject) -> Unit) {
        val x = request.getInt("x")
        val y = request.getInt("y")
        DEVICE_LOCK.withLock {
            val displayId = VirtualDisplayManager.getDisplayId()
            if (displayId == VirtualDisplayManager.DISPLAY_NONE) {
                reply(err("no active virtual display"))
                return@withLock
            }
            if (!downWithRetry(x, y, displayId)) {
                reply(err("touch down failed (no touchable window on display $displayId within ${DOWN_RETRY_BUDGET_MS}ms)"))
                return@withLock
            }
            Thread.sleep(CLICK_HOLD_MS)
            if (!InputControlUtils.up(x, y, 0, displayId)) {
                reply(err("touch up failed"))
                return@withLock
            }
            reply(ok())
        }
    }

    private fun handleSwipe(request: JSONObject, reply: (JSONObject) -> Unit) {
        val x1 = request.getInt("x1")
        val y1 = request.getInt("y1")
        val x2 = request.getInt("x2")
        val y2 = request.getInt("y2")
        val durationMs = request.optLong("duration", DEFAULT_SWIPE_MS).coerceAtLeast(0)
        DEVICE_LOCK.withLock {
            val displayId = VirtualDisplayManager.getDisplayId()
            if (displayId == VirtualDisplayManager.DISPLAY_NONE) {
                reply(err("no active virtual display"))
                return@withLock
            }
            if (!downWithRetry(x1, y1, displayId)) {
                reply(err("touch down failed (no touchable window on display $displayId within ${DOWN_RETRY_BUDGET_MS}ms)"))
                return@withLock
            }
            val steps = (durationMs / SWIPE_STEP_MS).coerceAtLeast(1)
            val startMs = SystemClock.uptimeMillis()
            var lastX = x1
            var lastY = y1
            var moveFailed = false
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                lastX = (x1 + (x2 - x1) * t).roundToInt()
                lastY = (y1 + (y2 - y1) * t).roundToInt()
                // 绝对时间轴控节奏：单步注入耗时不吃后续预算，整体时长仍 ≈ duration
                val sleepMs = startMs + durationMs * i / steps - SystemClock.uptimeMillis()
                if (sleepMs > 0) Thread.sleep(sleepMs)
                if (!InputControlUtils.move(lastX, lastY, 0, displayId)) {
                    moveFailed = true
                    break
                }
            }
            // 失败也要抬手：悬着的 ACTION_DOWN 会劫持虚拟屏触摸输入直到 VD 重启
            val upOk = InputControlUtils.up(lastX, lastY, 0, displayId)
            if (moveFailed || !upOk) {
                reply(err("swipe injection failed"))
                return@withLock
            }
            reply(ok())
        }
    }

    private fun validEquipmentCode(code: String): Boolean =
        code.length in 16..MAX_EQUIPMENT_CODE_LENGTH && code.length % 4 == 0 &&
            EQUIPMENT_CODE_PATTERN.matches(code)

    /**
     * 仅读取剪贴板中的装备码文本，不展开 URI，也不返回其他剪贴板内容。
     *
     * Reads only equipment-code text from the clipboard, without resolving URIs or returning
     * other clipboard content.
     */
    private fun handleEquipmentCodeRead(reply: (JSONObject) -> Unit) {
        DEVICE_LOCK.withLock {
            if (VirtualDisplayManager.getDisplayId() == VirtualDisplayManager.DISPLAY_NONE) {
                reply(err("no active virtual display"))
                return@withLock
            }
            val clipboard = FakeContext.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
            // 不先复制无界 CharSequence，避免把大剪贴板转换为协议响应。
            val code = if (text != null && text.length <= MAX_EQUIPMENT_CODE_LENGTH) {
                text.toString().trim().takeIf(::validEquipmentCode)
            } else null
            reply(ok().put("code", code ?: JSONObject.NULL))
        }
    }

    /**
     * 将装备码替换到当前虚拟屏的已聚焦文本框，成功不表示游戏已加载装备预览。
     *
     * Replaces the focused field on the current virtual display; success does not imply that
     * the game has loaded the equipment preview.
     */
    private fun handleEquipmentCodeInput(request: JSONObject, reply: (JSONObject) -> Unit) {
        val code = request.getString("code")
        require(validEquipmentCode(code)) { "invalid equipment code" }
        DEVICE_LOCK.withLock {
            val displayId = VirtualDisplayManager.getDisplayId()
            if (displayId == VirtualDisplayManager.DISPLAY_NONE) {
                reply(err("no active virtual display"))
                return@withLock
            }
            val success = InputControlUtils.replaceAsciiText(code, displayId)
            reply(if (success) ok() else err("equipment text injection failed"))
        }
    }

    /** shell 端点：`sh -c` 执行命令，双流封顶收集，超时 destroyForcibly（上限见 [SHELL_OUT_CAP]）/ The shell endpoint: runs `sh -c`, caps both streams, destroyForcibly on timeout (cap in [SHELL_OUT_CAP]) */
    private fun handleShell(request: JSONObject, reply: (JSONObject) -> Unit) {
        val cmd = request.getString("cmd")
        val timeoutSec = request.optDouble("timeout", DEFAULT_SHELL_TIMEOUT_SEC)
        val process = ProcessBuilder("sh", "-c", cmd)
            .apply {
                val env = environment()
                // 剥掉 LD_LIBRARY_PATH：系统二进制被桥自身的库污染会起不来（m0 同款教训）
                env.remove("LD_LIBRARY_PATH")
                env["PATH"] = SHELL_PATH_PREFIX + (env["PATH"] ?: "")
            }
            .start()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        // stdout/stderr 分线程读全：单线程顺序读会在管道 buffer 撑满时与子进程互等死锁
        val outReader =
            thread(isDaemon = true, name = "azurpilot-shell-out") { drain(process.inputStream, stdout) }
        val errReader =
            thread(isDaemon = true, name = "azurpilot-shell-err") { drain(process.errorStream, stderr) }
        val timeoutMs = (timeoutSec * 1000).toLong().coerceAtLeast(1)
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            outReader.join(JOIN_AFTER_KILL_MS)
            errReader.join(JOIN_AFTER_KILL_MS)
            reply(err("shell timeout ${timeoutSec}s"))
            return
        }
        outReader.join()
        errReader.join()
        reply(
            JSONObject()
                .put("ok", process.exitValue() == 0)
                .put("code", process.exitValue())
                .put("stdout", stdout.toString(Charsets.UTF_8.name()))
                .put("stderr", stderr.toString(Charsets.UTF_8.name()))
        )
    }

    private fun drain(input: InputStream, out: ByteArrayOutputStream) {
        val tmp = ByteArray(8192)
        while (true) {
            val n = try {
                input.read(tmp)
            } catch (e: IOException) {
                break
            }
            if (n < 0) break
            // 超限后照读照丢：不抽干管道，子进程写满 buffer 就憋死了
            val room = SHELL_OUT_CAP - out.size()
            if (room > 0) out.write(tmp, 0, minOf(n, room))
        }
    }

    private fun ok(): JSONObject = JSONObject().put("ok", true)

    private fun err(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    /**
     * socket 之上的缓冲读：行 + 裸字节写在同一条流上混用不丢数据（m0 Conn 语义）。
     * 5 端点里没有上行裸帧（ocr 已剔除），故只需行读；BufferedInputStream 垫底，
     * 后续若加读帧端点，字节流位置天然衔接。
     *
     * Buffered reading over the socket: lines and raw bytes interleave on the same stream
     * without loss (m0 Conn semantics). None of the 5 endpoints reads an upstream raw frame
     * (ocr was dropped), so only line reads are needed; BufferedInputStream underneath keeps
     * the byte position naturally aligned should a frame-reading endpoint be added later.
     */
    private class Conn(private val socket: Socket) {

        private val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
        private val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)

        /**
         * 读一行（不含 \n）；EOF 返回 null；超上限抛 IOException 由连接层断开
         *
         * Reads one line (without \n); null on EOF; an over-limit line throws IOException and
         * the connection layer disconnects.
         */
        fun readLine(): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) break
                line.write(b)
                if (line.size() > MAX_LINE) throw IOException("request line too long")
            }
            return line.toString(Charsets.UTF_8.name())
        }

        fun sendLine(payload: JSONObject) {
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
        }

        fun sendRaw(data: ByteArray) {
            output.write(data)
            output.flush()
        }

        fun close() {
            runCatching { socket.close() }
        }

        private companion object {
            const val BUFFER_SIZE = 8192
        }
    }
}
