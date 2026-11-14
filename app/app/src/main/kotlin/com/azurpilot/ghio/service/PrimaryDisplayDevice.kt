package com.azurpilot.ghio.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.azurpilot.ghio.constant.DefaultDisplayConfig.HEIGHT
import com.azurpilot.ghio.constant.DefaultDisplayConfig.WIDTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 在主屏上执行已授权的操作。桥接层串行调用；系统操作在主线程，像素转换在专用线程。
 *
 * Executes authorized operations on the default display. The bridge serializes calls;
 * framework operations run on main, pixel conversion on a dedicated thread.
 */
@RequiresApi(Build.VERSION_CODES.R)
internal class PrimaryDisplayDevice(private val isActive: () -> Boolean) {
    private var lastCaptureAt = -CAPTURE_INTERVAL_MS
    private var restartPackage: String? = null
    private val pixels = IntArray(WIDTH * HEIGHT)

    /**
     * 返回当前主屏焦点包名，供运行时判断游戏是否在前台。
     *
     * Returns the default display's focused package for the runtime's game detection.
     */
    suspend fun currentPackage(): String = withContext(Dispatchers.Main.immediate) {
        val service = service()
        if (service.isInteractive()) service.focusedPackage() else ""
    }

    /**
     * 在主屏打开指定 Activity；若之前请求停止，则重建它的任务栈。
     *
     * Opens the activity on the default display, recreating its task after a stop request.
     */
    suspend fun startApp(target: ComponentName) = withContext(Dispatchers.Main.immediate) {
        val service = service()
        service.requireInteractive()
        val restart = restartPackage == target.packageName
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = target
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (restart) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        service.startActivity(intent, options.toBundle())
        if (restart) restartPackage = null
    }

    /**
     * 游戏在主屏前台时退回桌面，并要求下次启动重建任务栈。
     *
     * Goes home if the game owns the main screen and requests a fresh task on the next start.
     */
    suspend fun stopApp(packageName: String) = withContext(Dispatchers.Main.immediate) {
        val service = service()
        if (service.focusedPackage() == packageName) {
            check(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
                "Could not leave the game on the primary display"
            }
        }
        if (isActive()) restartPackage = packageName
    }

