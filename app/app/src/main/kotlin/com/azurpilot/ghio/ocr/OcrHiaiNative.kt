package com.azurpilot.ghio.ocr

import androidx.annotation.Keep

/**
 * 在 ARM64 海思设备上加载严格的 HiAI 会话桥；调用方串行访问并负责句柄生命周期。
 *
 * Loads the strict HiAI session bridge on ARM64 Kirin devices. Callers serialize access and
 * own each handle's lifetime.
 */
internal object OcrHiaiNative {
    init { System.loadLibrary("ocrhiai") }

    /** 创建固定输入的 NPU 会话。 / Creates an NPU session with fixed input dimensions. */
    @JvmStatic external fun create(
        modelPath: String, libraryDirectory: String, classes: Int, trace: StageTrace,
    ): Long

    /** 返回单图 NTC FP32 输出。 / Returns a single image's NTC FP32 output. */
    @JvmStatic external fun run(handle: Long, values: FloatArray, trace: StageTrace): FloatArray

    /** 释放句柄及模型。 / Releases a handle and its model. */
    @JvmStatic external fun close(handle: Long)

    /**
     * 接收当前 JNI 调用的同步诊断，原生退出前已进入的阶段可以从文件恢复。
     *
     * Receives synchronous diagnostics for a JNI call, preserving entered stages after a crash.
     */
    class StageTrace(private val trace: OcrTrace, private val model: String) {
        /** 按原名字面量由 JNI 调用。 / Called by JNI by its literal method name. */
        @Keep
        fun record(stage: String, finished: Boolean, returnCode: Long) {
            trace.record(model, stage, phase = if (finished) "return" else "begin",
                returnCode = returnCode.takeIf { finished })
        }
    }
}
