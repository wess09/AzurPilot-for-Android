package com.azurpilot.ghio.remoteaccess

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.detector.FinderPattern
import kotlin.math.hypot

/**
 * 保留有效配对密钥及二维码四角，坐标相对传入的灰度图，按顺时针排列。
 *
 * Holds a valid pairing secret and clockwise QR corners relative to the supplied grayscale frame.
 *
 * @property secret 一次性配对密钥。 / The one-time pairing secret.
 * @property corners 左上、右上、右下、左下角的 x/y 坐标。 / X/y pairs in top-left clockwise order.
 */
data class PairingQrDetection(val secret: String, val corners: FloatArray)

/**
 * 从普通二维码读取 AzurPilot 一次性配对密钥，不接受扫码内容指定服务器。
 *
 * Reads AzurPilot one-time secrets from QR codes without accepting a server from scanned data.
 */
class PairingQrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /** 解码灰度图；二维码本身支持旋转、透视和纠错。 / Decodes grayscale frames with QR rotation and error correction. */
    fun decode(pixels: ByteArray, width: Int, height: Int): String? = detect(pixels, width, height)?.secret

    /**
     * 解码并定位有效配对二维码；由单个分析线程调用。
     *
     * Decodes and locates a valid pairing QR code; called from a single analysis thread.
     */
    fun detect(pixels: ByteArray, width: Int, height: Int): PairingQrDetection? {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return null
        val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
        return try {
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            val pairingSecret = secret(result.text) ?: return null
            val points = result.resultPoints
            if (points.size < 3) return null
            val bottomLeft = points[0]
            val topLeft = points[1]
            val topRight = points[2]
            val horizontal = hypot(topRight.x - topLeft.x, topRight.y - topLeft.y)
            val vertical = hypot(bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y)
            if (horizontal <= 0f || vertical <= 0f) return null
            // ZXing 返回定位图案的中心；向外补足半个定位图案，白框才会包住整个二维码。
            val module = points.take(3).mapNotNull { (it as? FinderPattern)?.estimatedModuleSize }
                .takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: minOf(horizontal, vertical) / 30f
            val marginX = 4f * module / horizontal
            val marginY = 4f * module / vertical
            val axisX = floatArrayOf(topRight.x - topLeft.x, topRight.y - topLeft.y)
            val axisY = floatArrayOf(bottomLeft.x - topLeft.x, bottomLeft.y - topLeft.y)
            val corners = floatArrayOf(
                topLeft.x - axisX[0] * marginX - axisY[0] * marginY,
                topLeft.y - axisX[1] * marginX - axisY[1] * marginY,
                topRight.x + axisX[0] * marginX - axisY[0] * marginY,
                topRight.y + axisX[1] * marginX - axisY[1] * marginY,
                topRight.x + axisY[0] * (1f + marginY) + axisX[0] * marginX,
                topRight.y + axisY[1] * (1f + marginY) + axisX[1] * marginX,
                bottomLeft.x - axisX[0] * marginX + axisY[0] * marginY,
                bottomLeft.y - axisX[1] * marginX + axisY[1] * marginY,
            )
            PairingQrDetection(pairingSecret, corners)
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }

    companion object {
        private val payload = Regex("^azurpilot://pair\\?secret=([a-f0-9]{32})$")

        /** 只接受 AP 生成的固定配对格式。 / Accepts only the fixed AP pairing format. */
        fun secret(text: String): String? = payload.matchEntire(text)?.groupValues?.get(1)
    }
}
