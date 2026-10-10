// 通过钉版 LiteRT 公开 C API 检查 JIT 分区，避免把静默 CPU 回退报告为 NPU。
// JNI 按 2.1.0rc1 的 ModelWrapper 布局取出模型；调用方必须持锁，不得与 close 并发。
//
// Checks JIT partitions through the pinned LiteRT public C API so silent CPU fallback is not
// reported as NPU. JNI unwraps the pinned 2.1.0rc1 ModelWrapper; callers must hold the model
// lock and never close it concurrently.

#include <jni.h>
#include <dlfcn.h>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <string>
#include "third_party/litert/litert/c/litert_layout.h"

namespace {
// C API 参数使用原始模型指针，不能直接传入 Kotlin 的 ModelWrapper 指针。
using GetSubgraph = int32_t (*)(void*, size_t, void**);
using GetOpCount = int32_t (*)(void*, size_t*);
using GetOp = int32_t (*)(void*, size_t, void**);
using GetOpCode = int32_t (*)(void*, int32_t*);
using IsFullyAccelerated = int32_t (*)(void*, bool*);
constexpr int32_t kCustomOp = 32;

// 覆盖编译与 dispatch 的基本入口，拒绝缺少接口的旧系统 adapter。
constexpr const char* kRequiredNeuronSymbols[] = {
    "NeuronModel_setName", "NeuronModel_create", "NeuronModel_free",
    "NeuronModel_addOperand", "NeuronModel_addOperation", "NeuronModel_setOperandValue",
    "NeuronModel_identifyInputsAndOutputs", "NeuronModel_finish",
    "NeuronModel_restoreFromCompiledNetwork", "NeuronCompilation_create",
    "NeuronCompilation_createWithOptions", "NeuronCompilation_finish", "NeuronCompilation_free",
    "NeuronCompilation_storeCompiledNetwork", "NeuronCompilation_getCompiledNetworkSize",
    "NeuronExecution_create", "NeuronExecution_setInputFromMemory",
    "NeuronExecution_setOutputFromMemory", "NeuronExecution_compute", "NeuronExecution_free",
    "NeuronMemory_createFromFd", "NeuronMemory_free", "Neuron_getVersion",
};

jstring AdapterFailure(JNIEnv* env, const std::string& message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) {
        env->ThrowNew(exception, message.c_str());
        env->DeleteLocalRef(exception);
    }
    return nullptr;
}

bool SupportsVersion(JNIEnv* env, jstring runtime_version) {
    if (runtime_version == nullptr) return false;
    const char* version = env->GetStringUTFChars(runtime_version, nullptr);
    if (version == nullptr) return false;
    const bool supported = std::strcmp(version, "2.1.0rc1") == 0;
    env->ReleaseStringUTFChars(runtime_version, version);
    return supported;
}

jlong ReadHandle(JNIEnv* env, jobject object) {
    if (object == nullptr) return 0;
    jclass object_class = env->GetObjectClass(object);
    jfieldID handle_field = env->GetFieldID(object_class, "handle", "J");
    env->DeleteLocalRef(object_class);
    if (handle_field == nullptr) {
        env->ExceptionClear();
        return 0;
    }
    return env->GetLongField(object, handle_field);
}
}  // namespace

// 编译会话的真实输出布局可能已经改变，不能读取源模型里的静态 40 时间步。
// Read the compiled output layout rather than the source model's static time axis.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_outputShape(
        JNIEnv* env, jobject, jobject compiled_model, jstring runtime_version) {
    if (!SupportsVersion(env, runtime_version)) return nullptr;
    auto* handle = reinterpret_cast<void*>(ReadHandle(env, compiled_model));
    if (handle == nullptr) return nullptr;
    void* library = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return nullptr;
    using GetLayouts = int32_t (*)(void*, size_t, size_t, LiteRtLayout*, bool);
    auto api = reinterpret_cast<GetLayouts>(dlsym(library, "LiteRtGetCompiledModelOutputTensorLayouts"));
    LiteRtLayout layout{};
    const bool valid = api != nullptr && api(handle, 0, 1, &layout, true) == 0 &&
            layout.rank > 0 && layout.rank <= 4;
    dlclose(library);
    if (!valid) return nullptr;
    jlong values[4]{};
    for (size_t i = 0; i < layout.rank; ++i) {
        if (layout.dimensions[i] <= 0) return nullptr;
        values[i] = layout.dimensions[i];
    }
    jlongArray result = env->NewLongArray(layout.rank);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, layout.rank, values);
    return result;
}

