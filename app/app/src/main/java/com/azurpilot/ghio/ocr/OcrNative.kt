package com.azurpilot.ghio.ocr

import com.google.ai.edge.litert.Model
import com.google.ai.edge.litert.CompiledModel

/**
 * 读取钉版 LiteRT 的公开 C 模型 API，确认 JIT 确实生成了厂商 dispatch 分区。
 *
 * 仅接收仍存活的模型对象，调用期间由 OcrEngine 的锁保护。JniHandle.handle 指向
 * ModelWrapper，不是 LiteRtModel。JNI 只接受 2.1.0rc1 并取其首成员；升级时须核对布局。
 *
 * Reads pinned LiteRT's public C model API to check that JIT generated vendor dispatch
 * partitions. Accepts live models only under OcrEngine's lock. JniHandle.handle points to
 * ModelWrapper, not LiteRtModel. JNI accepts only 2.1.0rc1 and reads its first member;
 * recheck this layout and the R8 keep rule when upgrading.
 */
internal object OcrNative {
    init { System.loadLibrary("ocrdiagnostics") }

    /**
     * 主图的 custom 操作数量，API 不可用或版本不匹配时为 -1。
     *
     * Main-graph custom op count, or -1 if unavailable or the version mismatches.
     */
    external fun countCustomOps(model: Model, runtimeVersion: String): Int

    /**
     * 从已编译会话检查全图委派；1 为全部委派，0 为剩余未委派算子，-1 为检查不可用。
     *
     * CPU delegate 也可能返回 1；此结果不能单独作为 NPU 执行证据。
     *
     * Checks delegation on the compiled session: 1 for full delegation, 0 for remaining
     * undelegated ops, and -1 if unavailable. CPU delegates can also return 1;
     * this result alone is never evidence of NPU execution.
     */
    external fun compiledModelAcceleration(model: CompiledModel, runtimeVersion: String): Int

    /**
     * 在加载 MTK adapter 前检查其必需的系统入口，缺失时返回原因。
     * MDLA 策略还检查 APUSys 执行库能否加载，不调用私有驱动函数。
     *
     * Checks the required system entry before loading MTK adapters and returns missing-driver details.
     * The MDLA policy also checks APUSys library loading without calling private driver functions.
     */
    external fun mediatekDriverError(requireApusys: Boolean): String?

    /**
     * 检查钉版插件最终选中的 MTK adapter，缺少基本入口时抛异常。
     *
     * Checks the MTK adapter selected by the pinned plugin; throws on missing basic entries.
     */
    external fun mediatekAdapterLibrary(): String

    /**
     * 原地计算稳定的行 Softmax；拒绝非有限输入或错误尺寸，调用方必须检查返回值。
     *
     * Computes stable row Softmax in place. Rejects nonfinite inputs or invalid dimensions;
     * callers must check the return value before using output.
     */
    external fun softmaxInPlace(values: FloatArray, classes: Int): Boolean

    /** 返回已编译的实际输出尺寸，失败为 null。 / Returns the compiled output shape, or null. */
    external fun outputShape(model: CompiledModel, runtimeVersion: String): LongArray?
}
