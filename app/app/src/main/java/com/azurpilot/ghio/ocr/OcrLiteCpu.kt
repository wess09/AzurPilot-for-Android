package com.azurpilot.ghio.ocr

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.Model
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 用同一份 LiteRT 权重创建独立 CPU 会话，按实际尺寸调整已校验的形状字段。
 *
 * 字节修改位置与原值由构建验证，禁止修改训练权重；私有临时文件随会话释放。
 * 不提供厂商 provider，且只请求 CPU。每次尺寸变化重建会话，批次由调用者拆分。
 *
 * Creates an isolated CPU session from the same LiteRT weights, specializing verified shape
 * fields. Build validation supplies byte offsets and expected values; trained weights never
 * change. Private temporary files live only with the session. Requests CPU without a vendor provider;
 * shape changes rebuild the session, and callers split batches.
 */
internal class OcrLiteCpu(
    file: File, spec: JsonObject, shape: LongArray, version: String, cacheDirectory: File,
) : AutoCloseable {
    private val environment = Environment.create()
    private var modelFile: OcrCpuModelFile? = null
    private var model: Model? = null
    private var compiled: CompiledModel? = null
    private var inputs: List<TensorBuffer> = emptyList()
    private var outputs: List<TensorBuffer> = emptyList()

    /** 实际输出尺寸，不从源模型的静态标注推断。 / Actual compiled output dimensions. */
    val outputShape: LongArray

    init {
        try {
            val bytes = file.readBytes()
            val view = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes)
            for (value in spec.getValue("cpu_shape_patches").jsonArray) {
                val patch = value.jsonObject
                val offset = patch.getValue("offset").jsonPrimitive.int
                val size = patch.getValue("size").jsonPrimitive.int
                require(size in listOf(1, 4) && offset >= 0 && offset.toLong() + size <= bytes.size)
                val expected = patch.getValue("expected").jsonPrimitive.int
                require((if (size == 4) view.getInt(offset) else view.get(offset).toInt()) == expected) {
                    "LiteRT CPU shape descriptor does not match the model"
                }
                val replacement = patch["value"]?.jsonPrimitive?.int ?: run {
                    val dimension = patch.getValue("dimension").jsonPrimitive.int
                    require(dimension in 2..3)
                    val divisor = patch["divisor"]?.jsonPrimitive?.int ?: 1
                    require(divisor > 0 && (patch["floor"]?.jsonPrimitive?.boolean == true || shape[dimension] % divisor == 0L))
                    val adjustment = patch["add"]?.jsonPrimitive?.int ?: 0
                    ((shape[dimension] + adjustment) / divisor).toInt()
                }
                if (size == 4) view.putInt(offset, replacement) else view.put(offset, replacement.toByte())
            }
            view.position(0)
            // 部分 ROM 拒绝通过 /proc/self/fd 重开 memfd；真实私有文件使用应用数据标签。
            modelFile = OcrCpuModelFile.create(cacheDirectory, spec.getValue("sha256").jsonPrimitive.content, view)
            model = Model.load(modelFile!!.file.absolutePath)
            check(OcrNative.countCustomOps(model!!, version) == 0) { "CPU model contains vendor dispatch operators" }
            val options = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = 2)
            }
            compiled = CompiledModel.create(model!!, options, environment)
            check(OcrNative.countCustomOps(model!!, version) == 0) { "CPU model unexpectedly compiled NPU partitions" }
            outputShape = OcrNative.outputShape(compiled!!, version) ?: error("LiteRT CPU output layout unavailable")
            require(outputShape.fold(1L, Long::times) <= 64 * 1024 * 1024 / 4)
            inputs = compiled!!.createInputBuffers()
            outputs = compiled!!.createOutputBuffers()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** 串行推理一个已转换布局的样本。 / Runs one sample in the conversion's tensor layout. */
    fun run(values: FloatArray): FloatArray {
        inputs.single().writeFloat(values)
        compiled!!.run(inputs, outputs)
        return outputs.single().readFloat().also {
            require(it.size.toLong() == outputShape.fold(1L, Long::times) && it.all(Float::isFinite))
        }
    }

    /** 关闭张量和模型后删除私有文件。 / Deletes the private file after closing tensors and models. */
    override fun close() {
        inputs.forEach { runCatching { it.close() } }
        outputs.forEach { runCatching { it.close() } }
        inputs = emptyList()
        outputs = emptyList()
        compiled?.let { runCatching { it.close() } }
        model?.let { runCatching { it.close() } }
        compiled = null
        model = null
        runCatching { environment.close() }
        modelFile?.let { runCatching { it.close() } }
        modelFile = null
    }
}
