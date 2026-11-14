package com.azurpilot.ghio.privileged

import android.content.Context
import android.os.IBinder
import android.os.Process
import com.azurpilot.ghio.AppDispatchers
import com.azurpilot.ghio.RemoteService
import com.azurpilot.ghio.constant.AppPaths
import com.azurpilot.ghio.domain.RemoteBackend
import com.azurpilot.ghio.remote.RemoteServiceImpl
import com.azurpilot.ghio.root.RootServiceBootstrapRegistry
import com.azurpilot.ghio.root.ShellServiceStarter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * 在 IO 线程经内置 ADB 启动专属 shell 服务；不请求外部 Shizuku 授权。
 *
 * 每次连接使用新的一次性 token，复用仅接受 shell/root 调用者的 Binder 回传端点。
 * 解绑或被新连接替代时注销 token，迟到的子进程因回传失败自行退出。
 *
 * Starts a dedicated shell service over embedded ADB on IO threads without an external Shizuku
 * grant. Each attempt uses a fresh one-time token and the existing shell/root-only Binder
 * handoff. Unbinding or replacement revokes the token, so late children exit on failed handoff.
 */
object EmbeddedAdbServiceConnector : RemoteServiceConnectorBackend {
    override val backend = RemoteBackend.SHIZUKU
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.IO)
    private lateinit var context: Context
    private val lock = Any()
    private var active: Launch? = null

    /**
     * 在 App 启动主线程保存 Context。
     *
     * Stores the Context on the app startup main thread.
     */
    fun initialize(appContext: Context) { context = appContext.applicationContext }

    /**
     * 主线程发起连接，IO 线程等待 Binder，最长 15 秒。
     *
     * Starts on main and awaits the Binder on IO for at most 15 seconds.
     */
    override fun connect(callbacks: RemoteServiceConnectorBackend.Callbacks) {
        synchronized(lock) {
            disconnect(null)
            val token = UUID.randomUUID().toString()
            val pending = RootServiceBootstrapRegistry.register(token)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    EmbeddedAdbManager.openClient().use { client ->
                        val output = StringBuilder()
                        client.shellCommand(startCommand(token)) { bytes ->
                            check(output.length + bytes.size <= 4_096) { "Unexpected launcher output" }
                            output.append(bytes.toString(Charsets.UTF_8))
                        }
                        check(output.toString().trim() == "AZURPILOT_STARTED") {
                            "Embedded launcher failed: ${output.toString().take(1_024)}"
                        }
                    }
                    val binder = withTimeout(15_000) { pending.await() }
                    withContext(Dispatchers.Main) {
                        if (synchronized(lock) { active?.token == token }) callbacks.onConnected(backend, binder)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    val current = synchronized(lock) {
                        if (active?.token == token) {
                            active = null
                            true
                        } else false
                    }
                    if (current) {
                        revoke(token, pending, null)
                        EmbeddedAdbManager.invalidate()
                        withContext(Dispatchers.Main) { callbacks.onError(backend, error) }
                    }
                } finally {
                    RootServiceBootstrapRegistry.unregister(token)
                }
            }
            active = Launch(token, job, pending)
            job.start()
        }
    }

    /**
     * 注销在途 token，并销毁已连接服务。
     *
     * Revokes an in-flight token and destroys the connected service.
     */
    override fun disconnect(currentBinder: IBinder?) {
        synchronized(lock) {
            active?.let {
                revoke(it.token, it.pending, currentBinder)
                it.job.cancel()
            }
            active = null
        }
        currentBinder?.let {
            runCatching { RemoteService.Stub.asInterface(it).destroy() }
                .onFailure { error -> Timber.w(error, "Embedded shell shutdown failed") }
        }
    }

    private fun revoke(token: String, pending: CompletableDeferred<IBinder>, currentBinder: IBinder?) {
        RootServiceBootstrapRegistry.unregister(token)
        // attach 已消费 token 但尚未 complete 时，等待该 deferred 可接住迟到的 Binder。
        scope.launch {
            try {
                val lateBinder = pending.await()
                if (lateBinder !== currentBinder) RemoteService.Stub.asInterface(lateBinder).destroy()
            } catch (_: CancellationException) {
                // 未消费的 token 已被注销，无子进程可以通过它回传。
            } catch (error: Exception) {
                Timber.d(error, "Late embedded shell cleanup completed")
            }
        }
    }

    private fun startCommand(token: String): String {
        val launcher = File(context.applicationInfo.nativeLibraryDir, "liblauncher.so")
        check(launcher.exists()) { "Shell launcher missing" }
        val log = File(AppPaths.DEBUG_DIR.apply { mkdirs() }, "embedded_adb_launch.log")
        val args = listOf(
            launcher.absolutePath,
            "--apk=${context.applicationInfo.sourceDir}",
            "--process-name=${context.packageName}:adb_service",
            "--starter-class=${ShellServiceStarter::class.java.name}",
            "--token=$token",
            "--package=${context.packageName}",
            "--class=${RemoteServiceImpl::class.java.name}",
            "--uid=${Process.myUid()}",
            "--log-file=${log.absolutePath}",
            "--daemonize",
        )
        return args.joinToString(" ") { "'${it.replace("'", "'\"'\"'")}'" } +
            " && printf 'AZURPILOT_STARTED'"
    }

    private data class Launch(val token: String, val job: Job, val pending: CompletableDeferred<IBinder>)
}
