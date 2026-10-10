"""在 Linux JVM 验证生产 JNI 桥的模型句柄和 MTK 驱动入口探测。

需要 JDK 和 C++ 编译器；C API fixture 校验收到的模型身份并模拟分区数量。
不需要 Android 或 NPU，无法替代真机驱动验证。

Checks production JNI model handles and MTK driver-entry probing on a Linux JVM.
Requires a JDK and C++ compiler. A C API fixture checks model identity and simulates
partition counts. Requires neither Android nor NPU and does not validate vendor drivers.
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "app/app/src/main/native/ocr_diagnostics.cpp"

C_API = r"""
#include <jni.h>
#include <cstddef>
#include <cstdint>
#include "litert/c/litert_layout.h"
struct Model { int custom; } model;
// 按钉版 ModelWrapper 首成员布局构造不同地址，避免错误指针也通过检查。
struct Wrapper { void* model; unsigned char buffer[32]; } wrapper;
struct Compiled { int result; } compiled;
extern "C" JNIEXPORT jlong JNICALL Java_test_Fixture_wrap(JNIEnv*, jclass, jint custom) {
    model.custom = custom;
    wrapper.model = custom < 0 ? nullptr : &model;
    return reinterpret_cast<jlong>(&wrapper);
}
extern "C" int32_t LiteRtGetModelSubgraph(void* handle, size_t index, void** subgraph) {
    if (handle != &model || index != 0) return 1;
    *subgraph = &model;
    return 0;
}
extern "C" int32_t LiteRtGetNumSubgraphOps(void* subgraph, size_t* count) {
    if (subgraph != &model) return 1;
    *count = model.custom + 3;
    return 0;
}
extern "C" int32_t LiteRtGetSubgraphOp(void* subgraph, size_t index, void** op) {
    if (subgraph != &model) return 1;
    *op = reinterpret_cast<void*>(index + 1);
    return 0;
}
extern "C" int32_t LiteRtGetOpCode(void* op, int32_t* code) {
    *code = reinterpret_cast<size_t>(op) <= static_cast<size_t>(model.custom) ? 32 : 18;
    return 0;
}
extern "C" JNIEXPORT jlong JNICALL Java_test_Fixture_compile(JNIEnv*, jclass, jint result) {
    compiled.result = result;
    return reinterpret_cast<jlong>(&compiled);
}
extern "C" int32_t LiteRtCompiledModelIsFullyAccelerated(void* handle, bool* fully) {
    // 编译会话必须直接传入，按 ModelWrapper 解包会得到另一地址并被拒绝。
    if (handle != &compiled || compiled.result < 0) return 1;
    *fully = compiled.result == 1;
    return 0;
}
extern "C" int32_t LiteRtGetCompiledModelOutputTensorLayouts(
        void* handle, size_t signature, size_t count, LiteRtLayout* layouts, bool update) {
    if (handle != &compiled || signature != 0 || count != 1 || !update || compiled.result < 0) return 1;
    layouts[0] = {};
    layouts[0].rank = 3;
    layouts[0].dimensions[0] = 1;
    layouts[0].dimensions[1] = 80;
    layouts[0].dimensions[2] = 18385;
    return 0;
}
"""

MODEL = """
package com.google.ai.edge.litert;
class JniHandle {
    private final long handle;
    JniHandle(long value) { handle = value; }
}
public class Model extends JniHandle {
    public Model(long value) { super(value); }
}
"""

COMPILED_MODEL = """
package com.google.ai.edge.litert;
public class CompiledModel extends JniHandle {
    public CompiledModel(long value) { super(value); }
}
"""

BRIDGE = """
package com.azurpilot.ghio.ocr;
import com.google.ai.edge.litert.Model;
import com.google.ai.edge.litert.CompiledModel;
public class OcrNative {
    static { System.loadLibrary("ocrdiagnostics"); }
    public native int countCustomOps(Model model, String runtimeVersion);
    public native int compiledModelAcceleration(CompiledModel model, String runtimeVersion);
    public native String mediatekDriverError(boolean requireApusys);
    public native String mediatekAdapterLibrary();
    public native boolean softmaxInPlace(float[] values, int classes);
    public native long[] outputShape(CompiledModel model, String runtimeVersion);
}
"""

FIXTURE = """
package test;
import com.azurpilot.ghio.ocr.OcrNative;
import com.google.ai.edge.litert.Model;
import com.google.ai.edge.litert.CompiledModel;
public class Fixture {
    static { System.loadLibrary("LiteRt"); }
    public static native long wrap(int custom);
    public static native long compile(int result);
    static void expect(int expected, int actual) {
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) throws Exception {
        OcrNative bridge = new OcrNative();
        if (args.length > 0 && args[0].equals("compiled_api_absent")) {
            expect(-1, bridge.compiledModelAcceleration(new CompiledModel(compile(1)), "2.1.0rc1"));
            System.out.println("OCR JNI: compiled_api_absent passed");
            return;
        }
        if (args.length > 0 && args[0].startsWith("adapter_")) {
            String actual;
            try {
                actual = bridge.mediatekAdapterLibrary();
            } catch (IllegalStateException error) {
                actual = "error:" + error.getMessage();
            }
            if (!actual.equals(args[1]))
                throw new AssertionError("Expected " + args[1] + ", got " + actual);
            System.out.println("OCR JNI: " + args[0] + " passed");
            return;
        }
        if (args.length > 0 && args[0].startsWith("apusys_")) {
            String error = bridge.mediatekDriverError(true);
            if (args[0].equals("apusys_present") ? error != null :
                    error == null || !error.contains("APUSys driver unavailable"))
                throw new AssertionError("Unexpected APUSys probe: " + error);
            System.out.println("OCR JNI: " + args[0] + " passed");
            return;
        }
        if (args.length > 0) {
            boolean available = bridge.mediatekDriverError(false) == null;
            if (available != args[0].equals("driver_present"))
                throw new AssertionError("Unexpected MTK driver probe: " + available);
            System.out.println("OCR JNI: " + args[0] + " passed");
            return;
        }
        expect(0, bridge.countCustomOps(new Model(wrap(0)), "2.1.0rc1"));
        expect(2, bridge.countCustomOps(new Model(wrap(2)), "2.1.0rc1"));
        expect(-1, bridge.countCustomOps(new Model(wrap(-1)), "2.1.0rc1"));
        expect(-1, bridge.countCustomOps(new Model(0), "2.1.0rc1"));
        // 未验证的 ABI 版本必须在读取未知指针前拒绝。
        expect(-1, bridge.countCustomOps(new Model(1), "2.2.0"));
        expect(-1, bridge.countCustomOps(null, "2.1.0rc1"));
        expect(-1, bridge.countCustomOps(new Model(1), null));
        expect(1, bridge.compiledModelAcceleration(new CompiledModel(compile(1)), "2.1.0rc1"));
        expect(0, bridge.compiledModelAcceleration(new CompiledModel(compile(0)), "2.1.0rc1"));
        expect(-1, bridge.compiledModelAcceleration(new CompiledModel(compile(-1)), "2.1.0rc1"));
        expect(-1, bridge.compiledModelAcceleration(new CompiledModel(0), "2.1.0rc1"));
        expect(-1, bridge.compiledModelAcceleration(new CompiledModel(1), "2.2.0"));
        expect(-1, bridge.compiledModelAcceleration(null, "2.1.0rc1"));
        expect(-1, bridge.compiledModelAcceleration(new CompiledModel(1), null));
        if (!java.util.Arrays.equals(new long[] {1, 80, 18385},
                bridge.outputShape(new CompiledModel(compile(1)), "2.1.0rc1")))
            throw new AssertionError("Actual compiled output layout not used");
        if (bridge.outputShape(new CompiledModel(compile(-1)), "2.1.0rc1") != null ||
                bridge.outputShape(new CompiledModel(1), "wrong") != null)
            throw new AssertionError("Invalid output layout accepted");
        System.out.println("OCR JNI: output layouts passed");
        if (bridge.mediatekDriverError(false) == null)
            throw new AssertionError("Missing MTK driver must be rejected before SDK loading");
        float[] logits = {1000, 999, -1000, -1000, -999, 1000};
        if (!bridge.softmaxInPlace(logits, 3) || Math.abs(logits[0] - 0.7310586) > 1e-6 ||
                Math.abs(logits[1] - 0.2689414) > 1e-6 || logits[2] != 0 || logits[5] != 1)
            throw new AssertionError("Stable native Softmax differs from reference");
        float[] large = new float[40 * 18710];
        if (!bridge.softmaxInPlace(large, 18710)) throw new AssertionError("Large dictionary rejected");
        double sum = 0;
        for (int i = 0; i < 18710; i++) sum += large[i];
        if (Math.abs(sum - 1) > 1e-6) throw new AssertionError("Large Softmax normalization differs");
        if (bridge.softmaxInPlace(new float[] {Float.NaN, 0}, 2) ||
                bridge.softmaxInPlace(new float[] {Float.POSITIVE_INFINITY, 0}, 2))
            throw new AssertionError("Nonfinite logits accepted");
        if (bridge.softmaxInPlace(null, 1) || bridge.softmaxInPlace(new float[0], 1) ||
                bridge.softmaxInPlace(new float[3], 2) || bridge.softmaxInPlace(new float[3], 0))
            throw new AssertionError("Invalid Softmax dimensions accepted");
        System.out.println("OCR JNI: 4 native Softmax checks passed");
        System.out.println("OCR JNI: 7 regression checks passed");
        System.out.println("OCR JNI: 7 compiled-session checks passed");
        System.out.println("OCR JNI: driver_absent passed");
    }
}
"""


def main():
    """编译真实 JNI 验证句柄及驱动探测。 / Compiles real JNI to check handles and driver probes."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=NATIVE)
    args = parser.parse_args()
    javac = Path(shutil.which("javac") or "").resolve()
    compiler = shutil.which("c++")
    if not javac.is_file() or not compiler or os.name != "posix":
        parser.error("Run on Linux with JDK and a C++ compiler installed")
    jdk = javac.parent.parent
    with tempfile.TemporaryDirectory(prefix="ocr-jni-") as temp:
        directory = Path(temp)
        api = directory / "api.cpp"
        api.write_text(C_API)
        flags = [compiler, "-std=c++17", "-shared", "-fPIC", "-Wall", "-Wextra", "-O2",
                 f"-I{jdk / 'include'}", f"-I{jdk / 'include/linux'}",
                 f"-I{NATIVE.parent / 'third_party/litert'}"]
        subprocess.run(flags + [str(api), "-o", str(directory / "libLiteRt.so")], check=True)
        subprocess.run(flags + [str(args.source), "-ldl", "-o",
                               str(directory / "libocrdiagnostics.so")], check=True)
        sources = []
        for path, text in [("com/google/ai/edge/litert/Model.java", MODEL),
                           ("com/google/ai/edge/litert/CompiledModel.java", COMPILED_MODEL),
                           ("com/azurpilot/ghio/ocr/OcrNative.java", BRIDGE),
                           ("test/Fixture.java", FIXTURE)]:
            source = directory / path
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text(text)
            sources.append(str(source))
        subprocess.run([str(javac), "-d", str(directory), *sources], check=True)
        env = os.environ | {"LD_LIBRARY_PATH": str(directory)}
        java = [str(jdk / "bin/java"), f"-Djava.library.path={directory}",
                "-cp", str(directory), "test.Fixture"]
        subprocess.run(java, env=env, check=True)
        # 在新 JVM 去掉会话检查导出，验证 API 不可用时不会宣称 NPU 成功。
        api.write_text(C_API.replace("LiteRtCompiledModelIsFullyAccelerated", "MissingAccelerationApi"))
        subprocess.run(flags + [str(api), "-o", str(directory / "libLiteRt.so")], check=True)
        subprocess.run(java + ["compiled_api_absent"], env=env, check=True)
        # 探测只能检查入口，不能调用未公开的硬件配置 ABI。
        api.write_text('#include <cstdlib>\nextern "C" void* queryHwConfigInternal(int*) { std::abort(); }\n')
        utility = directory / "libapuwareutils_v2.mtk.so"
        subprocess.run(flags + [str(api), "-o", str(utility)], check=True)
        subprocess.run(java + ["driver_present"], env=env, check=True)
        subprocess.run(java + ["apusys_absent"], env=env, check=True)
        # 执行库缺失要拒绝；存在时只检查加载，fixture 的私有入口一旦被调用就 abort。
        apusys = directory / "libapuwareapusys_v2.mtk.so"
        subprocess.run(flags + [str(api), "-o", str(apusys)], check=True)
        subprocess.run(java + ["apusys_present"], env=env, check=True)
        # 驱动文件存在但缺少其传递依赖，同样不能放行进入 SDK 编译。
        dependency = directory / "libmissing_apusys_dependency.so"
        api.write_text('extern "C" int apusys_dependency() { return 0; }\n')
        subprocess.run(flags + [str(api), "-o", str(dependency)], check=True)
        api.write_text('extern "C" int apusys_dependency();\n'
                       'extern "C" int apusys_entry() { return apusys_dependency(); }\n')
        subprocess.run(flags + [str(api), f"-L{directory}", "-lmissing_apusys_dependency",
                               "-o", str(apusys)], check=True)
        dependency.unlink()
        subprocess.run(java + ["apusys_broken_dependency"], env=env, check=True)
        apusys.unlink()
        # SDK 不会绕过已打开但缺少入口的 v2 库，旧库有入口也必须拒绝。
        shutil.copyfile(utility, directory / "libapuwareutils.mtk.so")
        api.write_text('extern "C" int wrong_driver_api() { return 0; }\n')
        subprocess.run(flags + [str(api), "-o", str(utility)], check=True)
        subprocess.run(java + ["driver_wrong_api"], env=env, check=True)
        subprocess.run(java + ["adapter_absent", "error:MediaTek Neuron adapter unavailable"],
                       env=env, check=True)
        # 用完整 SDK 接口模拟正常内置库；旧 MGVI 故意缺少命名接口，复现真机崩溃条件。
        symbols = ["NeuronModel_setName", "NeuronModel_create", "NeuronModel_free",
                   "NeuronModel_addOperand", "NeuronModel_addOperation", "NeuronModel_setOperandValue",
                   "NeuronModel_identifyInputsAndOutputs", "NeuronModel_finish",
                   "NeuronModel_restoreFromCompiledNetwork", "NeuronCompilation_create",
                   "NeuronCompilation_createWithOptions", "NeuronCompilation_finish", "NeuronCompilation_free",
                   "NeuronCompilation_storeCompiledNetwork", "NeuronCompilation_getCompiledNetworkSize",
                   "NeuronExecution_create", "NeuronExecution_setInputFromMemory",
                   "NeuronExecution_setOutputFromMemory", "NeuronExecution_compute", "NeuronExecution_free",
                   "NeuronMemory_createFromFd", "NeuronMemory_free", "Neuron_getVersion"]
        api.write_text("\n".join(f'extern "C" int {name}() {{ return 0; }}' for name in symbols))
        sdk = directory / "libneuronusdk_adapter.mtk.so"
        subprocess.run(flags + [str(api), "-o", str(sdk)], check=True)
        subprocess.run(java + ["adapter_bundled_v8", sdk.name], env=env, check=True)
        legacy = directory / "libneuron_adapter_mgvi.so"
        api.write_text('extern "C" int NeuronModel_create() { return 0; }\n')
        subprocess.run(flags + [str(api), "-o", str(legacy)], check=True)
        subprocess.run(java + ["adapter_legacy_shadow", f"error:MediaTek adapter {legacy.name} lacks NeuronModel_setName"],
                       env=env, check=True)
        legacy.unlink()
        sdk9 = directory / "libneuronusdk_adapter.9.mtk.so"
        shutil.copyfile(sdk, sdk9)
        api.write_text('#include <cstdint>\nextern "C" int NeuronService_getNeuroPilotMagicNumber(int32_t* magic) { *magic = 300; return 0; }\n')
        subprocess.run(flags + [str(api), "-o", str(directory / "libneuron_sys_util.mtk.so")], check=True)
        subprocess.run(java + ["adapter_bundled_v9", sdk9.name], env=env, check=True)


if __name__ == "__main__":
    main()
