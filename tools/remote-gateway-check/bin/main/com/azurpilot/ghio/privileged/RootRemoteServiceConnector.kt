package com.azurpilot.ghio.privileged
import com.azurpilot.ghio.AppDispatchers

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.constant.AppPaths
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.remote.RemoteServiceImpl
import com.azurpilot.ghio.root.RootServiceBootstrapRegistry
import com.azurpilot.ghio.root.RootServiceStarter
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root 后端的连接器：用 su 拉起独立的 `:root_service` 特权进程，等它回投 binder
 *
 * 链路：注册一次性 token（[RootServiceBootstrapRegistry]）→ su 执行
 * `liblauncher.so`（app_process 包装器）拉起 [RootServiceStarter] 入口 →
 * 特权进程经 token 把 RemoteService binder 回投到注册表 → 本连接器
 * await 到后交给 [RemoteServiceConnectorBackend.Callbacks]。
 *
 * 每次 connect 铸新 token；各完成点都按 activeLaunch 身份比对，被新连接顶替的
 * 旧结果一律注销丢弃。启动失败或等 binder 超时会转储 root_launch_debug.log
 * 帮助定位。
 *
 * The Root-backend connector; brings up a dedicated `:root_service` privileged
 * process via su and waits for it to hand back its binder.
 *
 * Chain: register a one-shot token ([RootServiceBootstrapRegistry]) → run
 * `liblauncher.so` (an app_process wrapper) under su to start the
 * [RootServiceStarter] entry → the privileged process returns the
 * RemoteService binder through the token → this connector awaits it and
 * forwards to [RemoteServiceConnectorBackend.Callbacks].
 *
 * Every connect mints a fresh token; each completion point checks activeLaunch
 * so results superseded by a newer connect are unregistered and dropped. A
 * failed start or a binder wait timeout dumps root_launch_debug.log to aid
 * diagnosis.
 */
object RootRemoteServiceConnector : RemoteServiceConnectorBackend {

    override val backend: RemoteBackend = RemoteBackend.ROOT

    /**
     * 等 binder 回投的上限；比 [RemoteServiceManager] 的 20s 兜底先行，超时
     * 顺带转储 root_launch_debug.log
     *
     * Upper bound for waiting on the returned binder; fires ahead of
     * [RemoteServiceManager]'s 20 s backstop and dumps
     * root_launch_debug.log on timeout.
     */
    private const val ROOT_BIND_TIMEOUT_MS = 15_000L

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(AppDispatchers.IO.limitedParallelism(1) + SupervisorJob())

    private lateinit var appContext: Context

    @Volatile
    private var activeLaunch: ActiveLaunch? = null