// SDK 8 初始化直接调用配置入口，MDLA 创建还需 APUSys；提前检查以避开已知 abort。
//
// Checks the configuration entry and, when requested, the APUSys library before SDK loading
// to avoid known initialization crashes. Does not call private driver functions.
extern "C" JNIEXPORT jstring JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_mediatekDriverError(
        JNIEnv* env, jobject, jboolean require_apusys) {
    std::string errors;
    for (const char* name : {"libapuwareutils_v2.mtk.so", "libapuwareutils.mtk.so"}) {
        void* library = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (library != nullptr) {
            void* query = dlsym(library, "queryHwConfigInternal");
            if (query != nullptr) {
                if (require_apusys == JNI_TRUE &&
                    dlopen("libapuwareapusys_v2.mtk.so", RTLD_NOW | RTLD_LOCAL) == nullptr) {
                    const char* error = dlerror();
                    return env->NewStringUTF((std::string("MediaTek APUSys driver unavailable: ") +
                            (error != nullptr ? error : "libapuwareapusys_v2.mtk.so")).c_str());
                }
                // 保持工具库存活，adapter 稍后还要按名称查找同一入口；不调用私有查询函数。
                return nullptr;
            }
            dlclose(library);
            // adapter 一旦打开 v2 工具库就不会转用旧版；不能用旧库符号掩盖该缺失。
            return env->NewStringUTF((std::string("MediaTek driver ") + name +
                    " lacks queryHwConfigInternal").c_str());
        }
        if (!errors.empty()) errors += "; ";
        errors += name;
        errors += ": ";
        const char* error = dlerror();
        errors += error != nullptr ? error : "queryHwConfigInternal unavailable";
    }
    return env->NewStringUTF(("MediaTek APU driver unavailable: " + errors).c_str());
}

