package com.azurpilot.ghio.remoteaccess

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.azurpilot.ghio.theme.AzurPilotTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * 全屏扫描配对二维码，将分析帧坐标映射到预览；完成识别动画后只返回一次密钥。
 * 相机帧与识别截图仅在内存处理，退出时释放本页绑定的相机。
 *
 * Scans pairing QR codes full screen, maps analysis coordinates to preview, and returns a secret
 * once after recognition feedback. Frames and captured crops stay in memory; exit releases this
 * page's camera use cases.
 */
class QrScanActivity : ComponentActivity() {
    private var allowed by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var flashAvailable by mutableStateOf(false)
    private var torchEnabled by mutableStateOf(false)
    private var capture by mutableStateOf<QrScanCapture?>(null)
    private val executor = Executors.newSingleThreadExecutor()
    private val completed = AtomicBoolean(false)
    private val decoder = PairingQrDecoder()
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var previewView: PreviewView? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var lastFrame = 0L
    private var luminance = ByteArray(0)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        allowed = cameraPermissionGranted()
        if (!allowed) permission.launch(Manifest.permission.CAMERA)
        setContent {
            AzurPilotTheme(darkTheme = true) {
                QrScanScreen(
                    allowed = allowed,
                    failed = failed,
                    flashAvailable = flashAvailable,
                    torchEnabled = torchEnabled,
                    capture = capture,
                    onPreviewReady = ::bindCamera,
                    onClose = ::finish,
                    onToggleTorch = ::toggleTorch,
                    onRequestPermission = { permission.launch(Manifest.permission.CAMERA) },
                    onCaptureFinished = {
                        if (!isFinishing && !isDestroyed &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            capture?.let {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_PAIRING_SECRET, it.secret))
                                finish()
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        allowed = cameraPermissionGranted()
    }

    private fun cameraPermissionGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun bindCamera(view: PreviewView) {
        previewView = view
        failed = false
        val pending = ProcessCameraProvider.getInstance(this)
        pending.addListener({
            if (isDestroyed || !allowed || previewView !== view) return@addListener
            runCatching {
                val owner = pending.get()
                provider = owner
                preview?.let { owner.unbind(it) }
                analysis?.let { owner.unbind(it) }
                val rotation = view.display.rotation
                val output = Preview.Builder().setTargetRotation(rotation).build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val resolution = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
                val reader = ImageAnalysis.Builder().setTargetRotation(rotation)
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                preview = output
                analysis = reader
                reader.setAnalyzer(executor, ::analyze)
                // 预览和分析共用全屏视口，避免只扫描中间区域或让识别框偏离二维码。
                val group = UseCaseGroup.Builder().addUseCase(output).addUseCase(reader)
                    .setViewPort(requireNotNull(view.viewPort)).build()
                camera = owner.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                flashAvailable = camera?.cameraInfo?.hasFlashUnit() == true
            }.onFailure {
                failed = true
                Timber.w(it, "Cannot open pairing QR camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(TransformExperimental::class)
    private fun analyze(image: ImageProxy) {
        try {
            val now = image.imageInfo.timestamp
            if (completed.get() || now - lastFrame < 100_000_000L) return
            lastFrame = now
            val crop = image.cropRect
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val start = buffer.position()
            val width = crop.width()
            val height = crop.height()
            if (luminance.size != width * height) luminance = ByteArray(width * height)
            // 视口裁剪保留全部可见区域；按实际行填充和像素步长复制亮度数据。
            for (index in luminance.indices) {
                luminance[index] = buffer.get(start + (crop.top + index / width) * plane.rowStride +
                    (crop.left + index % width) * plane.pixelStride)
            }
            val result = decoder.detect(luminance, width, height) ?: return
            val source = ImageProxyTransformFactory().apply {
                isUsingCropRect = true
            }.getOutputTransform(image)
            runOnUiThread { recognize(result, source) }
        } catch (error: RuntimeException) {
            Timber.w(error, "Cannot analyze pairing QR frame")
        } finally {
            image.close()
        }
    }

    @OptIn(TransformExperimental::class)
    private fun recognize(result: PairingQrDetection, source: OutputTransform) {
        if (isDestroyed || completed.get() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val view = previewView ?: return
        val target = view.outputTransform ?: return
        val points = result.corners.copyOf()
        CoordinateTransform(source, target).mapPoints(points)
        val xs = points.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = points.filterIndexed { index, _ -> index % 2 == 1 }
        val bounds = RectF(xs.min(), ys.min(), xs.max(), ys.max())
        bounds.inset(-6f * resources.displayMetrics.density, -6f * resources.displayMetrics.density)
        if (!bounds.intersect(0f, 0f, view.width.toFloat(), view.height.toFloat()) ||
            bounds.width() < 8f || bounds.height() < 8f || !completed.compareAndSet(false, true)) return
        analysis?.clearAnalyzer()
        val snapshot = view.bitmap
        val thumbnail = snapshot?.let {
            Bitmap.createBitmap(it, bounds.left.toInt(), bounds.top.toInt(),
                bounds.width().toInt().coerceAtLeast(1), bounds.height().toInt().coerceAtLeast(1))
        }
        if (thumbnail !== snapshot) snapshot?.recycle()
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY)
        capture = QrScanCapture(result.secret,
            Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), thumbnail?.asImageBitmap())
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        val enabled = !torchEnabled
        val request = activeCamera.cameraControl.enableTorch(enabled)
        torchEnabled = enabled
        request.addListener({
            runCatching { request.get() }.onFailure {
                torchEnabled = false
                Timber.w(it, "Cannot toggle pairing QR torch")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        camera?.cameraControl?.enableTorch(false)
        torchEnabled = false
        super.onStop()
    }

    override fun onDestroy() {
        analysis?.clearAnalyzer()
        preview?.let { provider?.unbind(it) }
        analysis?.let { provider?.unbind(it) }
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        /** 仅在本应用中返回一次性配对密钥。 / Returns the one-time secret within this app only. */
        const val EXTRA_PAIRING_SECRET = "pairing_secret"
    }
}
