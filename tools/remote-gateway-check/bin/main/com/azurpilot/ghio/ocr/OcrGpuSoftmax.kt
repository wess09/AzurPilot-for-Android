package com.azurpilot.ghio.ocr

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES31
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 在独立 EGL 上下文用 FP32 计算末尾 Softmax，不包含 OCR 权重。
 *
 * 每行先减最大值，再并行归约分母。调用者串行访问；每次恢复原线程的 EGL 上下文，
 * 因而工作线程改变时仍可复用。驱动错误抛给调用者，不能把软件渲染器报告为 GPU。
 *
 * Computes terminal FP32 Softmax in a private EGL context without OCR weights.
 * Subtracts row maxima before parallel denominator reduction. Callers serialize access;
 * each call restores the thread's previous EGL context so workers may change. Driver errors
 * propagate to the caller, and software renderers never count as GPU execution.
 */
internal class OcrGpuSoftmax : AutoCloseable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val buffers = IntArray(2)
    private var classesLocation = -1
    private var maxBytes = 0

    /** GPU 驱动名称，成功初始化后可用于执行记录。 / GPU driver name after initialization. */
    var renderer: String? = null
        private set

    /**
     * 原地替换有限 logits 为概率；耗时包含上传、执行、同步与读回。
     *
     * Replaces finite logits with probabilities in place. Includes upload, execution,
     * synchronization, and readback; throws on invalid output or any driver failure.
     */
    fun run(values: FloatArray, classes: Int) {
        require(classes > 0 && values.isNotEmpty() && values.size % classes == 0)
        require(values.size <= 64 * 1024 * 1024 / 4 && values.all(Float::isFinite))
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        try {
            if (display == EGL14.EGL_NO_DISPLAY) initialize()
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "GPU EGL context unavailable" }
            if (program == 0) compile()
            val bytes = values.size * 4
            require(bytes <= maxBytes) { "GPU Softmax exceeds the storage-buffer limit" }
            val rows = values.size / classes
            val limit = IntArray(1)
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, limit, 0)
            require(rows <= limit[0]) { "GPU Softmax exceeds the work-group limit" }
            val upload = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            upload.asFloatBuffer().put(values)
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[0])
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, upload, GLES31.GL_STREAM_DRAW)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[1])
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, null, GLES31.GL_STREAM_READ)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])
            GLES31.glUseProgram(program)
            GLES31.glUniform1i(classesLocation, classes)
            GLES31.glDispatchCompute(rows, 1, 1)
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT or GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            checkGl("dispatch")
            // 映射会等待 GPU 写完；不能只计 dispatch 的异步提交时间。
            val mapped = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes,
                GLES31.GL_MAP_READ_BIT) as? ByteBuffer ?: error("GPU Softmax readback failed")
            try {
                mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().get(values)
            } finally {
                check(GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)) { "GPU Softmax buffer corrupted" }
            }
            checkGl("readback")
            require(values.all { it.isFinite() && it in 0f..1f }) { "GPU Softmax returned invalid probabilities" }
            repeat(rows) { row ->
                var total = 0.0
                for (column in 0 until classes) total += values[row * classes + column]
                require(kotlin.math.abs(total - 1.0) < 0.001) { "GPU Softmax probabilities do not sum to one" }
            }
        } finally {
            if (previousDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext)
            } else if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
        }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "GPU EGL display unavailable" }
        check(EGL14.eglInitialize(display, null, 0, null, 0)) { "GPU EGL initialization failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0) { "GPU ES3 config unavailable" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "GPU ES3 context creation failed" }
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "GPU offscreen surface unavailable" }
    }

    private fun compile() {
        renderer = GLES31.glGetString(GLES31.GL_RENDERER) ?: error("GPU renderer unavailable")
        val lower = renderer!!.lowercase()
        check(listOf("swiftshader", "llvmpipe", "softpipe", "software").none(lower::contains)) {
            "Software renderer cannot provide hardware Softmax: $renderer"
        }
        val version = IntArray(2)
        GLES31.glGetIntegerv(GLES31.GL_MAJOR_VERSION, version, 0)
        GLES31.glGetIntegerv(GLES31.GL_MINOR_VERSION, version, 1)
        check(version[0] > 3 || version[0] == 3 && version[1] >= 1) { "GPU requires OpenGL ES 3.1" }
        val limit = IntArray(1)
        GLES31.glGetIntegerv(GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE, limit, 0)
        maxBytes = limit[0]
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        check(shader != 0) { "GPU compute shader unavailable" }
        try {
            GLES31.glShaderSource(shader, SHADER)
            GLES31.glCompileShader(shader)
            GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, limit, 0)
            check(limit[0] != 0) { "GPU Softmax shader: ${GLES31.glGetShaderInfoLog(shader).take(200)}" }
            program = GLES31.glCreateProgram()
            GLES31.glAttachShader(program, shader)
            GLES31.glLinkProgram(program)
            GLES31.glGetProgramiv(program, GLES31.GL_LINK_STATUS, limit, 0)
            check(limit[0] != 0) { "GPU Softmax link: ${GLES31.glGetProgramInfoLog(program).take(200)}" }
            classesLocation = GLES31.glGetUniformLocation(program, "classes")
            check(classesLocation >= 0) { "GPU Softmax uniform unavailable" }
            GLES31.glGenBuffers(2, buffers, 0)
            checkGl("compile")
        } finally {
            GLES31.glDeleteShader(shader)
        }
    }

    private fun checkGl(stage: String) {
        val error = GLES31.glGetError()
        check(error == GLES31.GL_NO_ERROR) { "GPU Softmax $stage failed: GL 0x${error.toString(16)}" }
    }

    /** 释放 GPU 对象和上下文；调用者须停止推理。 / Releases GPU resources after inference stops. */
    override fun close() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        try {
            if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE &&
                EGL14.eglMakeCurrent(display, surface, surface, context)) {
                if (program != 0) GLES31.glDeleteProgram(program)
                GLES31.glDeleteBuffers(2, buffers, 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        } finally {
            if (previousDisplay != EGL14.EGL_NO_DISPLAY && previousContext != context) {
                EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext)
            }
        }
        // 默认 display 可能与同进程其他 EGL 用户共享，不在这里全局 terminate。
        surface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
        program = 0
        buffers.fill(0)
    }

    private companion object {
        val SHADER = """
            #version 310 es
            precision highp float;
            precision highp int;
            layout(local_size_x = 128) in;
            layout(std430, binding = 0) readonly buffer Source { float logits[]; };
            layout(std430, binding = 1) writeonly buffer Destination { float probabilities[]; };
            uniform int classes;
            shared float reduction[128];
            void main() {
                uint lane = gl_LocalInvocationID.x;
                uint base = gl_WorkGroupID.x * uint(classes);
                float maximum = -3.402823466e38;
                for (uint column = lane; column < uint(classes); column += 128u)
                    maximum = max(maximum, logits[base + column]);
                reduction[lane] = maximum;
                barrier();
                for (uint stride = 64u; stride > 0u; stride >>= 1u) {
                    if (lane < stride) reduction[lane] = max(reduction[lane], reduction[lane + stride]);
                    barrier();
                }
                maximum = reduction[0];
                barrier();
                float total = 0.0;
                for (uint column = lane; column < uint(classes); column += 128u)
                    total += exp(logits[base + column] - maximum);
                reduction[lane] = total;
                barrier();
                for (uint stride = 64u; stride > 0u; stride >>= 1u) {
                    if (lane < stride) reduction[lane] += reduction[lane + stride];
                    barrier();
                }
                total = reduction[0];
                for (uint column = lane; column < uint(classes); column += 128u)
                    probabilities[base + column] = exp(logits[base + column] - maximum) / total;
            }
        """.trimIndent()
    }
}
