package com.azurpilot.ghio.ocr

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * 在正常应用域验证 CPU 文件生命周期、动态尺寸、关闭加速和 NPU 初始化失败回退。
 *
 * Checks CPU file lifetimes, dynamic shapes, disabled acceleration, and failed NPU
 * initialization fallback in the ordinary app domain. No root or policy changes are used.
 */
@RunWith(AndroidJUnit4::class)
class OcrCpuModelFileTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val manifest get() = base.assets.open("ocr/manifest.json").bufferedReader().use {
        Json.parseToJsonElement(it.readText()).jsonObject
    }
    private val spec get() = manifest.getValue("models").jsonArray.map { it.jsonObject }.first {
        it.getValue("asset").jsonPrimitive.content.endsWith("alocr-en-us-v2.6.nvc.onnx")
    }

    private fun isolatedContext(root: File, nativeDirectory: File? = null) = object : ContextWrapper(base) {
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "models").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getApplicationInfo() = ApplicationInfo(base.applicationInfo).apply {
            nativeDirectory?.let { nativeLibraryDir = it.absolutePath }
        }
    }

    private fun withRoot(action: (File) -> Unit) {
        val root = File.createTempFile("ocr-test-", "", base.cacheDir)
        check(root.delete() && root.mkdirs())
        try { action(root) } finally { root.deleteRecursively() }
    }

    @Test
    fun concurrentFilesKeepLiveModelsAndReclaimCrashRemnants() = withRoot { root ->
        val identity = "a".repeat(64)
        val directory = File(root, "ocr-cpu-sessions").apply { mkdirs() }
        val stale = File(directory, "$identity-crashed.tflite").apply { writeBytes(byteArrayOf(0)) }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val files = (0..7).map { index ->
                executor.submit<OcrCpuModelFile> {
                    OcrCpuModelFile.create(root, identity, ByteBuffer.wrap(byteArrayOf(index.toByte())))
                }
            }.map { it.get() }
            try {
                assertFalse(stale.exists())
                assertEquals(8, files.map { it.file.absolutePath }.toSet().size)
                files.forEachIndexed { index, model ->
                    assertArrayEquals(byteArrayOf(index.toByte()), model.file.readBytes())
                }
            } finally { files.forEach { it.close(); it.close() } }
            assertTrue(directory.listFiles()!!.none { it.extension == "tflite" })
        } finally { executor.shutdownNow() }
    }

    @Test
    fun failedModelLoadDeletesPatchedFile() = withRoot { root ->
        val conversion = spec.getValue("litert").jsonObject
        val source = File(root, "bad-model")
        val bytes = base.assets.open("ocr/${conversion.getValue("asset").jsonPrimitive.content}").use { it.readBytes() }
        // 只破坏 FlatBuffer 根偏移，shape 描述仍通过校验，失败发生在 LiteRT 加载阶段。
        bytes.fill(0x7f.toByte(), 0, 4)
        source.writeBytes(bytes)
        try {
            OcrLiteCpu(source, conversion, longArrayOf(1, 3, 48, 320), "2.1.0rc1", root).close()
            fail("Corrupt model was accepted")
        } catch (_: com.google.ai.edge.litert.LiteRtException) {
            assertTrue(File(root, "ocr-cpu-sessions").listFiles()!!.none { it.extension == "tflite" })
        }
    }

    @Test
    fun coldCpuSessionsAndRepeatedShapeChangesRemainStable() = withRoot { root ->
        val context = isolatedContext(root)
        val hash = spec.getValue("sha256").jsonPrimitive.content
        repeat(3) {
            OcrEngine(context, hardwareAccelerationEnabled = false).use { engine ->
                val evidence = engine.testCpu(hash)
                assertTrue(evidence.getValue("cpu_execution_verified").jsonPrimitive.boolean)
                assertEquals(0, evidence.getValue("cpu_npu_dispatch_partitions").jsonPrimitive.int)
                var previous: FloatArray? = null
                for (width in listOf(317, 320, 640, 320)) {
                    val result = engine.run(hash, longArrayOf(1, 3, 48, width.toLong()), FloatArray(3 * 48 * width), "diagnostic")
                    assertEquals("litert_cpu", result.backend)
                    assertTrue(result.values.all(Float::isFinite))
                    if (width == 320) {
                        previous?.let { assertArrayEquals(it, result.values, 0f) }
                        previous = result.values
                    }
                }
            }
            assertTrue(File(context.cacheDir, "ocr-cpu-sessions").listFiles()!!.none { it.extension == "tflite" })
        }
    }

    @Test
    fun unavailableNpuLibrariesFallBackToIndependentCpuSession() = withRoot { root ->
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val plugins = File(root, "broken-npu").apply { mkdirs() }
        for (vendor in listOf("Qualcomm", "MediaTek")) {
            File(plugins, "libLiteRtCompilerPlugin_$vendor.so").writeBytes(byteArrayOf(0))
            File(plugins, "libLiteRtDispatch_$vendor.so").writeBytes(byteArrayOf(0))
        }
        val context = isolatedContext(root, plugins)
        val hash = spec.getValue("sha256").jsonPrimitive.content
        val shape = longArrayOf(1, 3, 48, 320)
        val values = FloatArray(3 * 48 * 320)
        OcrEngine(context).use { engine ->
            assumeTrue(engine.status().getValue("vendor").jsonPrimitive.content in setOf("qualcomm", "mediatek"))
            val fallback = engine.run(hash, shape, values, "diagnostic")
            assertEquals("litert_cpu", fallback.backend)
            assertTrue(engine.status().getValue("fallbacks").jsonObject.containsKey(hash))
            engine.setHardwareAcceleration(false)
            assertArrayEquals(fallback.values, engine.run(hash, shape, values, "diagnostic").values, 0f)
        }
        assertTrue(File(context.cacheDir, "ocr-cpu-sessions").listFiles()!!.none { it.extension == "tflite" })
    }
}
