# LiteRT CPU 模型加载修复 / LiteRT CPU model loading fix

## 中文

### 根因与修复

部分 ROM 的 SELinux 拒绝应用通过 `/proc/self/fd/N` 重新打开自身创建的 memfd。
CPU 路径在 LiteRT `Model.load()` 阶段失败，因此关闭硬件加速和 NPU 回退都会受影响。
修复不使用 Root、系统权限或 SELinux 策略修改。

`OcrLiteCpu` 保留原有 shape 描述校验和字节修补，把结果完整写入
`cacheDir/ocr-cpu-sessions/`，再通过绝对路径加载。钉版 LiteRT Kotlin API 没有
ByteBuffer 加载入口；[原生模型加载器](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/core/model/model_load.cc)
还保留来源路径，因此文件保留至张量、编译模型和模型对象关闭之后。

每个文件使用转换模型 SHA-256 前缀和随机后缀，不复用其他版本或尺寸的修补结果。
目录锁保护跨进程创建与残留清理；独立租约文件的锁保护存活会话，避免 LiteRT 关闭
模型 fd 时释放 POSIX 锁。同进程还记录存活路径，避免租约探测 fd 释放已有锁。
创建或加载失败会清理半成品，进程被杀
后的残留在下一次创建时回收。缓存只保存当前会话需要的额外修补副本。

CPU 初始化异常通过宿主协议返回 `error_code=cpu_initialization_failed`。
业务调用将其转换为 `AndroidOcrInitializationError`（继承 `SystemExit`），停止当前 AP
实例，避免通用异常处理反复重启游戏。诊断模式仍返回 `RuntimeError`，可以重新测试。
网络中断仍保留原有一次重试，NPU 失败仍先尝试 CPU；只在 CPU 初始化也失败时停止实例。

### 验证与边界

按用户要求跳过真机测试。本地已执行的 OCR Python 协议与适配器测试为 21 项，
20 项通过、1 项因可选 ONNX 依赖缺失跳过，包括新增的业务停止和诊断重试案例。
生产 Kotlin 与新增 instrumentation 测试源码编译通过；测试编译临时排除了旧 PI 文件，
没有修改仓库的测试配置。当前缺少本地 OCR 厂商运行库，因此编译跳过了
`verifyBundledAzurPilotRuntime` 和 `verifyBundledOcrRuntime`，不代表打包校验通过。
改动的原生桥使用 NDK 28.2 对 ARM64 和 x86_64 单独编译、链接通过。完整 CMake 构建
因未准备 HiAI/MNN 源码无法完成。数值对照、原生 JNI 测试及新增设备测试未执行。

新增 `OcrCpuModelFileTest` 覆盖并发文件隔离、进程残留回收、坏模型加载清理、
冷建会话、动态宽度重复创建、关闭硬件加速，以及故意损坏 NPU 库后的 CPU 回退。
在准备好 OCR 运行库并连接设备后，可用 Android instrumentation 单独选择该测试类。
仓库现有 PI instrumentation 测试引用已移除的类，完整测试源集需先修复或排除该旧文件。

Redmi K40 验收仍需在重启后且未加载任何 SELinux 放行规则的 Enforcing 环境执行：

1. 关闭硬件加速，冷启动 APK，执行 `ocr-test-cpu`。
2. 确认 `cpu_execution_verified=true` 和 `cpu_npu_dispatch_partitions=0`。
3. 启动 runtime，执行 `ocr-ap-config-test`，确认四种语言识别 `12345`。
4. 反复切换任务与模型，确认原生日志不再报告打开 `/proc/self/fd/...` 失败。
5. 执行 Commission、Tactical 等真实任务，并验证 NPU 失败时业务使用 `litert_cpu`。

QNN logger 4000 的独立兼容性问题仍需后续调查。修补副本增加会话期间的缓存写入和
磁盘占用；缓存空间不足会明确停止受影响实例，重启游戏无法解决该错误。

## English

### Cause and fix

SELinux on some ROMs prevents apps from reopening their own memfd through `/proc/self/fd/N`.
CPU initialization fails at LiteRT `Model.load()`, affecting both disabled acceleration and
NPU fallback. The fix uses no root access, system permissions, or policy changes.

`OcrLiteCpu` preserves shape descriptor validation and byte patches, writes the complete
result under `cacheDir/ocr-cpu-sessions/`, and loads its absolute path. The pinned Kotlin API
has no ByteBuffer loading entry. The [native loader](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/core/model/model_load.cc)
also retains the source path, so files remain until tensors, compiled models, and models close.

Each file uses the converted model SHA-256 prefix and a random suffix. Patched files are never
reused across versions or shapes. A directory lock protects cross-process creation and cleanup;
separate lease-file locks protect live sessions even when LiteRT closes a model descriptor.
An in-process live-path registry avoids releasing POSIX locks by opening and closing a lease
probe descriptor. Creation and loading failures clean
partial files; the next creation reclaims files left by killed processes. Extra patched copies
exist only for current sessions.

The host reports CPU initialization errors as `error_code=cpu_initialization_failed`.
Business calls convert them to `AndroidOcrInitializationError`, a `SystemExit` subclass that
stops the affected AP instance and bypasses generic game restart handling. Diagnostics still
report `RuntimeError` and may be retried. Network interruptions retain one retry; NPU errors
still try CPU first. Only a failed CPU initialization stops the instance.

### Validation and limits

Device tests were skipped at the user's request. The previously executed OCR Python protocol
and adapter suite ran 21 tests: 20 passed and one skipped for a missing optional ONNX dependency.
It includes new instance-stop and diagnostic-retry cases. Kotlin compilation results are
successful for production and new instrumentation sources, temporarily excluding the old PI
test without changing repository test configuration. Local vendor OCR runtime files are absent,
so compilation skipped `verifyBundledAzurPilotRuntime` and `verifyBundledOcrRuntime`; packaging
verification did not pass. The changed native bridge compiled and linked separately for ARM64
and x86_64 with NDK 28.2. The full CMake build could not complete without prepared HiAI/MNN
sources. Numerical comparisons, native JNI tests, and new device tests were not run.

`OcrCpuModelFileTest` covers concurrent file isolation, abandoned-file cleanup, failed model
loading, cold session creation, repeated dynamic widths, disabled acceleration, and CPU fallback
after intentionally corrupting NPU libraries. With OCR runtime assets prepared and a connected
device, select that class in Android instrumentation. An existing PI instrumentation test
references removed classes; fix or exclude that old file before compiling the entire test set.

Redmi K40 acceptance remains pending in Enforcing mode after a reboot without any policy overrides:

1. Disable acceleration, cold-start the APK, and run `ocr-test-cpu`.
2. Confirm `cpu_execution_verified=true` and `cpu_npu_dispatch_partitions=0`.
3. Start the runtime, run `ocr-ap-config-test`, and confirm `12345` for all four languages.
4. Repeat tasks and models; verify native logs have no `/proc/self/fd/...` open failures.
5. Run Commission and Tactical tasks and confirm `litert_cpu` after NPU failure.

QNN logger error 4000 remains a separate compatibility issue. Patched copies add cache writes
and disk usage during sessions. Insufficient cache space stops the affected instance explicitly;
restarting the game cannot repair that failure.