    /**
     * 进程级初始化：缓存 applicationContext，是 [connect] 的前置条件
     *
     * Process-level initialization; caches the applicationContext and is a
     * precondition of [connect].
     */
    fun initialize(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            appContext = context.applicationContext
        }
    }

    /**
     * 发起一次 root 连接
     *
     * 异步：启动命令在受限串行 IO 协程里执行，结果经 [callbacks] 回报。
     * 同一时刻只认最后一次 connect——被顶替的旧 token 在任何完成点都会被
     * 注销丢弃。
     *
     * Starts one Root connection.
     *
     * Asynchronous: the start command runs on a confined serial IO coroutine
     * and results come back through [callbacks]. Only the latest connect
     * counts — a superseded token is unregistered and dropped at every
     * completion point.
     */
    override fun connect(callbacks: RemoteServiceConnectorBackend.Callbacks) {
        ensureInitialized()

        val token = UUID.randomUUID().toString()
        val deferred = RootServiceBootstrapRegistry.register(token)
        val job = scope.launch {
            val startResult = withContext(AppDispatchers.IO) {
                startRemoteService(token)
            }
            val active = activeLaunch
            if (active?.token != token) {
                RootServiceBootstrapRegistry.unregister(token)
                return@launch
            }

            val startError = startResult.exceptionOrNull()
            if (startError != null) {
                activeLaunch = null
                RootServiceBootstrapRegistry.unregister(token)
                callbacks.onError(backend, startError)
                return@launch
            }

            runCatching {
                withTimeout(ROOT_BIND_TIMEOUT_MS) {
                    deferred.await()
                }
            }.onSuccess { binder ->
                if (activeLaunch?.token != token) {
                    RootServiceBootstrapRegistry.unregister(token)
                    return@onSuccess
                }

                try {
                    binder.linkToDeath({
                        Timber.e("Root process died unexpectedly.")
                        callbacks.onDisconnected(backend)
                    }, 0)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to link to death for root binder")
                }

                Timber.i("RemoteService connected by root bootstrap")
                callbacks.onConnected(backend, binder)
            }.onFailure { throwable ->
                RootServiceBootstrapRegistry.unregister(token)
                if (activeLaunch?.token == token) {
                    activeLaunch = null
                    dumpDebugLog()
                    callbacks.onError(backend, throwable)
                }
            }
        }

        activeLaunch = ActiveLaunch(token, job)
    }

    /**
     * 拆掉在途连接；已连上时顺带让特权服务进程自毁
     *
     * 取消在途任务并注销 token；[currentBinder] 非空则调 `destroy()` 让
     * `:root_service` 进程退出。
     *
     * Tears down the in-flight connection; when already connected, also ends
     * the privileged service process.
     *
     * Cancels the pending job and unregisters the token; with a non-null
     * [currentBinder], calls `destroy()` so the `:root_service` process exits.
     */
    override fun disconnect(currentBinder: IBinder?) {
        val active = activeLaunch
        activeLaunch = null
        active?.job?.cancel()
        active?.token?.let(RootServiceBootstrapRegistry::unregister)
        currentBinder?.let { binder ->
            runCatching {
                RemoteService.Stub.asInterface(binder)?.destroy()
            }.onFailure {
                Timber.w(it, "destroy root remote service failed")
            }
        }
    }

    /**
     * su 执行启动命令；退出码非 0 即失败，报错文本取 shell 错误输出，为空时
     * 退化为退出码
     *
     * Runs the start command under su; a non-zero exit code fails, with error
     * text taken from the shell's stderr and degraded to the exit code when
     * that is blank.
     */
    private fun startRemoteService(token: String): Result<Unit> {
        return runCatching {
            val command = buildStartCommand(token)
            val result = Shell.cmd(command).exec()
            if (result.code != 0) {
                error(result.err.joinToString("\n").ifBlank { "exit code=${result.code}" })
            }
        }.onFailure {
            Timber.e(it, "startRemoteService failed")
        }
    }

    /**
     * 拼 `liblauncher.so` 的完整命令行；参数一律单引号转义，整条后台执行
     *
     * Builds the full command line for `liblauncher.so`; every argument is
     * single-quote escaped and the whole line runs in the background.
     */
    private fun buildStartCommand(token: String): String {
        val processName = "${appContext.packageName}:root_service"
        val launcherFile = File(
            appContext.applicationInfo.nativeLibraryDir,
            "liblauncher.so"
        )
        check(launcherFile.exists()) { "root launcher not found: ${launcherFile.absolutePath}" }
        val launcherPath = launcherFile.absolutePath
        val uid = Process.myUid()
        val logFile = debugLogFile()
        return buildString {
            append(shellQuote(launcherPath))
            append(" --apk=")
            append(shellQuote(appContext.applicationInfo.sourceDir))
            append(" --process-name=")
            append(shellQuote(processName))
            append(" --starter-class=")
            append(shellQuote(RootServiceStarter::class.java.name))
            append(" --token=")
            append(shellQuote(token))
            append(" --package=")
            append(shellQuote(appContext.packageName))
            append(" --class=")
            append(shellQuote(RemoteServiceImpl::class.java.name))
            append(" --uid=")
            append(uid)
            // Android 14 起追加 --keep-root：launcher 默认会在 exec app_process 前
            // 把子进程降权为 shell 身份，该开关让特权进程保持 root 身份
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                append(" --keep-root")
            }
            append(" --log-file=")
            append(shellQuote(logFile.absolutePath))
            if (BuildConfig.DEBUG) {
                append(" --debug-name=")
                append(shellQuote(processName))
            }
            append(" >/dev/null 2>&1 &")
        }
    }

    /**
     * 启动诊断日志落点（debug/root_launch_debug.log）；目录不存在则建
     *
     * Where the launch diagnostic log lands (debug/root_launch_debug.log);
     * the directory is created when missing.
     */
    private fun debugLogFile(): File {
        val dir = AppPaths.DEBUG_DIR
        dir.mkdirs()
        return File(dir, "root_launch_debug.log")
    }

    /**
     * 把 root_launch_debug.log 读进 Timber；启动失败的现场直接进 logcat
     *
     * Reads root_launch_debug.log into Timber so the crash site lands in
     * logcat directly.
     */
    private fun dumpDebugLog() {
        val log = debugLogFile()
        if (!log.exists()) {
            Timber.e("Root launch debug log not found: %s", log.absolutePath)
            return
        }
        val content = runCatching { log.readText().trim() }.getOrNull()
        if (content.isNullOrBlank()) {
            Timber.e("Root launch debug log is empty (launcher may have crashed before opening it)")
        } else {
            Timber.e("Root launch debug log (%s):\n%s", log.absolutePath, content)
        }
    }

    /**
     * 未 [initialize] 即 connect 属装配错误，当场炸出来
     *
     * Connecting without [initialize] is a wiring error and fails fast here.
     */
    private fun ensureInitialized() {
        check(initialized.get()) { "RootRemoteServiceConnector is not initialized" }
    }

    /**
     * POSIX 单引号转义，防路径里的空格与单引号拆坏命令行
     *
     * POSIX single-quote escaping so spaces or quotes in paths cannot break
     * the command line.
     */
    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    /**
     * 一次在途的拉起；token 用于各完成点的身份比对
     *
     * One in-flight launch; the token keys the identity checks at each
     * completion point.
     */
    private data class ActiveLaunch(
        val token: String,
        val job: Job
    )
}
