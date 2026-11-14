package com.azurpilot.ghio.privileged

import android.content.pm.PackageManager
import com.azurpilot.ghio.domain.RemoteBackend
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 提权后端：优先使用内置 ADB 认证，否则经 rikka 的 Binder API 探活与授权。
 *
 * 内置路径以本机 ADB 认证结果为授权依据；外部路径用 `pingBinder()` 和
 * `checkSelfPermission()`。Sui（Magisk 模块）能提供同一 API，[initSui] 在启动时
 * 探测一次，结论存 [isSui]。
 *
 * binder 生死经 sticky 监听常驻观察，首次 addStateListener 或 requestPermission
 * 时装配；状态变化经 [RemoteAccessStateListener] 广播给协调器。
 *
 * The Shizuku privilege backend; prefers embedded ADB authentication and otherwise probes and
 * grants through rikka's Shizuku Binder API.
 *
 * The embedded path grants access from local ADB authentication; the external path uses
 * `pingBinder()` and `checkSelfPermission()`. Sui (a Magisk module) can serve the same API;
 * [initSui] probes it once at startup and caches the verdict in [isSui].
 *
 * Binder life and death are watched permanently via sticky listeners, wired on
 * the first addStateListener or requestPermission; state changes fan out to
 * the coordinator through [RemoteAccessStateListener].
 */
object ShizukuManager : RemoteAccessPermissionBackend {

    override val backend = RemoteBackend.SHIZUKU

    private val listeners = CopyOnWriteArraySet<RemoteAccessStateListener>()
    private val observingState = AtomicBoolean(false)
    private val suiInitialized = AtomicBoolean(false)

    /**
     * Sui 是否在提供 Shizuku API；[initSui] 之后定值
     *
     * Whether Sui is serving the Shizuku API; fixed after [initSui].
     */
    @Volatile
    var isSui: Boolean = false
        private set

    /**
     * 进程内一次性的 Sui 探测；init 失败按没有 Sui 处理
     *
     * One-shot Sui probe per process; a failed init counts as "no Sui".
     */
    fun initSui(packageName: String) {
        if (!suiInitialized.compareAndSet(false, true)) return
        isSui = try {
            Sui.init(packageName)
        } catch (e: Exception) {
            Timber.e(e, "Sui.init failed")
            false
        }
    }

    /** 与 [isAvailable] 等价的别名 / alias equivalent to [isAvailable] */
    fun isShizukuAvailable(): Boolean = isAvailable()

    /**
     * 内置 ADB 已认证或外部 Shizuku Binder 可达时可用；不进行网络 IO。
     *
     * Available when embedded ADB is authenticated or the external Shizuku Binder is reachable;
     * performs no network IO.
     */
    override fun isAvailable(): Boolean {
        if (EmbeddedAdbManager.isReady) return true
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Timber.e(e, "Error pinging Shizuku binder")
            false
        }
    }

    /**
     * 内置认证直接授权本 App，外部服务仍查询原授权；不可用时为 false。
     *
     * Embedded authentication grants this app directly; external services retain the original
     * permission check. False when neither is available.
     */
    override fun isGranted(): Boolean {
        if (EmbeddedAdbManager.isReady) return true
        if (!isAvailable()) {
            return false
        }
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 发起 Shizuku 授权（必要时弹授权界面），等待结果至多 15s
     *
     * pre-v11 的 Shizuku 无需授权，直接视为已授；已授权则原样返回。
     * 结果无论成败都广播一次状态变化。
     *
     * Requests the Shizuku grant (showing the dialog when needed), waiting up
     * to 15 s for the result.
     *
     * pre-v11 Shizuku needs no grant and counts as granted; an existing grant
     * returns as-is. The state change is broadcast regardless of the outcome.
     *
     * @return 授权是否到手 / whether the grant was obtained
     */
    override suspend fun requestPermission(): Boolean {
        ensureStateObservation()

        if (!isAvailable()) return false

        if (Shizuku.isPreV11()) {
            notifyStateChanged()
            return true
        }

        if (isGranted()) {
            notifyStateChanged()
            return true
        }

        val granted = try {
            withTimeoutOrNull(15_000) {
                callbackFlow {
                    val requestCode = (1000..9999).random()
                    val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
                        if (code == requestCode) {
                            val granted = result == PackageManager.PERMISSION_GRANTED
                            Timber.d("Permission result: code=%d, granted=%s", code, granted)
                            trySend(granted)
                            close()
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(listener)
                    Timber.d("Requesting Shizuku permission with code=%d", requestCode)
                    Shizuku.requestPermission(requestCode)
                    awaitClose {
                        Shizuku.removeRequestPermissionResultListener(listener)
                    }
                }.catch { e ->
                    Timber.e(e, "Error in permission request flow")
                    emit(false)
                }.first()
            }?.also { granted ->
                Timber.d("Shizuku permission %s", if (granted) "granted" else "denied")
            } ?: false
        } catch (e: Exception) {
            Timber.e(e, "Error requesting permission")
            false
        }

        notifyStateChanged()
        return granted
    }

    /**
     * 订阅状态变化；首次调用顺带装配 binder 生死观察
     *
     * Subscribes to state changes; the first call also wires the binder
     * life/death watchers.
     */
    override fun addStateListener(listener: RemoteAccessStateListener) {
        ensureStateObservation()
        listeners += listener
    }

    override fun removeStateListener(listener: RemoteAccessStateListener) {
        listeners -= listener
    }

    /**
     * 内置 ADB 认证变化后广播状态；调用线程可以是 IO 线程。
     *
     * Broadcasts embedded ADB authentication changes, including calls from IO threads.
     */
    fun refreshEmbeddedState() = notifyStateChanged()

    /**
     * 一次性装配 Shizuku binder 的到达与死亡监听；两者都触发状态广播
     *
     * One-time wiring of the Shizuku binder's arrival and death listeners;
     * both trigger a state broadcast.
     */
    private fun ensureStateObservation() {
        if (!observingState.compareAndSet(false, true)) {
            return
        }
        Shizuku.addBinderReceivedListenerSticky {
            Timber.d("Shizuku binder received")
            notifyStateChanged()
        }
        Shizuku.addBinderDeadListener {
            Timber.d("Shizuku binder dead")
            notifyStateChanged()
        }
    }

    /**
     * 广播状态变化；单个监听器抛异常不影响其余监听器
     *
     * Fans the state change out to listeners; one throwing never blocks the
     * rest.
     */
    private fun notifyStateChanged() {
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(backend) }
                .onFailure { Timber.w(it, "notifyStateChanged failed") }
        }
    }
}
