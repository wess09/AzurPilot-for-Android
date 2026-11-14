package com.azurpilot.ghio.service

import android.content.ComponentName
import android.net.LocalServerSocket
import android.os.Build
import android.os.Process
import com.azurpilot.ghio.BuildConfig
import com.azurpilot.ghio.constant.DefaultDisplayConfig.HEIGHT
import com.azurpilot.ghio.constant.DefaultDisplayConfig.WIDTH
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * 在 App 域内为 PRoot 提供主屏设备操作。监听线程串行处理同 UID 的请求。
 *
 * Provides primary-display operations to PRoot within the app domain. A single listener thread
 * handles requests from the same UID in order.
 */
internal class PrimaryDisplayBridge(private val primarySelected: () -> Boolean) {
    @Volatile
    private var listener: LocalServerSocket? = null
    private val device by lazy { PrimaryDisplayDevice(primarySelected) }

    /** 返回进程内的监听状态。 / Reports whether the in-process listener is running. */
    val isRunning: Boolean get() = listener != null

    /**
     * 在 IO 线程幂等绑定监听器；绑定失败可在下次探测时重试。
     *
     * Binds on IO, retrying on the next probe if binding fails.
     */
    @Synchronized
    fun start() {
        if (listener != null) return
        val socket = try {
            LocalServerSocket(SOCKET_NAME)
        } catch (error: IOException) {
            Timber.w(error, "Could not bind the primary display bridge")
            return
        }
        listener = socket
        thread(isDaemon = true, name = "azurpilot-primary-bridge") { accept(socket) }
    }

    private fun accept(server: LocalServerSocket) {
        try {
            while (true) {
                val client = server.accept()
                try {
                    client.use {
                        if (it.peerCredentials.uid == Process.myUid()) {
                            it.soTimeout = SOCKET_TIMEOUT_MS
                            val line = readLine(BufferedInputStream(it.inputStream)) ?: return@use
                            serve(line, it.outputStream)
                        }
                    }
                } catch (error: IOException) {
                    Timber.d("Primary display bridge connection closed: %s", error.message)
                }
            }
        } catch (error: IOException) {
            Timber.w(error, "Primary display bridge listener closed")
        } finally {
            runCatching { server.close() }
            listener = null
        }
    }

    private fun serve(line: String, output: OutputStream) {
        val response = JSONObject().put("ok", true)
        var frame: ByteArray? = null
        try {
            val request = JSONObject(line)
            val method = request.getString("method")
            if (method == "ping") {
                response.put("primary", primarySelected())
            } else {
                check(primarySelected()) { "Primary display mode is not selected" }
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Primary display needs Android 11 or later" }
                runBlocking {
                    when (method) {
                        "app_current" -> response.put("package", device.currentPackage())
                        "app_start" -> {
                            val packageName = readPackage(request)
                            val activity = request.getString("activity")
                            require(COMPONENT_PATTERN.matches(activity)) { "Invalid activity name" }
                            device.startApp(ComponentName.createRelative(packageName, activity))
                        }
                        "app_stop" -> device.stopApp(readPackage(request))
                        "screencap" -> {
                            val bytes = device.capture(readPackage(request))
                            response.put("width", WIDTH).put("height", HEIGHT)
                                .put("channels", 3).put("length", bytes.size)
                            frame = bytes
                        }
                        "click" -> {
                            val x = request.getInt("x")
                            val y = request.getInt("y")
                            device.gesture(readPackage(request), x, y, x, y, 50)
                        }
                        "swipe" -> device.gesture(
                            readPackage(request),
                            request.getInt("x1"), request.getInt("y1"),
                            request.getInt("x2"), request.getInt("y2"),
                            request.getLong("duration"),
                        )
                        else -> error("Unknown primary display method: $method")
                    }
                }
            }
        } catch (error: Exception) {
            response.put("ok", false)
                .put("error", error.message ?: "Primary display request failed")
            if (error is PrimaryDisplayException) response.put("code", error.code)
        }
        output.write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
        frame?.let(output::write)
    }

    private fun readPackage(request: JSONObject): String = request.getString("package").also {
        require(PACKAGE_PATTERN.matches(it)) { "Invalid package name" }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < MAX_REQUEST_BYTES) {
            when (val next = input.read()) {
                -1 -> return null
                '\n'.code -> return line.toString(Charsets.UTF_8.name())
                else -> line.write(next)
            }
        }
        return null
    }

    companion object {
        /** App 私有桥的 Unix 地址，与安装包名隔离。 / Unix bridge name namespaced by application ID. */
        val SOCKET_NAME = "${BuildConfig.APPLICATION_ID}.primary.v1"
        private const val MAX_REQUEST_BYTES = 4096
        private const val SOCKET_TIMEOUT_MS = 3_000
        private val PACKAGE_PATTERN = Regex("[A-Za-z][\\w]*(?:\\.[A-Za-z][\\w]*)+")
        private val COMPONENT_PATTERN = Regex("\\.?[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")
    }
}