    /**
     * 截取 1280×720 的 BGR 帧。在系统截图限流时短暂等待，始终交付新帧。
     *
     * Captures a 1280×720 BGR frame. Briefly waits on system throttling and always returns a new frame.
     */
    suspend fun capture(packageName: String): ByteArray {
        val result = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            var frame: ByteArray? = null
            while (frame == null) {
                delay((lastCaptureAt + CAPTURE_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
                frame = withContext(Dispatchers.Main.immediate) {
                    val service = service()
                    service.requireGame(packageName)
                    if (!service.hasLandscape720p()) return@withContext null
                    lastCaptureAt = SystemClock.uptimeMillis()
                    captureOnMain(service)
                }
                // 碧蓝航线从启动页切向横屏时不能交付纵向帧，也不能拉伸。
                if (frame == null) delay(CAPTURE_INTERVAL_MS)
            }
            frame
        } ?: throw PrimaryDisplayException("capture_unavailable", "No 1280x720 landscape screenshot")
        withContext(Dispatchers.Main.immediate) {
            val service = service()
            service.requireGame(packageName)
            check(service.hasLandscape720p()) { "Primary display must be 1280x720 in landscape" }
        }
        return result
    }

    /**
     * 在当前游戏的横屏像素坐标中执行触摸，等待系统确认完成。
     *
     * Performs a touch in the foreground game's landscape pixel space and waits for completion.
     */
    suspend fun gesture(packageName: String, x1: Int, y1: Int, x2: Int, y2: Int, duration: Long) {
        require(x1 in 0 until WIDTH && x2 in 0 until WIDTH && y1 in 0 until HEIGHT && y2 in 0 until HEIGHT) {
            "Touch coordinates outside the 1280x720 primary display"
        }
        require(duration in 1..GestureDescription.getMaxGestureDuration()) { "Invalid gesture duration" }
        withContext(Dispatchers.Main.immediate) {
            val service = service()
            service.requireGame(packageName)
            check(service.hasLandscape720p()) { "Primary display must be 1280x720 in landscape" }
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .setDisplayId(Display.DEFAULT_DISPLAY)
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
            val done = withTimeoutOrNull(duration + GESTURE_CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val sent = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(true)
                        override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(false)
                    }, null)
                    if (!sent) continuation.resume(false)
                }
            }
            check(done == true) { "Primary display gesture did not complete" }
        }
    }

    private fun service(): AccessibilityHelperService {
        check(isActive()) { "Primary display bridge stopped" }
        return checkNotNull(AccessibilityHelperService.connectedService) { "Accessibility service disconnected" }
    }

    private fun AccessibilityHelperService.isInteractive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun AccessibilityHelperService.requireInteractive() =
        check(isInteractive()) { "Keep the primary display on and unlocked" }

    private fun AccessibilityHelperService.requireGame(packageName: String) {
        check(has720p(this)) { "Primary display must be set to 720p" }
        requireInteractive()
        if (focusedPackage() != packageName) {
            throw PrimaryDisplayException("game_not_foreground", "Game is not in the foreground: $packageName")
        }
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityHelperService.focusedPackage(): String {
        // windows 只查询默认显示器，不能从当前聚焦的虚拟屏取包名。
        val windows = windows
        try {
            val root = windows.firstOrNull { it.isFocused }?.root ?: return ""
            try {
                return root.packageName?.toString().orEmpty()
            } finally {
                root.recycle()
            }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun AccessibilityHelperService.hasLandscape720p(): Boolean =
        size(this)?.let { it.x == WIDTH && it.y == HEIGHT } == true

    private suspend fun captureOnMain(service: AccessibilityHelperService): ByteArray? =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    screenshot.hardwareBuffer.use { buffer ->
                        if (!continuation.isActive) return
                        if (buffer.width != WIDTH || buffer.height != HEIGHT) {
                            continuation.resume(null)
                            return
                        }
                        try {
                            val bitmap = checkNotNull(Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace))
                            try {
                                val copy = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                                try {
                                    copy.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                                } finally {
                                    copy.recycle()
                                }
                            } finally {
                                bitmap.recycle()
                            }
                            val bgr = ByteArray(WIDTH * HEIGHT * 3)
                            var offset = 0
                            for (pixel in pixels) {
                                bgr[offset++] = pixel.toByte()
                                bgr[offset++] = (pixel ushr 8).toByte()
                                bgr[offset++] = (pixel ushr 16).toByte()
                            }
                            continuation.resume(bgr)
                        } catch (error: Exception) {
                            continuation.resumeWithException(error)
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        continuation.resume(null)
                    } else {
                        continuation.resumeWithException(
                            PrimaryDisplayException("capture_failed", "Accessibility screenshot failed ($errorCode)")
                        )
                    }
                }
            }
            service.takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, callback)
        }

    companion object {
        /**
         * 读取含旋转及系统尺寸覆盖的主屏像素范围，而非 Activity 窗口的大小。
         *
         * Reads the full logical display size with rotation and system overrides, not app bounds.
         */
        @Suppress("DEPRECATION")
        fun size(context: Context): Point? =
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.let { display -> Point().also(display::getRealSize) }

        /**
         * 校验主屏的逻辑尺寸，允许游戏启动前的竖屏。
         *
         * Validates the logical display size, allowing portrait before the game starts.
         */
        fun has720p(context: Context): Boolean = size(context)?.let {
            minOf(it.x, it.y) == HEIGHT && maxOf(it.x, it.y) == WIDTH
        } == true

        private val screenshotExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "azurpilot-primary-capture").apply { isDaemon = true }
        }

        /** Android 对同一无障碍服务的连续截图限流。 / Android throttles successive screenshots per service. */
        private val CAPTURE_INTERVAL_MS = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) 1_050L else 350L
        private const val CAPTURE_TIMEOUT_MS = 5_000L
        private const val GESTURE_CALLBACK_TIMEOUT_MS = 2_000L
    }
}

/**
 * 携带可供运行时识别的设备错误码。
 *
 * Carries device error codes recognized by the runtime.
 */
internal class PrimaryDisplayException(val code: String, message: String) : IllegalStateException(message)