// 复现 2.1.0rc1 的最后成功加载者规则；缺少基本 API 时抛异常，不执行推理入口。
// Mirrors the pinned 2.1.0rc1 last-successful-load rule; throws on missing basic API entries
// without calling inference functions. Returns the selected library name for diagnostics.
extern "C" JNIEXPORT jstring JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_mediatekAdapterLibrary(JNIEnv* env, jobject) {
    void* selected = nullptr;
    std::string selected_name;
    auto try_library = [&](const char* name) {
        void* library = dlopen(name, RTLD_NOW | RTLD_LOCAL);
        if (library != nullptr) {
            if (selected != nullptr) dlclose(selected);
            selected = library;
            selected_name = name;
        }
    };
    // 上游 LoadSymbols 不在成功后 break，必须检查最终胜出的库，不能只检查内置库。
    // https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/
    // litert/vendors/mediatek/neuron_adapter_api.cc
    try_library("libneuronusdk_adapter.mtk.so");
    void* utility = dlopen("libneuron_sys_util.mtk.so", RTLD_NOW | RTLD_LOCAL);
    if (utility != nullptr) {
        auto magic_number = reinterpret_cast<int (*)(int32_t*)>(
                dlsym(utility, "NeuronService_getNeuroPilotMagicNumber"));
        int32_t magic = 0;
        if (magic_number != nullptr && magic_number(&magic) == 0 && magic >= 300) {
            try_library("libneuronusdk_adapter.9.mtk.so");
        }
        dlclose(utility);
    }
    try_library("libneuron_adapter_mgvi.so");
    try_library("libneuron_adapter.so");
    if (selected == nullptr) return AdapterFailure(env, "MediaTek Neuron adapter unavailable");
    for (const char* symbol : kRequiredNeuronSymbols) {
        if (dlsym(selected, symbol) == nullptr) {
            dlclose(selected);
            return AdapterFailure(env, "MediaTek adapter " + selected_name + " lacks " + symbol);
        }
    }
    // 保持已确认的库映射，编译与 dispatch 稍后会重新按名称打开；随工作进程释放。
    return env->NewStringUTF(selected_name.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_countCustomOps(
        JNIEnv* env, jobject, jobject model, jstring runtime_version) {
    if (!SupportsVersion(env, runtime_version)) return -1;
    auto* wrapper = reinterpret_cast<void*>(ReadHandle(env, model));
    if (wrapper == nullptr) return -1;
    // 上游此版本 ModelWrapper 的首成员是 LiteRtModel；memcpy 避免伪造结构的别名访问。
    // 来源：https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/
    // litert/kotlin/src/main/jni/litert_model_wrapper.h
    void* handle = nullptr;
    std::memcpy(&handle, wrapper, sizeof(handle));
    if (handle == nullptr) return -1;
    void* library = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return -1;
    auto subgraph_api = reinterpret_cast<GetSubgraph>(dlsym(library, "LiteRtGetModelSubgraph"));
    auto count_api = reinterpret_cast<GetOpCount>(dlsym(library, "LiteRtGetNumSubgraphOps"));
    auto op_api = reinterpret_cast<GetOp>(dlsym(library, "LiteRtGetSubgraphOp"));
    auto code_api = reinterpret_cast<GetOpCode>(dlsym(library, "LiteRtGetOpCode"));
    void* subgraph = nullptr;
    size_t count = 0;
    int custom = 0;
    if (!subgraph_api || !count_api || !op_api || !code_api ||
        subgraph_api(handle, 0, &subgraph) != 0 || count_api(subgraph, &count) != 0 || count > 100000) {
        custom = -1;
    } else {
        for (size_t index = 0; index < count; ++index) {
            void* op = nullptr;
            int32_t code = 0;
            if (op_api(subgraph, index, &op) != 0 || code_api(op, &code) != 0) {
                custom = -1;
                break;
            }
            if (code == kCustomOp) ++custom;
        }
    }
    dlclose(library);
    return custom;
}

// CompiledModel.handle 已是原始 C 会话，不能按 ModelWrapper 解包。
// CPU delegate 也可能令全部委派为真，调用方不得据此单独宣称 NPU 执行。
//
// CompiledModel.handle is the raw C session, unlike ModelWrapper. Returns 1 for full
// delegation, 0 for remaining undelegated ops, or -1 for unavailable diagnostics.
// CPU delegates can also return 1, so this result alone is never NPU evidence.
extern "C" JNIEXPORT jint JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_compiledModelAcceleration(
        JNIEnv* env, jobject, jobject compiled_model, jstring runtime_version) {
    if (!SupportsVersion(env, runtime_version)) return -1;
    auto* handle = reinterpret_cast<void*>(ReadHandle(env, compiled_model));
    if (handle == nullptr) return -1;
    void* library = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return -1;
    auto api = reinterpret_cast<IsFullyAccelerated>(
            dlsym(library, "LiteRtCompiledModelIsFullyAccelerated"));
    bool fully = false;
    const int result = api != nullptr && api(handle, &fully) == 0 ? (fully ? 1 : 0) : -1;
    dlclose(library);
    return result;
}

// 大字典逐行减最大值，避免指数溢出；双精度累加分母，减少大量类别的求和误差。
// 整块数组通过 JNI 访问，不逐元素跨语言调用；不使用 fast-math。
//
// Subtracts row maxima to prevent exponent overflow, with double denominator accumulation
// to reduce error across large dictionaries. Uses bulk JNI array access without fast-math.
// Returns false for invalid shapes or nonfinite logits.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_azurpilot_ghio_ocr_OcrNative_softmaxInPlace(
        JNIEnv* env, jobject, jfloatArray values, jint classes) {
    if (values == nullptr || classes <= 0) return JNI_FALSE;
    const jsize count = env->GetArrayLength(values);
    if (count == 0 || count % classes != 0) return JNI_FALSE;
    jfloat* data = env->GetFloatArrayElements(values, nullptr);
    if (data == nullptr) return JNI_FALSE;
    bool valid = true;
    for (jsize offset = 0; offset < count && valid; offset += classes) {
        float maximum = data[offset];
        for (jint column = 0; column < classes; ++column) {
            const float value = data[offset + column];
            if (!std::isfinite(value)) { valid = false; break; }
            maximum = std::max(maximum, value);
        }
        if (!valid) break;
        double total = 0;
        for (jint column = 0; column < classes; ++column) {
            const float value = std::exp(data[offset + column] - maximum);
            data[offset + column] = value;
            total += value;
        }
        const float inverse = static_cast<float>(1.0 / total);
        for (jint column = 0; column < classes; ++column) data[offset + column] *= inverse;
    }
    env->ReleaseFloatArrayElements(values, data, 0);
    return valid ? JNI_TRUE : JNI_FALSE;
}
