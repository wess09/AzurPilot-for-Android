package com.azurpilot.ghio.bridge

import android.graphics.Bitmap
import android.os.SharedMemory
import android.view.Surface
import java.nio.ByteBuffer

import com.azurpilot.ghio.third.Ln

import dalvik.annotation.optimization.FastNative

/**
 * native 桥库（libbridge.so）的加载入口与 JNI 静态方法声明
 *
 * `init` 里 [System.loadLibrary] 加载 C++ 桥库，成功与否记入 [LOADED]（不抛出，
 * 让调用方优雅降级）。类名被 bridge.cpp 的 FindClass / RegisterNatives 按字符串
 * 查找，且在 R8 关键类清单内，不可重命名；方法须保持 [JvmStatic] 以生成静态
 * native 符号。帧缓冲相关方法由 native 捕获线程写入，Kotlin 侧任意线程可调；
 * 带 [FastNative] 的为高频热路径。
 *
 * Load entry and JNI static-method surface of the native bridge library
 * (libbridge.so).
 *
 * `init` loads the C++ bridge via [System.loadLibrary] and records the
 * outcome in [LOADED] (it never throws, letting callers degrade gracefully).
 * The class name is looked up by string in bridge.cpp's FindClass /
 * RegisterNatives and is listed in the R8 keep rules, so it must not be
 * renamed; methods must stay [JvmStatic] to produce static native symbols.
 * The frame-buffer methods are written by the native capture thread and
 * callable from any Kotlin thread; [FastNative]-annotated ones are hot paths.
 */
object NativeBridgeLib {

    /**
     * 将当前 BGR 帧复制到持久直接缓冲，返回同一帧的宽、高、字节数和序号。
     *
     * 调用线程独占目标映射；目标不足或尚无帧时返回 null，不持有任何帧槽。
     *
     * Copies the current BGR frame into a persistent direct buffer, returning its width, height,
     * byte length, and sequence. The calling thread owns the destination exclusively; null means
     * no frame or insufficient capacity, and no frame slot remains held.
     */
    @JvmStatic
    external fun copyFrameBuffer(destination: ByteBuffer): LongArray?

    /**
     * 复制共享内存的 FD；调用方负责关闭，失败返回负数。可在任意线程调用。
     *
     * Duplicates the shared-memory FD; the caller closes it. Returns a negative value on failure
     * and may be called from any thread.
     */
    @JvmStatic
    external fun dupSharedMemoryFd(memory: SharedMemory): Int

    /**
     * libbridge.so 是否加载成功
     *
     * false 时调用任何 external 方法都会抛 UnsatisfiedLinkError，调用方须先检查。
     *
     * Whether libbridge.so loaded successfully.
     *
     * When false, calling any external method throws UnsatisfiedLinkError, so
     * callers must check this first.
     */
    @JvmStatic
    var LOADED: Boolean = false
        private set

    init {
        LOADED = try {
            System.loadLibrary("bridge")
            true
        } catch (e: Throwable) {
            Ln.e("NativeBridgeLib static initializer: ", e)
            false
        }
    }

    /**
     * native 连通性自检（测试用）
     *
     * Native connectivity probe (for tests).
     */
    @JvmStatic
    @FastNative
    external fun ping(): String?

    /**
     * 开关 native 侧的多触点 contact 支持
     *
     * 框架版本闸（见 NativeVersion）；false 时触摸恒按单指 contact 0 注入。
     *
     * Toggles multi-touch contact support on the native side.
     *
     * A framework-version gate (see NativeVersion); when false, touches are
     * always injected as a single pointer with contact 0.
     */
    @JvmStatic
    external fun setContactSupport(supported: Boolean)

    /**
     * 初始化 native 帧捕获器
     *
     * @return native 侧接收帧画面的 [Surface] / the [Surface] the native side
     *   receives frames into
     */
    @JvmStatic
    external fun setupNativeCapturer(width: Int, height: Int): Surface?

    /**
     * 释放 native 捕获器及其持有的帧缓冲资源
     *
     * Releases the native capturer and its frame-buffer resources.
     */
    @JvmStatic
    external fun releaseNativeCapturer()

    /**
     * 挂载/更换预览 [Surface]（传 null 解除绑定）
     *
     * Attaches or replaces the preview [Surface] (null detaches it).
     */
    @JvmStatic
    @FastNative
    external fun setPreviewSurface(surface: Any?)

    /**
     * 取当前帧缓冲的 [Bitmap] 快照（测试用）；无可用帧时返回 null
     *
     * Returns the current frame buffer as a [Bitmap] snapshot (for tests);
     * null when no frame is available.
     */
    @JvmStatic
    external fun getFrameBufferBitmap(): Bitmap?

    /**
     * BridgeServer screencap 端点专用：BGR 裸字节直出，跳过 Bitmap 转换
     *
     * 无可用帧返回 null。
     *
     * Dedicated to the BridgeServer screencap endpoint: raw BGR bytes straight
     * out, skipping the Bitmap conversion.
     *
     * Returns null when no frame is available.
     */
    @JvmStatic
    external fun getFrameBufferBytes(): ByteArray?

    /**
     * 查询 native 侧累计渲染帧数
     *
     * [DriverClass] 用它等待首帧。
     *
     * Returns the native side's cumulative rendered frame count.
     *
     * [DriverClass] uses it to wait for the first frame.
     */
    @JvmStatic
    @FastNative
    external fun getFrameCount(): Long

    /**
     * 获取捕获链路的诊断信息文本
     *
     * Returns capture-pipeline diagnostics as text.
     */
    @JvmStatic
    external fun getCaptureDiagnostics(): String?
}
