package com.azurpilot.ghio.ocr

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.system.Os
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.Model
import com.google.ai.edge.litert.NpuAcceleratorProvider
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * 在 OCR 工作进程串行推理白名单模型；驱动错误和不支持的尺寸回退同一份 LiteRT 权重的 CPU。
 *
 * LiteRT 使用厂商插件，海思使用严格的 HiAI 会话，不注册 NNAPI。缓存最多两个模型。
 * 所有公开方法在服务工作线程调用，内部同步保证模型与缓冲区不会并发访问。
 *
 * Serializes allowlisted OCR inference in the OCR worker, falling back to CPU using the same LiteRT weights
 * for driver failures and unsupported sizes. LiteRT uses vendor plugins and Kirin uses strict
 * HiAI sessions, never registering NNAPI. At most two models are cached. Public methods run on workers;
 * internal synchronization protects sessions and buffers.
 */
class OcrEngine(
    private val context: Context,
    disabledModels: Map<String, String> = emptyMap(),
    private var hardwareAccelerationEnabled: Boolean = true,
) : AutoCloseable {
    private val manifest by lazy {
        context.assets.open("ocr/manifest.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }
    private val models by lazy {
        manifest.getValue("models").jsonArray.associate { value ->
            val model = value.jsonObject
            model.getValue("sha256").jsonPrimitive.content to model
        }
    }
    private val runtime by lazy {
        context.assets.open("ocr/runtime.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }
    private val sessions = LinkedHashMap<String, Session>(4, 0.75f, true)
    private val failures = disabledModels.toMutableMap()
    private val activity = mutableMapOf<String, JsonObject>()
    private val provider = BundledProvider(context)
    private var environment: Environment? = null
    private var gpuSoftmax: OcrGpuSoftmax? = null
    private var gpuSoftmaxFailure: String? = disabledModels.values.firstOrNull { "gpu_softmax" in it }
    private val trace = OcrTrace(context)
    private var mediatekAdapter: String? = null
    private var mediatekTargetPolicy: String? = null
    private var prunedModelCacheBytes = 0L
    private val modelDirectory by lazy {
        val directory = File(context.noBackupFilesDir, "ocr-models").apply {
            check(isDirectory || mkdirs()) { "Could not create OCR model cache" }
        }
        val retained = models.values.flatMap { spec ->
            listOfNotNull(spec["litert"]?.jsonObject, spec["mnn"]?.jsonObject)
                .map { it.getValue("sha256").jsonPrimitive.content }
        }.toSet()
        // 单一 OCR 工作进程首次加载权重前清理；只移除本缓存的旧哈希，不触碰 AP 模型。
        directory.listFiles().orEmpty().filter {
            Regex("[a-f0-9]{64}(\\.tmp)?").matches(it.name) && it.name !in retained &&
                Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }.forEach { file ->
            val bytes = file.length()
            if (file.delete()) prunedModelCacheBytes += bytes
        }
        if (prunedModelCacheBytes > 0) trace.record("*", "model_cache_pruned", prunedModelCacheBytes.toString())
        directory
    }

    init {
        // 工作进程启动即回收旧源权重，不必等到用户再次发起 OCR。
        modelDirectory
    }

    /**
     * 返回匹配权重的原始元数据；未知哈希拒绝调用。
     *
     * Returns original metadata or rejects unknown hashes.
     */
    @Synchronized
    fun describe(hash: String): JsonObject = models[hash] ?: error("OCR model is not bundled in this APK")

    /**
     * 串行切换后端选择；关闭时释放硬件会话，保留 CPU 缓存和真实错误记录。
     *
     * Switches the backend choice serially. Disabling frees hardware sessions while retaining
     * CPU caches and actual failure records. Subsequent AP calls use the same choice.
     */
    @Synchronized
    fun setHardwareAcceleration(enabled: Boolean) {
        hardwareAccelerationEnabled = enabled
        if (!enabled) {
            sessions.values.forEach { session ->
                session.closeNpu()
                session.backend = if (session.cpu != null) "litert_cpu" else "uninitialized"
            }
            environment?.close()
            environment = null
            gpuSoftmax?.close()
            gpuSoftmax = null
        }
        trace.record("*", "hardware_acceleration_setting", enabled.toString())
    }

    /**
     * 返回运行库、会话和回退原因；JIT 分区证据不代替硬件性能分析。
     *
     * Reports libraries, sessions, and fallback reasons; JIT partition evidence does not
     * replace hardware profiling.
     */
    @Synchronized
    fun status(): JsonObject = buildJsonObject {
        put("api_version", 1)
        put("model_format_version", 2)
        put("cpu_runtime", "litert")
        put("source_weights_bundled", false)
        gpuSoftmaxFailure?.let { put("gpu_softmax_error", it) }
        put("worker_process_isolated", true)
        put("worker_pid", android.os.Process.myPid())
        put("runtime", "litert_and_hiai")
        put("runtime_versions", buildJsonObject {
            put("litert", runtime.getValue("litert"))
            put("qnn", runtime.getValue("qnn"))
            put("neuropilot", runtime.getValue("neuropilot"))
            context.assets.open("ocr/hiai-runtime.json").bufferedReader().use {
                val hiai = Json.parseToJsonElement(it.readText()).jsonObject
                put("mnn_source_commit", hiai.getValue("mnn_source_commit"))
                put("hiai_source_commit", hiai.getValue("hiai_source_commit"))
            }
        })
        put("nnapi", false)
        put("hardware_acceleration_enabled", hardwareAccelerationEnabled)
        put("model_cache_pruned_bytes", prunedModelCacheBytes)
        put("validation", buildJsonObject {
            put("finite_outputs_required", true)
            put("matching_output_shape_required", true)
            put("cpu_reference_check_on_business_calls", false)
            put("score_difference_triggers_fallback", false)
            put("matching_timestep_predictions_required", false)
        })
        trace.latest?.let { put("last_stage", it) }
        put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE)
        put("vendor", provider.vendor)
        mediatekAdapter?.let { put("mediatek_adapter_library", it) }
        mediatekTargetPolicy?.let { put("mediatek_target_policy", it) }
        val bundled = provider.isLibraryReady() || provider.isHiaiReady()
        val recognizers = models.filterValues { it["npu_supported"]?.jsonPrimitive?.boolean == true }.keys
        val disabled = recognizers.count { it in failures || "*" in failures }
        put("npu_libraries_bundled", bundled)
        // 兼容已有 AP 客户端；此字段只表示库存在，实际运行证据在 sessions 中。
        put("npu_libraries_ready", bundled)
        put("npu_state", when {
            !hardwareAccelerationEnabled -> "user_disabled"
            !bundled -> "unavailable"
            disabled == recognizers.size -> "disabled"
            sessions.values.any { it.inferenceSucceeded && it.hasNpuEvidence } -> "verified"
            disabled > 0 -> "partially_disabled"
            else -> "untested"
        })
        put("models", JsonArray(models.keys.map(::JsonPrimitive)))
        put("model_status", buildJsonArray {
            models.forEach { (hash, spec) ->
                val last = activity[hash]
                add(buildJsonObject {
                    put("model_sha256", hash)
                    put("name", spec.getValue("asset").jsonPrimitive.content.substringAfterLast('/'))
                    put("testable", spec["kind"]?.jsonPrimitive?.content == "recognition")
                    put("npu_supported", spec["npu_supported"] ?: JsonPrimitive(false))
                    put("backend", last?.get("backend") ?: JsonPrimitive("uninitialized"))
                    put("ap_requests", last?.get("ap_requests") ?: JsonPrimitive(0))
                    put("diagnostic_requests", last?.get("diagnostic_requests") ?: JsonPrimitive(0))
                    last?.get("last_ms")?.let { put("last_ms", it) }
                    last?.get("shape")?.let { put("shape", it) }
                    last?.get("cpu_reason")?.let { put("cpu_reason", it) }
                    last?.get("last_ap_call")?.let { put("last_ap_call", it) }
                    last?.get("last_diagnostic_call")?.let { put("last_diagnostic_call", it) }
                    (failures[hash] ?: failures["*"])?.let { put("error", it) }
                })
            }
        })
        put("sessions", buildJsonArray {
            sessions.forEach { (hash, session) ->
                add(buildJsonObject {
                    put("model_sha256", hash)
                    put("backend", session.backend)
                    put("npu_requested", session.lite != null || session.hiai != 0L)
                    put("npu_dispatch_partitions", session.partitions)
                    put("litert_all_ops_delegated", session.allOpsDelegated)
                    put("hiai_npu_only_session_ready", session.hiai != 0L)
                    put("npu_delegation_verified", session.inferenceSucceeded && session.hasNpuEvidence)
                    put("npu_hardware_profile_verified", false)
                    put("terminal_softmax_backend", session.softmaxBackend)
                    session.softmaxMs?.let { put("terminal_softmax_ms", it) }
                    session.gpuRenderer?.let { put("gpu_renderer", it) }
                })
            }
        })
        put("fallbacks", buildJsonObject { failures.forEach { (key, value) -> put(key, value) } })
    }

    /**
     * 推理 NCHW FP32 张量并返回原 ONNX 输出形状；批次拆成单图，宽度不改写。
     *
     * Runs an NCHW FP32 tensor and returns ONNX-shaped output; batches split into individual
     * images without changing their width.
     */
    @Synchronized
    fun run(hash: String, shape: LongArray, values: FloatArray, source: String = "ap"): Output {
        val started = System.nanoTime()
        val result = infer(hash, shape, values)
        val previous = activity[hash]
        val call = buildJsonObject {
            put("backend", result.backend)
            put("hardware_acceleration_enabled", hardwareAccelerationEnabled)
            put("last_ms", (System.nanoTime() - started) / 1_000_000.0)
            put("timestamp_ms", System.currentTimeMillis())
            put("shape", JsonArray(shape.map(::JsonPrimitive)))
            put("npu_dispatch_partitions", if (result.backend.startsWith("litert_npu"))
                sessions[hash]?.partitions ?: 0 else 0)
            sessions[hash]?.let { session ->
                put("terminal_softmax_backend", session.softmaxBackend)
                session.softmaxMs?.let { put("terminal_softmax_ms", it) }
                session.gpuRenderer?.let { put("gpu_renderer", it) }
            }
            if (result.backend == "litert_cpu") {
                put("cpu_reason", when {
                    !hardwareAccelerationEnabled -> "user_disabled"
                    hash in failures || "*" in failures -> "npu_failed"
                    describe(hash)["kind"]?.jsonPrimitive?.content == "detector" -> "detector"
                    describe(hash)["npu_supported"]?.jsonPrimitive?.boolean != true -> "model_not_accelerated"
                    shape[2] != 48L || shape[3] != 320L -> "dynamic_shape"
                    else -> "npu_unavailable"
                })
            }
        }
        val callKey = if (source == "ap") "last_ap_call" else "last_diagnostic_call"
        activity[hash] = buildJsonObject {
            call.forEach { (key, value) -> put(key, value) }
            listOf("last_ap_call", "last_diagnostic_call").forEach { key ->
                if (key == callKey) put(key, call) else previous?.get(key)?.let { put(key, it) }
            }
            put("ap_requests", (previous?.get("ap_requests")?.jsonPrimitive?.longOrNull ?: 0) +
                if (source == "ap") 1 else 0)
            put("diagnostic_requests", (previous?.get("diagnostic_requests")?.jsonPrimitive?.longOrNull ?: 0) +
                if (source != "ap") 1 else 0)
        }
        return result
    }

    private fun infer(hash: String, shape: LongArray, values: FloatArray): Output {
        val spec = describe(hash)
        trace.activeModel(hash)
        require(shape.size == 4 && shape[0] in 1..16 && shape[1] == 3L)
        require(shape[2] in 1..2048 && shape[3] in 1..4096)
        require(shape.fold(1L, Long::times) == values.size.toLong())
        require(values.all(Float::isFinite))
        val inputSpec = spec.getValue("inputs").jsonArray.single().jsonObject
        inputSpec.getValue("shape").jsonArray.forEachIndexed { index, dimension ->
            dimension.jsonPrimitive.longOrNull?.let { require(shape[index] == it) }
        }
        val conversion = spec["litert"]?.jsonObject
        if (conversion != null && spec["kind"]?.jsonPrimitive?.content == "recognition") {
            // 时间轴步长为 8；先限输出大小，避免运行库分配超过协议上限的张量。
            val classes = conversion.getValue("output_shape").jsonArray.last().jsonPrimitive.long
            require(shape[0] * ((shape[3] + 7) / 8) * classes <= 64 * 1024 * 1024 / 4) {
                "OCR output exceeds the API limit"
            }
        }
        val session = sessions.getOrPut(hash) {
            while (sessions.size >= 2) {
                val eldest = sessions.entries.iterator().next()
                eldest.value.close()
                sessions.remove(eldest.key)
            }
            Session(spec)
        }
        if (hardwareAccelerationEnabled && spec["npu_supported"]?.jsonPrimitive?.boolean == true && conversion != null && shape[2] == 48L && shape[3] == 320L &&
            (provider.isLibraryReady() || provider.isHiaiReady())
            && hash !in failures && "*" !in failures) {
            try {
                return if (provider.isHiaiReady()) {
                    runHiai(session, spec.getValue("mnn").jsonObject, shape, values)
                } else runLite(session, conversion, shape, values)
            } catch (error: Exception) {
                failures[hash] = error.message?.take(300) ?: error.javaClass.simpleName
                trace.record(hash, "npu_failed", failures[hash])
                trace.captureNative(hash)
                session.closeNpu()
                Timber.w(error, "OCR NPU failed; falling back to LiteRT CPU")
            } catch (error: LinkageError) {
                failures[hash] = error.message?.take(300) ?: "NPU library linkage failed"
                trace.record(hash, "npu_linkage_failed", failures[hash])
                trace.captureNative(hash)
                session.closeNpu()
                Timber.w(error, "OCR NPU libraries unavailable")
            }
        }
        return runCpu(session, shape, values)
    }

    /**
     * 用内置数字图片测量所选识别器，与独立 LiteRT CPU 比较；不计为 AP 业务调用。
     *
     * 在服务工作线程执行；预热三次，再取二十次平均。CPU 对照为独立 LiteRT 会话，
     * 只请求 CPU，不注册厂商 provider，验证图中没有 NPU dispatch 分区。
     * 测试报告数值差与字符一致性，不因此切换后端；不能代替游戏识别质量或硬件性能验证。
     *
     * Benchmarks a selected recognizer with a bundled digit image against isolated LiteRT CPU.
     * Runs on a server worker without counting AP business calls. Cold time includes initialization;
     * steady time averages twenty runs after three warmups. A separate LiteRT baseline requests only CPU without a vendor provider and verifies
     * that no NPU dispatch partitions were compiled.
     * Score differences and character agreement are diagnostic only, never a fallback gate.
     * Success does not prove game accuracy or hardware performance.
     */
    @Synchronized
    fun test(hash: String): JsonObject {
        return testModel(hash)
    }

    private fun testModel(hash: String): JsonObject {
        require(describe(hash)["kind"]?.jsonPrimitive?.content == "recognition") { "Only bundled recognition models can be tested" }
        val values = testValues()
        val shape = longArrayOf(1, 3, 48, 320)
        val coldStart = System.nanoTime()
        var output = run(hash, shape, values, "benchmark")
        val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0
        repeat(WARMUP_RUNS) { output = run(hash, shape, values, "benchmark") }
        val samples = (1..MEASURED_RUNS).map {
            val start = System.nanoTime()
            output = run(hash, shape, values, "benchmark")
            (System.nanoTime() - start) / 1_000_000.0
        }
        val cpu = benchmarkCpu(hash, shape, values)
        val reference = cpu.output
        check(output.shape.contentEquals(reference.shape)) { "OCR test output shape differs" }
        val maxError = output.values.indices.maxOf { kotlin.math.abs(output.values[it] - reference.values[it]) }
        val classes = output.shape.last().toInt()
        val equalPredictions = output.values.indices.step(classes).all { offset ->
            (0 until classes).maxBy { output.values[offset + it] } ==
                (0 until classes).maxBy { reference.values[offset + it] }
        }
        return buildJsonObject {
            put("model_sha256", hash)
            put("backend", output.backend)
            put("shape", JsonArray(shape.map(::JsonPrimitive)))
            put("cold_ms", coldMs)
            put("steady_ms", samples.average())
            put("steady_samples_ms", JsonArray(samples.map(::JsonPrimitive)))
            put("warmup_runs", WARMUP_RUNS)
            put("measured_runs", MEASURED_RUNS)
            cpu.evidence.forEach { (key, value) -> put(key, value) }
            put("max_abs_error", maxError)
            put("score_difference_triggers_fallback", false)
            put("character_predictions_equal", equalPredictions)
            put("terminal_softmax_backend", sessions[hash]?.softmaxBackend ?: "in_model")
            sessions[hash]?.softmaxMs?.let { put("terminal_softmax_ms", it) }
            sessions[hash]?.gpuRenderer?.let { put("gpu_renderer", it) }
            gpuSoftmaxFailure?.let { put("gpu_softmax_error", it) }
            put("npu_dispatch_partitions", sessions[hash]?.partitions ?: 0)
            put("npu_hardware_profile_verified", false)
        }
    }

    /**
     * 独立创建 LiteRT CPU 会话，不加载 NPU 模型，也不改业务会话与调用计数。
     *
     * Creates an isolated CPU using the same LiteRT weights session without loading NPU models or changing
     * business sessions and request counters.
     */
    @Synchronized
    fun testCpu(hash: String): JsonObject {
        require(describe(hash)["kind"]?.jsonPrimitive?.content == "recognition") { "Only bundled recognition models can be tested" }
        val shape = longArrayOf(1, 3, 48, 320)
        val cpu = benchmarkCpu(hash, shape, testValues())
        return buildJsonObject {
            put("model_sha256", hash)
            put("backend", cpu.output.backend)
            put("shape", JsonArray(shape.map(::JsonPrimitive)))
            put("warmup_runs", WARMUP_RUNS)
            put("measured_runs", MEASURED_RUNS)
            cpu.evidence.forEach { (key, value) -> put(key, value) }
        }
    }

    /**
     * 单独验证 GPU Softmax，包含上传、同步与读回，不依赖 NPU 编译成功。
     *
     * Tests GPU Softmax including upload, synchronization, and readback, independently of NPU.
     * Uses synthetic logits and an isolated CPU reference; never counts as an AP call.
     */
    @Synchronized
    fun testGpuSoftmax(hash: String): JsonObject {
        check(hardwareAccelerationEnabled) { "Hardware acceleration is disabled by the user" }
        check(gpuSoftmaxFailure == null) { "GPU Softmax previously failed: $gpuSoftmaxFailure" }
        val conversion = describe(hash).getValue("litert").jsonObject
        check(conversion["output_postprocess"]?.jsonPrimitive?.content == "softmax") {
            "This model does not use an external terminal Softmax"
        }
        val classes = conversion.getValue("output_shape").jsonArray.last().jsonPrimitive.int
        val logits = FloatArray(40 * classes) { ((it * 37L) % 997).toFloat() / 10f - 50f }
        val reference = logits.copyOf()
        check(OcrNative.softmaxInPlace(reference, classes))
        trace.activeModel(hash)
        trace.record(hash, "gpu_softmax_test")
        val gpu = gpuSoftmax ?: OcrGpuSoftmax().also { gpuSoftmax = it }
        var output = logits.copyOf()
        repeat(WARMUP_RUNS) { gpu.run(logits.copyOf(), classes) }
        val samples = (1..MEASURED_RUNS).map {
            output = logits.copyOf()
            val started = System.nanoTime()
            gpu.run(output, classes)
            (System.nanoTime() - started) / 1_000_000.0
        }
        return buildJsonObject {
            put("model_sha256", hash)
            put("test_mode", "terminal_softmax_only")
            put("gpu_execution_verified", true)
            put("gpu_renderer", gpu.renderer)
            put("shape", buildJsonArray { add(1); add(40); add(classes) })
            put("steady_ms", samples.average())
            put("steady_samples_ms", JsonArray(samples.map(::JsonPrimitive)))
            put("measurement_scope", "upload_compute_synchronize_readback_and_validation")
            put("max_abs_error", output.indices.maxOf { kotlin.math.abs(output[it] - reference[it]) })
            put("business_call_proof", false)
        }
    }

    /**
     * 独立测试 NPU 与 GPU 联合委派；保留 CPU 算子回退，暂不修改业务后端策略。
     *
     * Benchmarks combined NPU/GPU delegation with CPU operator fallback in an isolated session,
     * without changing the production backend policy. Requesting GPU does not prove its use.
     */
    @Synchronized
    fun testMixed(hash: String): JsonObject {
        check(hardwareAccelerationEnabled) { "Hardware acceleration is disabled by the user" }
        val spec = describe(hash)
        val conversion = spec["litert"]?.jsonObject ?: error("Only recognition models support this test")
        check(provider.isLibraryReady() && !provider.isHiaiReady()) { "Combined LiteRT delegation unavailable for this vendor" }
        check(hash !in failures && "*" !in failures) { "A previous hardware runtime failure blocks this model" }
        trace.activeModel(hash)
        val shape = longArrayOf(1, 3, 48, 320)
        val values = testValues()
        val session = Session(spec)
        try {
            val started = System.nanoTime()
            var output = runLite(session, conversion, shape, values, useGpu = true)
            val cold = (System.nanoTime() - started) / 1_000_000.0
            repeat(WARMUP_RUNS) { output = runLite(session, conversion, shape, values, useGpu = true) }
            val samples = (1..MEASURED_RUNS).map {
                val start = System.nanoTime()
                output = runLite(session, conversion, shape, values, useGpu = true)
                (System.nanoTime() - start) / 1_000_000.0
            }
            val cpu = benchmarkCpu(hash, shape, values)
            return buildJsonObject {
                put("model_sha256", hash)
                put("backend", output.backend)
                put("gpu_requested", true)
                put("gpu_execution_verified", false)
                put("npu_dispatch_partitions", session.partitions)
                put("litert_all_ops_delegated", session.allOpsDelegated)
                put("cold_ms", cold)
                put("steady_ms", samples.average())
                put("steady_samples_ms", JsonArray(samples.map(::JsonPrimitive)))
                put("shape", JsonArray(shape.map(::JsonPrimitive)))
                put("warmup_runs", WARMUP_RUNS)
                put("measured_runs", MEASURED_RUNS)
                cpu.evidence.forEach { (key, value) -> put(key, value) }
                put("production_policy_changed", false)
                put("power_efficiency_verified", false)
            }
        } finally {
            trace.captureNative(hash)
            session.close()
        }
    }

    private fun benchmarkCpu(hash: String, shape: LongArray, values: FloatArray): CpuBenchmark {
        val session = Session(describe(hash))
        try {
            var output = runCpu(session, shape, values)
            repeat(WARMUP_RUNS) { output = runCpu(session, shape, values) }
            val samples = (1..MEASURED_RUNS).map {
                val start = System.nanoTime()
                output = runCpu(session, shape, values)
                (System.nanoTime() - start) / 1_000_000.0
            }
            return CpuBenchmark(output, buildJsonObject {
                put("cpu_backend", "litert_cpu")
                put("cpu_runtime", runtime.getValue("litert"))
                put("cpu_requested_accelerators", buildJsonArray { add("CPU") })
                put("cpu_vendor_provider_registered", false)
                put("cpu_npu_dispatch_partitions", 0)
                put("cpu_execution_verified", true)
                put("cpu_intra_op_threads", 2)
                put("cpu_session_isolated", true)
                put("measurement_scope", "warm_model_inference_with_tensor_copy_softmax_and_finite_check")
                put("cpu_ms", samples.average())
                put("cpu_samples_ms", JsonArray(samples.map(::JsonPrimitive)))
            })
        } finally {
            session.close()
        }
    }

    private fun testValues(): FloatArray {
        val bitmap = context.assets.open("ocr/test/sample.png").use(BitmapFactory::decodeStream)
            ?: error("OCR test image is missing")
        val pixels = IntArray(48 * 320)
        try {
            require(bitmap.width == 320 && bitmap.height == 48)
            bitmap.getPixels(pixels, 0, 320, 0, 0, 320, 48)
        } finally {
            bitmap.recycle()
        }
        return FloatArray(pixels.size * 3) { index ->
            val shift = (2 - index / pixels.size) * 8
            ((pixels[index % pixels.size] ushr shift) and 255) / 127.5f - 1f
        }
    }

    private fun runLite(session: Session, conversion: JsonObject, shape: LongArray, values: FloatArray,
        useGpu: Boolean = false): Output {
        if (conversion["output_postprocess"]?.jsonPrimitive?.content != "softmax") {
            session.softmaxBackend = "in_model"
            session.softmaxMs = null
            session.gpuRenderer = null
        }
        val hash = session.spec.getValue("sha256").jsonPrimitive.content
        if (session.lite == null) {
            if (provider.vendor == "mediatek") {
                // 钉版 dispatch 用全局 adapter；关闭一个会话会使其他会话持有的引用失效。
                // 保留 CPU 缓存，但 MTK 的 NPU 会话必须先关闭再切换模型。
                sessions.values.filter { it !== session && it.lite != null }.forEach { other ->
                    trace.record(other.spec.getValue("sha256").jsonPrimitive.content,
                        "litert_session_close", "mediatek_model_switch")
                    other.closeNpu()
                    other.backend = if (other.cpu != null) "litert_cpu" else "uninitialized"
                }
                if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.equals("MT6985", ignoreCase = true)) {
                    // 此芯片的 SDK 8 编译目标缺失会主动 abort；限制 MDLA，余下算子由 LiteRT 分区。
                    Os.setenv("MTKNN_ADAPTER_CONFIG_TARGET", "mdla", true)
                    mediatekTargetPolicy = "mdla"
                    trace.record(hash, "mediatek_target_policy", mediatekTargetPolicy)
                }
                trace.record(hash, "mediatek_driver_probe")
                OcrNative.mediatekDriverError(mediatekTargetPolicy == "mdla")?.let { error(it.take(300)) }
                trace.record(hash, "mediatek_adapter_probe")
                mediatekAdapter = OcrNative.mediatekAdapterLibrary()
                trace.record(hash, "mediatek_adapter_selected", mediatekAdapter)
            }
            trace.record(hash, "litert_environment")
            val env = environment ?: Environment.create(provider).also { environment = it }
            trace.record(hash, "litert_model_load")
            val model = Model.load(materialize(conversion).absolutePath).also { session.model = it }
            val version = runtime.getValue("litert").jsonPrimitive.content
            trace.record(hash, "litert_graph_before_compile")
            check(OcrNative.countCustomOps(model, version) == 0) { "OCR conversion contains unexpected custom operators or diagnostics are unavailable" }
            trace.record(hash, "litert_npu_compile")
            session.lite = CompiledModel.create(
                model, if (useGpu) CompiledModel.Options(Accelerator.NPU, Accelerator.GPU)
                    else CompiledModel.Options(Accelerator.NPU), env,
            )
            session.partitions = OcrNative.countCustomOps(model, version)
            val acceleration = OcrNative.compiledModelAcceleration(session.lite!!, version)
            session.allOpsDelegated = acceleration == 1
            trace.record(hash, "litert_graph_after_compile",
                "custom_ops=${session.partitions}, all_ops_delegated=$acceleration, gpu_requested=$useGpu")
            trace.captureNative(hash)
            // CPU delegate 也能令全部委派为真；只有实际 dispatch 分区可作为 NPU 证据。
            check(session.partitions > 0) {
                "LiteRT NPU delegation unavailable: custom_ops=${session.partitions}, " +
                    "all_ops_delegated=$acceleration; see debug/ocr/native-${hash.take(12)}.log"
            }
            trace.record(hash, "litert_buffers")
            session.inputs = session.lite!!.createInputBuffers()
            session.outputs = session.lite!!.createOutputBuffers()
            check(Accelerator.NPU in env.getAvailableAccelerators()) { "LiteRT reports no NPU accelerator" }
        }
        val expectedShape = conversion.getValue("output_shape").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
        val sampleSize = expectedShape.fold(1L, Long::times).toInt()
        val batch = shape[0].toInt()
        require(sampleSize.toLong() * batch <= 64 * 1024 * 1024 / 4) { "OCR output exceeds the API limit" }
        val output = FloatArray(sampleSize * batch)
        val inputSize = 3 * 48 * 320
        val firstRun = !session.inferenceSucceeded
        if (firstRun) trace.record(hash, "litert_first_inference")
        repeat(batch) { index ->
            val source = values.copyOfRange(index * inputSize, (index + 1) * inputSize)
            val input = if (conversion.getValue("input_layout").jsonPrimitive.content == "nhwc") {
                FloatArray(inputSize) { position -> source[(position % 3) * 48 * 320 + position / 3] }
            } else source
            session.inputs!!.single().writeFloat(input)
            session.lite!!.run(session.inputs!!, session.outputs!!)
            val result = session.outputs!!.single().readFloat()
            val nonFinite = result.count { !it.isFinite() }
            if (firstRun) trace.record(hash, "litert_output",
                "expected_floats=$sampleSize, actual_floats=${result.size}, non_finite=$nonFinite")
            require(result.size == sampleSize) {
                "LiteRT OCR output size differs: expected=$sampleSize, actual=${result.size}"
            }
            require(nonFinite == 0) { "LiteRT OCR output contains $nonFinite non-finite values" }
            if (conversion["output_postprocess"]?.jsonPrimitive?.content == "softmax") {
                terminalSoftmax(session, result, expectedShape.last().toInt())
            }
            result.copyInto(output, index * sampleSize)
        }
        expectedShape[0] = shape[0]
        session.inferenceSucceeded = true
        if (firstRun) trace.record(hash, "litert_inference_succeeded")
        session.backend = if (useGpu) "litert_npu_gpu_requested_with_cpu_fallback"
            else if (session.softmaxBackend == "gpu") "litert_npu_gpu_softmax_with_cpu_fallback"
            else "litert_npu_with_cpu_fallback"
        return Output(expectedShape, output, session.backend)
    }

    private fun runHiai(session: Session, conversion: JsonObject, shape: LongArray, values: FloatArray): Output {
        session.softmaxBackend = "in_model"
        session.softmaxMs = null
        session.gpuRenderer = null
        val expectedShape = conversion.getValue("output_shape").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
        if (session.hiai == 0L) {
            trace.record(session.spec.getValue("sha256").jsonPrimitive.content, "hiai_model_load")
            session.hiai = OcrHiaiNative.create(materialize(conversion).absolutePath,
                context.applicationInfo.nativeLibraryDir, expectedShape.last().toInt())
            check(session.hiai != 0L) { "HiAI did not create an NPU session" }
        }
        val sampleSize = expectedShape.fold(1L, Long::times).toInt()
        val batch = shape[0].toInt()
        require(sampleSize.toLong() * batch <= 64 * 1024 * 1024 / 4)
        val output = FloatArray(sampleSize * batch)
        val inputSize = 3 * 48 * 320
        repeat(batch) { index ->
            val result = OcrHiaiNative.run(session.hiai,
                values.copyOfRange(index * inputSize, (index + 1) * inputSize))
            require(result.size == sampleSize && result.all(Float::isFinite))
            result.copyInto(output, index * sampleSize)
        }
        expectedShape[0] = shape[0]
        session.inferenceSucceeded = true
        session.backend = "hiai_npu"
        return Output(expectedShape, output, session.backend)
    }

    private fun terminalSoftmax(session: Session, logits: FloatArray, classes: Int) {
        val hash = session.spec.getValue("sha256").jsonPrimitive.content
        val started = System.nanoTime()
        if (gpuSoftmaxFailure == null) {
            // GPU 读回失败可能部分改写数组；只有完整校验成功才替换原 logits。
            val probabilities = logits.copyOf()
            try {
                trace.record(hash, "gpu_softmax_run")
                val gpu = gpuSoftmax ?: OcrGpuSoftmax().also { gpuSoftmax = it }
                gpu.run(probabilities, classes)
                probabilities.copyInto(logits)
                session.softmaxBackend = "gpu"
                session.gpuRenderer = gpu.renderer
                session.softmaxMs = (System.nanoTime() - started) / 1_000_000.0
                trace.record(hash, "gpu_softmax_succeeded", gpu.renderer)
                return
            } catch (error: Exception) {
                gpuSoftmaxFailure = error.message?.take(300) ?: error.javaClass.simpleName
            } catch (error: LinkageError) {
                gpuSoftmaxFailure = error.message?.take(300) ?: "GPU linkage failed"
            }
            trace.record(hash, "gpu_softmax_failed", gpuSoftmaxFailure)
            runCatching { gpuSoftmax?.close() }
            gpuSoftmax = null
        }
        check(OcrNative.softmaxInPlace(logits, classes)) { "CPU Softmax rejected OCR logits" }
        session.softmaxBackend = "cpu_after_gpu_error"
        session.gpuRenderer = null
        session.softmaxMs = (System.nanoTime() - started) / 1_000_000.0
    }

    private fun runCpu(session: Session, shape: LongArray, values: FloatArray): Output {
        val conversion = session.spec.getValue("litert").jsonObject
        val singleShape = shape.copyOf().apply { this[0] = 1 }
        if (session.cpu == null || session.cpuShape?.contentEquals(singleShape) != true) {
            session.cpu?.close()
            session.cpu = null
            trace.record(session.spec.getValue("sha256").jsonPrimitive.content, "litert_cpu_compile")
            try {
                session.cpu = OcrLiteCpu(materialize(conversion), conversion, singleShape,
                    runtime.getValue("litert").jsonPrimitive.content, context.cacheDir)
            } catch (error: Exception) {
                throw OcrCpuInitializationException(error)
            } catch (error: LinkageError) {
                throw OcrCpuInitializationException(error)
            }
            session.cpuShape = singleShape
        }
        val cpu = session.cpu!!
        val expected = cpu.outputShape.copyOf()
        val sampleSize = expected.fold(1L, Long::times).toInt()
        val batch = shape[0].toInt()
        require(sampleSize.toLong() * batch <= 64 * 1024 * 1024 / 4)
        val output = FloatArray(sampleSize * batch)
        val plane = (shape[2] * shape[3]).toInt()
        val inputSize = 3 * plane
        session.softmaxBackend = "in_model_cpu"
        session.softmaxMs = null
        session.gpuRenderer = null
        repeat(batch) { index ->
            val source = values.copyOfRange(index * inputSize, (index + 1) * inputSize)
            val input = if (conversion.getValue("input_layout").jsonPrimitive.content == "nhwc") {
                FloatArray(inputSize) { position -> source[(position % 3) * plane + position / 3] }
            } else source
            val result = cpu.run(input)
            if (conversion["output_postprocess"]?.jsonPrimitive?.content == "softmax") {
                val started = System.nanoTime()
                check(OcrNative.softmaxInPlace(result, expected.last().toInt())) { "CPU Softmax rejected OCR logits" }
                session.softmaxBackend = "cpu"
                session.softmaxMs = (System.nanoTime() - started) / 1_000_000.0
            }
            if (conversion["output_layout"]?.jsonPrimitive?.content == "nhwc") {
                val channels = expected[3].toInt()
                val outputPlane = sampleSize / channels
                repeat(sampleSize) { position ->
                    output[index * sampleSize + position] = result[(position % outputPlane) * channels + position / outputPlane]
                }
            } else result.copyInto(output, index * sampleSize)
        }
        val outputShape = if (conversion["output_layout"]?.jsonPrimitive?.content == "nhwc")
            longArrayOf(shape[0], expected[3], expected[1], expected[2]) else expected.apply { this[0] = shape[0] }
        session.backend = "litert_cpu"
        return Output(outputShape, output, session.backend)
    }

    private fun materialize(spec: JsonObject): File {
        val hash = spec.getValue("sha256").jsonPrimitive.content
        val directory = modelDirectory
        val target = File(directory, hash)
        if (!target.isFile) {
            val temporary = File(directory, "$hash.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("ocr/${spec.getValue("asset").jsonPrimitive.content}").use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == hash) { "Bundled OCR model checksum mismatch" }
            check(temporary.renameTo(target)) { "Could not install OCR model" }
        }
        return target
    }

    /**
     * 释放所有会话，调用方须先停止服务。
     *
     * Releases all sessions after the caller stops the service.
     */
    @Synchronized
    override fun close() {
        sessions.values.forEach(Session::close)
        sessions.clear()
        environment?.close()
        environment = null
        gpuSoftmax?.close()
        gpuSoftmax = null
    }

    /**
     * 保存输出张量及实际请求的后端；线程局部，服务负责编码。
     *
     * Holds output tensors and the requested backend; thread-local, encoded by the server.
     * @property shape 原 ONNX 输出维度。 / Original ONNX output dimensions.
     * @property values 连续 FP32 数据。 / Contiguous FP32 data.
     * @property backend CPU 或带 CPU 回退的 NPU 路径。 / CPU or NPU with CPU fallback.
     */
    data class Output(val shape: LongArray, val values: FloatArray, val backend: String)

    private data class CpuBenchmark(val output: Output, val evidence: JsonObject)

    private companion object {
        const val WARMUP_RUNS = 3
        const val MEASURED_RUNS = 20
    }

    private class Session(val spec: JsonObject) : AutoCloseable {
        var lite: CompiledModel? = null
        var hiai = 0L
        var model: Model? = null
        var cpu: OcrLiteCpu? = null
        var cpuShape: LongArray? = null
        var softmaxBackend = "in_model"
        var softmaxMs: Double? = null
        var gpuRenderer: String? = null
        var inputs: List<com.google.ai.edge.litert.TensorBuffer>? = null
        var outputs: List<com.google.ai.edge.litert.TensorBuffer>? = null
        var inferenceSucceeded = false
        var partitions = 0
        var allOpsDelegated = false
        val hasNpuEvidence get() = partitions > 0 || hiai != 0L
        var backend = "uninitialized"
        fun closeNpu() {
            if (hiai != 0L) {
                runCatching { OcrHiaiNative.close(hiai) }
                hiai = 0L
            }
            inputs?.forEach { runCatching { it.close() } }
            outputs?.forEach { runCatching { it.close() } }
            lite?.let { runCatching { it.close() } }
            model?.let { runCatching { it.close() } }
            inputs = null
            outputs = null
            lite = null
            model = null
            inferenceSucceeded = false
            partitions = 0
            allOpsDelegated = false
        }
        override fun close() {
            closeNpu()
            cpu?.close()
            cpu = null
        }
    }

    private class BundledProvider(private val context: Context) : NpuAcceleratorProvider {
        val vendor: String
            get() {
                // 部分升级到 Android 12 的设备没有填 SoC 字段，仍需使用其硬件标识。
                val identifiers = buildList {
                    add(Build.HARDWARE.lowercase())
                    if (Build.VERSION.SDK_INT >= 31) {
                        add(Build.SOC_MANUFACTURER.lowercase())
                        add(Build.SOC_MODEL.lowercase())
                    }
                }
                return when {
                    identifiers.any {
                        it.contains("qualcomm") || it == "qti" || it.contains("qcom") ||
                            Regex("(?:sm|sdm|msm)\\d{3,5}.*").matches(it)
                    } -> "qualcomm"
                    identifiers.any { it.contains("mediatek") || Regex("mt\\d{4}.*").matches(it) } -> "mediatek"
                    identifiers.any { it.contains("kirin") || it.contains("hisi") } -> "hiai"
                    identifiers.any { it.contains("xring") || it.contains("xiaomi") } -> "xring_unavailable"
                    else -> "unsupported"
                }
            }
        override fun isDeviceSupported() = Build.VERSION.SDK_INT >= 31 && vendor in setOf("qualcomm", "mediatek")
        override fun isLibraryReady(): Boolean {
            if (!isDeviceSupported()) return false
            val suffix = if (vendor == "qualcomm") "Qualcomm" else "MediaTek"
            return listOf("libLiteRtCompilerPlugin_$suffix.so", "libLiteRtDispatch_$suffix.so").all {
                File(context.applicationInfo.nativeLibraryDir, it).isFile
            }
        }
        override suspend fun downloadLibrary() = Unit
        fun isHiaiReady(): Boolean = vendor == "hiai" && Build.VERSION.SDK_INT >= 29 &&
            listOf("libocrhiai.so", "libMNN.so", "libMNN_Backend_HiAI.so", "libhiai.so",
                "libhiai_ir.so", "libhiai_ir_build.so", "libhiai_model_compatible.so",
                "libhiai_enhance.so").all { File(context.applicationInfo.nativeLibraryDir, it).isFile }
        override fun getLibraryDir(): String {
            val source = File(context.applicationInfo.nativeLibraryDir)
            val directory = File(context.noBackupFilesDir, "ocr-npu/$vendor").apply { mkdirs() }
            val names = if (vendor == "qualcomm") {
                listOf("libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so",
                    "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so") +
                    listOf(68, 69, 73, 75, 79, 81).flatMap { version ->
                        listOf("libQnnHtpV${version}Skel.so", "libQnnHtpV${version}Stub.so")
                    }
            } else {
                listOf("libLiteRtCompilerPlugin_MediaTek.so", "libLiteRtDispatch_MediaTek.so",
                    "libneuronusdk_adapter.mtk.so", "libneuronusdk_adapter.9.mtk.so")
            }
            // 只暴露本厂商插件，避免另一厂商的离线编译器抢先接管模型；链接仍指向安装目录。
            names.forEach { name ->
                val target = File(source, name).toPath()
                check(Files.isRegularFile(target)) { "Missing NPU runtime library: $name" }
                val link = File(directory, name).toPath()
                if (!Files.isSymbolicLink(link) || Files.readSymbolicLink(link) != target) {
                    Files.deleteIfExists(link)
                    Files.createSymbolicLink(link, target)
                }
            }
            return directory.absolutePath
        }
    }
}
