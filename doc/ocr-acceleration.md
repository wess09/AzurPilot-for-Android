# OCR NPU 加速 / OCR NPU acceleration

## 中文

### 调用链

2026-10-09 起使用模型格式 2：原始权重已移除，CPU 复用 LiteRT，末尾 Softmax 可在 GPU 执行。
细节与验证边界见 [同权重 CPU 和 GPU Softmax](ocr-litert-cpu-gpu-20261009.md)。

APK 内置五个识别器和一个检测器的 LiteRT FP32 模型；海思另保留四个 MNN 转换。原始
ONNX、NCNN 和旧 CnOCR 权重不再随 APK 或 rootfs 发布。AP 的 ONNX 会话工厂由
`android_ocr.py` 在 Android 运行时代理，按模型身份文件中的源 SHA-256 匹配 APK 白名单，向
`127.0.0.1:22302` 发送预处理后的 NCHW 张量。宿主负责推理，AP 保留语言字典、CTC
解码和后处理。接口运行于私有绑定的 `:ocr` 工作进程，与主界面和特权设备桥分离，
不需要 root 或 Shizuku 权限。

LiteRT CompiledModel 通过厂商编译、dispatch 插件调用 NPU，未注册 NNAPI。
高通使用 QNN HTP，联发科使用 NeuroPilot。海思使用独立的 MNN → HiAI 后端：
V320 客户端明确请求 NPU，禁用 MNN 的 CPU 备援。模型和库随 APK 安装，运行时不依赖
Google Play 服务下载或网络连接。每次 PRoot 启动会同步 APK overlay，已有 rootfs
也可随轻量 APK 更新接入。上游改变权重时要求更新 APK，不会误用旧转换或静默运行原权重。
宿主为当前厂商创建独立的库目录，其中只放指向安装目录的符号链接，防止另一厂商的
离线编译器抢先接管模型；APK 更新后会刷新链接。
Manifest 还以 `required=false` 请求厂商公开的 RPC、NeuroPilot 和 HiAI 系统库；Android 12+
要求显式声明这些非 NDK 库，缺少库的平台仍能安装并使用 CPU。
这些声明不能让 App 访问未向应用开放的私有驱动库；厂商必须公开对应服务接口。

### 支持范围

| 平台 | 当前实现 | 条件和限制 |
| --- | --- | --- |
| 高通 | LiteRT → QNN HTP | Android 12+、ARM64，内置 v68/v69/v73/v75/v79/v81 Skel 和 Stub；实际以驱动和算子编译成功为准 |
| 联发科 | LiteRT → NeuroPilot | Android 12+、ARM64，内置 8.0.10 / 9.0.3 adapter；系统必须开放兼容的 APU 驱动 |
| 小米设备中的高通 / 联发科芯片 | 使用芯片对应后端 | 按 SoC 厂商识别，与设备品牌无关 |
| 小米玄戒 | CPU 回退 | 已找到官方 XNN 接口头文件，尚未取得匹配的用户态运行库和模型转换器 |
| 海思 / 麒麟 | MNN → HiAI NPU | Android 10+、ARM64，内置官方示例的 NPU 最小库集；需兼容的 HiAI 驱动，全部算子必须被后端接受 |
| 三星 Exynos | CPU 回退 | 已取得公开 AI LiteCore SDK，但四个内置识别器的 AOT 编译均失败；尚未启用此后端 |
| Google Tensor | CPU 回退 | AOT 需要外部 Beta SDK 和芯片专用产物；尚未取得 SDK |
| Android 9 / x86_64 | LiteRT CPU | 保留原 App 的安装范围；Android 10–11 可尝试海思后端 |

不能仅凭厂商或 SoC 名称承诺 NPU 可用。LiteRT 官方的厂商与芯片范围参见
[NPU 文档](https://developers.google.com/edge/litert/next/npu)、
[高通](https://developers.google.com/edge/litert/next/qualcomm) 和
[联发科](https://developers.google.com/edge/litert/next/mediatek)。海思库来自
[华为 HiAI 官方示例](https://gitee.com/huawei-hiai-foundation/HiAIDemo)，玄戒已公开
[XNN 配置接口](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/dijun-v-oss/platform/O1/npu/mnne/include/xnn/c_api/XnnConfig.h)。
本实现只对完整打包的后端尝试推理。

[LiteRT 2.1.0rc1 的芯片表](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/vendors/qualcomm/supported_soc.csv)
将骁龙 855（SM8150）和 865（SM8250）列为 Hexagon v66。内置的 QNN HTP 插件从 v68 起，
识别到 v65/v66 设备时直接使用 LiteRT CPU，并在诊断中报告 `npu_state=unsupported_soc`。

固定形状识别请求 `[N,3,48,320]` 可尝试 NPU，`N` 拆成单图且不改变输入宽度。
其他宽度、检测器和 pro 档使用宿主 LiteRT CPU，不改写图片宽度。Android 的保存 NCNN 配置
在内存中转接宿主并保留模型档位；未内置版本明确报错。CPU 会话缓存最多两个，单宿主串行推理；
钉版 MTK dispatch 使用全局 adapter，因此同时只保留一个 MTK NPU 会话。

### 回退与状态

宿主首先确认原 LiteRT 模型没有 custom 操作，再通过公开 C 模型 API 检查 JIT 后的
主图是否生成 custom dispatch 分区，并用已编译会话的
`LiteRtCompiledModelIsFullyAccelerated` 补充诊断。没有分区时拒绝静默 CPU 替代。
Kotlin 的单独 NPU 选项会自动加上 CPU，所以创建成功不能证明 NPU 执行。
`litert_all_ops_delegated` 也可能由 CPU delegate 置为真，不能单独用于判定 NPU。
**设置 → OCR 加速 → 使用硬件加速** 默认开启，选择保存到 App DataStore。
关闭时等待当前推理结束、释放硬件会话，后续 App 测试与 AP 请求直接使用同权重 LiteRT CPU；
不用重启 AP。冷启动和 OCR 工作进程重建先读取保存值，避免先按默认值运行一次 NPU。
此开关控制 Android 宿主 OCR；配置文件不被改写，未知模型要求更新 APK。

开启时仅在硬件库、驱动、编译、推理或输出尺寸、有限值检查发生实际错误时关闭该模型的
硬件会话并回退 CPU；不支持的厂商和尺寸也使用 CPU。1.2.116 起不再在业务调用中额外运行
CPU 对照，也不因分数差或字符分类差回退。诊断测试仍显示最大分数差和字符选择一致性，
由用户判断是否关闭加速。这些数值不是字符错误率。

加载 MTK adapter 前，先尝试打开系统 `libapuwareutils_v2.mtk.so` 或
`libapuwareutils.mtk.so`，确认其导出 `queryHwConfigInternal`。8.0.10 adapter 的
初始化会直接调用该入口，缺失时会跳转空地址。Manifest 把两库声明为可选公开依赖；
无法打开或入口缺失时报告原因并使用 LiteRT CPU。若 v2 库能打开但缺少入口，则直接拒绝，
因为 adapter 在这种情况下不会改试旧版库。此检查只排除已知初始化故障，不保证驱动兼容。

钉版 LiteRT 的 adapter 加载器会遍历全部候选，以最后成功加载者为准。
MT6985 的 Android 16 日志显示：内置 SDK 加载后，系统旧 `libneuron_adapter_mgvi.so`
覆盖了它；该库缺少 `NeuronModel_setName`，编译插件在 `0x333a8` 调用空函数指针。
构建通过 AGP 的 `MERGED_MANIFEST` artifact 过滤 AAR 对旧 MGVI 的声明，保留内置 8/9 SDK。
当前合并器对 `uses-native-library` 的 `tools:node="remove"` 不按库名匹配，不能仅加删除标记。
APK 校验器扫描最终二进制 Manifest 的 UTF-8/UTF-16 字符串池，仍含旧 MGVI 时拒绝发布。
编译前探测按上游规则选择最终 adapter，并检查 23 个基本编译和执行入口；缺失时回退 CPU。
日志和状态的 `mediatek_adapter_library` 记录实际选中的库名。
加载规则依据[钉版 adapter 源码](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/vendors/mediatek/neuron_adapter_api.cc)。

LiteRT 2.1.0rc1 的 Kotlin `Model.handle` 指向 JNI `ModelWrapper`，不能直接传给
C 模型 API。诊断桥按该钉版布局读取首成员 `LiteRtModel`，仅接受 `2.1.0rc1`；构建也会
拒绝未经重新验证的升级。包装对象布局依据
[上游钉版源码](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/kotlin/src/main/jni/litert_model_wrapper.h)。
`CompiledModel.handle` 已是原始 C 会话，不能再次按 `ModelWrapper` 解包。
`test_ocr_jni.py` 在 Linux JVM 编译生产 JNI，执行七项模型句柄检查、八项已编译
会话检查、三项 MTK 驱动入口检查、三项 APUSys 加载检查和四项 adapter 选择检查。
模拟库复现旧 MGVI 覆盖内置 SDK、缺失入口的
条件，并检查 8/9 SDK 选择；它不验证 Android 驱动。

首次初始化和校验阶段同步保存到启动器日志的 `debug/ocr/stages.jsonl`，复制 OCR 报告
也会包含 `last_stage`。导出启动器日志时额外收集 Android 11+ 的进程退出原因；
Android 12+ 如仍保留原生 tombstone，则附带 `debug/process-exits/trace_*.pb`。
系统可能已清除堆栈，空缺不能据此排除原生崩溃。原生信号仍不能被 Kotlin 异常捕获，
因此所有推理库放在 `:ocr` 进程。工作进程退出后，主进程记录当前模型并重新绑定，
该模型改用 CPU；无法确定模型时暂禁全部 NPU。禁用记录持续到 APK 版本升级，避免
重启再次触发同一崩溃。若退出摘要或匹配的最后阶段表明正在执行 CPU 路径，主进程仅采集
日志并重绑，不新增 NPU 禁用记录。AP 和 App 测试使用同一回环 API，连接中断后允许重试一次。

MT6985 的 1.2.103 日志已通过 adapter 选择和编译调用，没有新增原生崩溃，但两个
测试模型仍因「没有分区」使用 CPU。这份日志缺少厂商编译错误，尚不能确定具体
算子或驱动原因。初始化完成或失败后保存本 OCR 进程的 logcat 快照到
`debug/ocr/native-<模型哈希前12位>.log`，每个模型最多约 512 KiB、采集最多两秒。
不请求额外日志权限，系统限制或日志清除仍可能使快照为空。

`launcher_logs_20261008_154409.zip` 的 test2 快照显示：MTK Neuron 拒绝
`BatchMatMul` 的输入秩，导致英文和 tiny 识别器各自生成零个分区。对应矩阵乘法
使用 `[1,40,K]` 激活和 `[K,C]` 常量；tiny 首层还带输入转置选项。转换脚本
把较低秩的常量克隆为 `[1,K,C]`，复用权重缓冲区，保留原常量的其他消费者、
输出和转置选项。四个识别器分别修正 2、9、9、9 处；没有增加运行时算子。
依据[钉版 MTK 算子转换源码](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/vendors/mediatek/compiler/legalizations/batch_matmul_op_legalization.cc)，
插件将输入形状直接交给 Neuron，没有补齐两输入的秩。
六项模型回归测试包含 16 种广播、常量位置和转置组合，通过真实 LiteRT CPU
执行确认输出一致，并覆盖共享常量输出、重复处理和拒绝条件。四模型的 ONNX
数值对照仍通过，最大绝对误差约 `4.6e-5`。这排除了日志中发现的秩差异，
实际 NPU 分区、运行和精度仍需更新 APK 后真机确认。
另在 Linux 中把 AP 冒烟测试的宿主替身改为本次 LiteRT 模型的 CPU 执行，四个
识别器通过原 AP 工厂、RapidOCR 预处理、回环代理和 CTC 解码均识别出 `12345`。
该检查不运行 Android 厂商驱动或完整 PRoot。

`launcher_logs_20261008_155930.zip` 的 1.2.105 两份新堆栈显示，模型已进入
`NeuronCompilation_finish`，随后 SDK 8 在 `0x7dcedc` 调用 `abort`。
反汇编显示目标查找结果为空时走到此分支；堆栈本身没有说明具体缺失目标，
包中的原生日志快照仍是上一版，不能用它断言本次也发生相同秩错误。
针对 MT6985 的测试策略在 adapter 初始化前将工作进程环境变量
`MTKNN_ADAPTER_CONFIG_TARGET` 设为 `mdla`，尝试避免选择未注册的编译目标。
该名称和目标字符串读取路径来自钉版 SDK 二进制；修复效果仍需真机验证。
其他芯片保持自动选择。状态记录 `mediatek_target_policy`，目标配置不作为 NPU
执行证据；仍要求实际分区、成功推理及原 ONNX 数值对照。
主进程在工作进程退出后，按系统退出记录或本版最新阶段的 PID 采集同 UID 的
logcat，覆盖所选模型的快照，避免 native abort 后只导出旧日志。采集仍有界，
不启动常驻子进程或请求额外权限。

`launcher_logs_20261008_161220.zip` 的 1.2.106 快照确认 SDK 已采纳 `mdla` 策略，
为英文识别器从 342 个算子中选中 308 个，生成 35 个候选分区。随后加载
`libapuwareapusys_v2.mtk.so` 失败，无法创建 MDLA 设备，仍在相同位置主动 `abort`。
候选分区不是已完成编译或成功推理的证据。Manifest 补齐 SDK 动态引用的 APUSys、
XRP、HMP、CMDL 和 NEON 库可选声明；此前只有 APU 配置工具库可见。
依据 [Android 的非 NDK 库规则](https://developer.android.com/guide/topics/manifest/uses-native-library-element)，
Android 12+ 需要显式请求厂商公开库。APK 校验器也要求这些依赖声明，防止后续遗漏。
MT6985 的 MDLA 策略在加载 adapter 前先尝试打开 APUSys 执行库；失败时报告链接错误，
保留原 ONNX CPU 路径，避免进入已知崩溃分支。JNI 回归覆盖执行库缺失、存在和传递
依赖缺失，且不调用私有驱动入口。声明和加载检查仍不能证明系统服务权限、驱动兼容
或硬件执行，修复后的实际运行需要真机确认。

`launcher_logs_20261008_163411.zip` 的 1.2.107 日志没有新增原生退出：英文模型编译后
因 `NeuronCompilation_getOutputPaddedDimensions` 只接受最多 4 维张量而失败；tiny
模型生成 6 个实际 dispatch 分区并完成推理，但没有通过原 ONNX 精度校验，所以仍回退 CPU。
转换脚本消去 reshape → transpose → split 链中的内部单维轴，保持公开输入输出和数据
顺序，克隆控制常量以保护共享消费者。small、英文和中文分别改写 10、11、11 个中间
张量，tiny 无需此改写。五项新增回归覆盖真实 LiteRT CPU 执行、共享控制输出、重复处理
和拒绝条件；四模型 ONNX 对照仍通过，最大绝对误差约 `4.6e-5`。更新模型的 AP 冒烟测试
仍全部识别出 `12345`，但它不执行 Android 驱动。

日志还显示无填充 FP32 缓冲区带有厂商生成的 stride 描述。
[钉版 dispatch 源码](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/vendors/mediatek/dispatch/litert_dispatch_invocation_context.cc)
会为无填充张量保留此描述，[上游 2.2](https://github.com/google-ai-edge/LiteRT/blob/v2.2.0/litert/vendors/mediatek/dispatch/litert_dispatch_invocation_context.cc)
已在无填充时清除它。APK 通过公开 dispatch C ABI 包装钉版库：原库改名为
`libLiteRtDispatch_MediaTek_Vendor.so`，原始校验和不变；标准库名由本地编译的包装库提供。
包装只接受钉版 ABI，在静态 FP32、1–4 维、原张量无显式 stride、缓冲大小等于紧密布局，
且 stride 前缀完全匹配钉版生成规则时清除描述。真实填充和未知布局保持原厂要求，缓冲类型、
对齐、所有执行接口保持不变。Linux 回归编译生产包装库，检查输入输出回调、并发初始化、
分配失败的句柄释放、错误 ABI 和缺失原库；Android NDK 构建检查实际包装库。
此修正针对布局疑点，不能据此认定 tiny 的精度问题已经解决。首次数值对照现在记录
`npu_accuracy`，包含 `max_abs_error` 和 `timestep_mismatches`，精度门槛不变。
SDK 没有二进制修改，实际 MTK 结果仍需 1.2.108 真机日志验证。

1.2.112 已在连接的 MT6985 / Android 16 设备上完成 Debug 和 R8 Release 验证，
四个识别模型均生成实际 dispatch 分区并完成推理、原 ONNX 对照和 AP 接线。
完整数据、之前的 NaN 原因定位、模型切换修复及当前限制见
[MT6985 真机验收记录](ocr-mt6985-validation-20261008.md)。
当时将运行时门槛调整为 `0.15`，并要求字符预测一致；1.2.116 起按用户要求移除此门槛，
由硬件加速开关决定选择，分数差仅作诊断。`0.15` 是概率尺度上的绝对差，不是字符错误率。
small / 中文的末尾 Softmax 从 LiteRT 图中拆出；本版优先在 GPU 计算，GPU 实际错误时
使用稳定 CPU 算法。主体交由 NPU 分区执行，公开输出形状及 AP 解码不变，原权重不再发布。

海思必须成功创建 HiAI 会话、后端为 `MNN_FORWARD_USER_0`，并返回 V320 就绪状态。
该后端明确使用 `AiModelDescription_DeviceType_NPU`，请求成功后报告 `hiai_npu`；
`hiai_npu_only_session_ready` 记录软件层面的就绪证据。错误回退 LiteRT CPU，
转换模型的 CPU 执行明确报告为 CPU。

厂商可能只接受部分算子，所以 `litert_npu_with_cpu_fallback` 允许其余算子由 CPU 执行。
`npu_dispatch_partitions` 与 `npu_delegation_verified` 提供软件层面的分区和成功运行证据，
不代表所有算子在 NPU 执行，也不代表更快。`npu_hardware_profile_verified` 保持 `false`，
直到实现硬件性能分析；发布前仍需真机测量识别质量、初始化时间、稳定推理延迟和内存。

在继承宿主环境变量的 AP Python 子进程中运行：

```bash
cd /opt/azurpilot
.venv/bin/python -m android_ocr
```

命令输出 SoC、厂商、运行库是否就绪、模型会话、分区证据及回退原因，不打印口令。
输入输出原始张量为 little-endian FP32，JSON 行头之后按 `length` 读取二进制数据。
`describe` 和 `run` 要求 `model_sha256`，所有请求要求 `AZURPILOT_ANDROID_TOKEN`；
`status` 仅返回状态。服务只绑定回环地址，头部限 16 KiB，数据限 64 MiB，连接最多四个。
客户端每次请求释放连接，网络中断重试一次；服务无法访问时明确报错，不运行原权重。

### App 内测试

无需界面点击的 ADB 状态查询、模型测试、AP 接线和日志导出，见 [ADB 调试入口](adb-debugging.md)。

打开 **设置 → OCR 加速**，可查看芯片、加速库是否就绪，以及每个内置模型最近一次的
实际后端、AP 业务调用次数、测试调用次数和 CPU 回退原因。进入页面不会创建推理会话；
模型尚未运行时显示「尚未运行」。退出页面后停止自动刷新。
加速库显示「已内置」，加速状态单独显示待验证、调用通过或失败后使用 CPU。
报告的 `npu_libraries_bundled` 表示安装文件存在；旧字段 `npu_libraries_ready` 保留相同
含义以兼容客户端，不作为成功执行的证据。`npu_state=verified` 表示软件分区证据与成功推理，
不表示游戏识别准确率或硬件性能分析已通过；`user_disabled` 表示用户主动选择 CPU。

点击 **运行测试**，选择的识别器通过带口令的真实回环 API 推理 `ocr/test/sample.png`。
测试图片为本项目生成的 320×48 白底数字「12345」。首次耗时包含模型初始化；
稳定耗时及独立 LiteRT CPU 对照分别预热三次，再测二十次并保存逐次结果。CPU 对照
只请求 CPU、不传厂商 provider，且检查图中没有 NPU dispatch 分区；不借用业务会话。
两者均为固定尺寸单模型推理，含张量复制与有限值检查，不含 AP 图像处理、传输和解码。
结果比较形状、最大绝对误差和
各时间步字符分类，仅供参考，不因此回退。CPU 成功不会被标记为 NPU 成功。
业务调用不运行额外 CPU 对照，游戏识别质量需用实际画面验证。

AP 已运行时，同一个按钮还通过 PRoot 启动使用正常环境变量的 AP Python 子进程，调用
`module.ocr.al_ocr._create_ocr`，验证原有 RapidOCR 预处理、宿主推理和 CTC 解码。
模型版本不一致、会话工厂未接入或静默回退原 Python 推理都会报告失败。示例文字是否
正确识别单独显示；接线成功不代替游戏画面的识别质量测试。该测试显式选择所选 ONNX
识别器，不修改实例配置。Android 的 NCNN 选择转接宿主，未知版本明确报错。
AP 未启动时仍可测试内置模型，页面会提示启动 AP 后重新测试接线。

`ocr-ap-config-test` 额外读取当前实例的保存配置，调用正常 `AlOcr.ocr`，不强制 ONNX
或某个模型，也不启动游戏任务。页面单列最近 AP 业务后端和输入尺寸，诊断不会覆盖
`last_ap_call`；暂无业务计数时，不能把接线或配置测试说成正在运行的游戏调用证明。

**复制测试报告** 包含芯片、运行库版本、实际后端、调用计数、耗时、数值对照和 AP
识别结果，不包含认证口令。诊断调用不增加 AP 业务计数；业务计数只代表此 OCR 工作进程
收到的宿主请求，不代表整个历史任务的累计次数。

### 构建与验证

2026-10-08 的软件验收已通过 Kotlin 编译、Debug/Release 打包、R8 保留检查、15 项
协议和配置诊断测试、17 项打包质量门测试和文案一致性检查。Linux 侧还从固定 AP 源码提取并原样
执行 `RecOnlyOCR`、`OcrSettings` 和识别器工厂，配合真实 RapidOCR 3.9.0 与 ONNX CPU
宿主协议替身，四个识别模型均正确解码测试图片的「12345」。这项隔离依赖的验证未执行
完整 PRoot 运行时或 Android 厂商驱动；完整设备端调用由 App 内测试入口验证。

LiteRT 核心与 JIT 插件统一锁定在
[v2.1.0rc1](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0rc1)。这是预发布版本，
选用原因是其公开 JIT 包同时包含高通与联发科插件。升级核心须同步重建或更新两套插件，
不能混用 2.2 核心与旧插件。QNN 锁定 2.40.0。下载器验证来源 SHA-256，白名单提取，
不执行下载包的脚本；SDK 许可文本随 APK 打包。

海思 SDK 固定到 HiAIDemo 提交 `220e2070f1fad9005d6ed06d33520e559cdac7ec`，
MNN 源码固定到 `024a946b0b8fcf87c8a418229fadd4cd7858ffba`。下载器提取五个 ARM64
NPU 客户端库、匹配的头文件与许可文本。MNN 在 NDK 构建中编译，仅开启 HiAI 插件。
该 SDK 未导出且 ARM64 编译代码未引用的四个旧 ABI 分发表条目被删除；真实引用的
缺失符号仍由链接器拒绝。来源、修改与库哈希记录在 `hiai-runtime.json`。

```bash
python app/scripts/fetch_ocr_runtime.py
python app/scripts/test_verify_ocr_assets.py
python app/scripts/test_ocr_jni.py  # Linux，需要 JDK 和 C++ 编译器
python rootfs/tests/test_android_ocr.py  # 需要 numpy
cd app
./gradlew compileDebugKotlin -x verifyBundledAzurPilotRuntime
./gradlew assembleDebug -Pazurpilot.slimApk=true
python scripts/verify_ocr_assets.py app/build/outputs/apk/debug/*.apk
```

仅转换产物入库，普通 APK 构建不需要转换器。原始参考文件放在忽略的 `.tmp/ocr-sources/`，
路径与原 AP 的 `bin/ocr_models/` 下层一致，并按清单 SHA-256 校验。在 Linux / Python 3.12 中重新转换：

```bash
uv venv /var/tmp/alas-ocr-convert --python 3.12
uv pip install --python /var/tmp/alas-ocr-convert/bin/python -r app/scripts/ocr-models-requirements.txt
/var/tmp/alas-ocr-convert/bin/python app/scripts/prepare_ocr_cpu_models.py
/var/tmp/alas-ocr-convert/bin/python app/scripts/prepare_ocr_cpu_models.py --verify-only
/var/tmp/alas-ocr-convert/bin/python app/scripts/test_ocr_model_ranks.py
/var/tmp/alas-ocr-convert/bin/python app/scripts/prepare_hiai_models.py
/var/tmp/alas-ocr-convert/bin/python app/scripts/prepare_hiai_models.py --verify-only
```

模型格式 2 必须用 `prepare_ocr_cpu_models.py` 同时更新字节描述和转换哈希，
`--verify-only` 复验多尺寸的原 ONNX 数值对照。

四个识别器的空白、全黑和固定随机张量 CPU 对照均通过：LiteRT 最大绝对误差约 `4.6e-5`，
MNN 约 `0.0058`，每个时间步的字符分类一致。MNN 使用 FP32 高精度配置，但算子融合
会产生不同舍入误差，模型转换检查采用独立的 `0.01` 容差。
这些输入不足以代表游戏截图全集。MT6985 真机结果见上文；
转换测试或 APK 构建成功本身不能作为真机 NPU 性能验证。

### 其他厂商的接入调查（2026-10-08）

三星官方 AI LiteCore 1.2.0 下载地址可访问，包内提供 Exynos 9955 / 9965 的编译库。
使用独立 Linux 环境中的 `ai-edge-litert==2.2.0` 对四个现有 LiteRT 识别模型做 E9965
AOT 探测。先加载同版 LiteRT 核心和 SDK 的 `libgraphgen.so`，解决插件未链接其所需
符号的问题；随后四个模型均进入厂商编译阶段，但没有生成可用产物。英文和中文
AL OCR 模型明确报告 `Unsupported shape type UNDEFINED`，两个 PP-OCR 模型返回
编译错误。该结果只说明这组公开插件、SDK 和模型尚不兼容，不能外推为设备不支持 NPU。
SDK 的许可 PDF 使用 NASCA DRM，未取得可读取的许可内容；SDK 库未加入 APK。
三星 SDK 的使用环境和支持芯片见[官方 LiteRT 接入说明](https://developers.google.com/edge/litert/next/samsung)。

Google Tensor 的官方 `ai-edge-litert-sdk-google-tensor==2.2.0` 安装器没有公开的默认
SDK 下载地址，要求 `GOOGLE_TENSOR_SDK_BETA` 本地文件或 `GOOGLE_TENSOR_ML_SDK_URL`。
仅安装 Python 前端与编译插件不能生成目标 NPU 模型，因此仍需要厂商提供的 Beta SDK。
玄戒的 XNN 头文件同样不足以替代运行库和转换器。

## English

### Call path

As of 2026-10-09, model format 2 removes source weights, shares LiteRT weights with CPU, and
supports terminal GPU Softmax. See [CPU weight sharing and GPU Softmax](ocr-litert-cpu-gpu-20261009.md).

The APK bundles LiteRT FP32 models for five recognizers and one detector, plus four MNN
conversions for HiAI. Original ONNX, NCNN, and legacy CnOCR weights are no longer shipped.
`android_ocr.py` proxies AP's ONNX session factory only in the Android runtime, matches
source hashes in identity descriptors against the APK allowlist, and sends preprocessed NCHW tensors to
`127.0.0.1:22302`. The app performs inference; AP retains dictionaries, CTC decoding, and
postprocessing. A privately bound `:ocr` worker hosts the endpoint, separate from the UI
and privileged device bridge. It requires neither root nor Shizuku permissions.

LiteRT CompiledModel invokes vendor compiler/dispatch plugins without registering NNAPI:
QNN HTP for Qualcomm and NeuroPilot for MediaTek. A separate MNN → HiAI backend uses the
V320 client to request the NPU explicitly and disables MNN CPU backup. Models and libraries install with the
APK and require neither Play services downloads nor a network connection during inference.
Each PRoot start refreshes overlays from APK assets, including existing rootfs installations.
Changed upstream weights require an APK update; stale conversions and original-weight fallback are rejected.
The host exposes only the current vendor's plugins through a directory of symlinks to the
installed native libraries, preventing another vendor's offline compiler from taking the
model first. APK updates refresh those links.
The manifest requests public vendor RPC, NeuroPilot, and HiAI system libraries with `required=false`.
Android 12+ requires explicit declarations for such non-NDK libraries; devices without them
can still install and use CPU.
These declarations do not grant access to private driver libraries; the vendor must expose
the corresponding service interfaces to apps.

### Coverage

| Platform | Implementation | Conditions and limits |
| --- | --- | --- |
| Qualcomm | LiteRT → QNN HTP | Android 12+, ARM64; bundled v68/v69/v73/v75/v79/v81 Skel and Stub libraries; driver and operator compilation must succeed |
| MediaTek | LiteRT → NeuroPilot | Android 12+, ARM64; bundled 8.0.10 / 9.0.3 adapters; compatible APU drivers must be exposed to apps |
| Xiaomi phones with Qualcomm / MediaTek SoCs | Corresponding SoC backend | Uses the SoC vendor rather than phone brand |
| Xiaomi Xring | CPU fallback | Official XNN interface headers found; matching user-space runtime and model converter not obtained |
| HiSilicon / Kirin | MNN → HiAI NPU | Android 10+, ARM64; official demo's minimal NPU libraries bundled; compatible HiAI drivers required, and the backend must accept all operators |
| Samsung Exynos | CPU fallback | Public AI LiteCore SDK obtained, but AOT compilation failed for all four bundled recognizers; backend not enabled |
| Google Tensor | CPU fallback | AOT requires an external beta SDK and chip-specific artifacts; SDK not obtained |
| Android 9 / x86_64 | LiteRT CPU | Preserves the installation range; Android 10–11 may use the HiAI backend |

Vendor or SoC names alone do not establish NPU availability. Consult the official
[NPU overview](https://developers.google.com/edge/litert/next/npu),
[Qualcomm](https://developers.google.com/edge/litert/next/qualcomm), and
[MediaTek](https://developers.google.com/edge/litert/next/mediatek) coverage. This implementation
attempts only complete bundled backends. HiAI clients come from the
[official Huawei demo](https://gitee.com/huawei-hiai-foundation/HiAIDemo). Xiaomi has published
[XNN configuration headers](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/dijun-v-oss/platform/O1/npu/mnne/include/xnn/c_api/XnnConfig.h).

The [LiteRT 2.1.0rc1 SoC table](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/vendors/qualcomm/supported_soc.csv)
lists Snapdragon 855 (SM8150) and 865 (SM8250) as Hexagon v66. The bundled QNN HTP plugin starts
at v68. Known v65/v66 devices use LiteRT CPU directly and report `npu_state=unsupported_soc`.

Recognizer requests shaped `[N,3,48,320]` may use NPU; batches split into single images
without changing width. Other widths, the detector, and pro use host LiteRT CPU. Saved NCNN
settings route to the host in memory, retaining the selected model tier. Unknown versions fail
explicitly. The host serializes
inference and caches at most two CPU sessions. The pinned MTK dispatch uses a global adapter,
so only one MTK NPU session remains live at a time.

### Fallback and diagnostics

The host checks that the original LiteRT model has no custom operators, then uses the
public C model API to check for custom dispatch partitions in the JIT-transformed main graph.
`LiteRtCompiledModelIsFullyAccelerated` provides additional compiled-session diagnostics.
No partitions means rejecting silent CPU substitution. Kotlin adds CPU automatically to the
NPU-only option, so creation alone is not evidence of NPU execution.
CPU delegates can also set `litert_all_ops_delegated` to true; it is never sufficient NPU evidence.
**Settings → OCR acceleration → Use hardware acceleration** is enabled by default and persists
in the app DataStore. Disabling waits for current inference, frees hardware sessions, and sends
subsequent app tests and AP requests directly to LiteRT CPU with the same weights without restarting AP.
Cold startup and worker recovery load the saved choice before binding. This switch controls host
OCR models; saved configuration files are unchanged and unknown models require an APK update.

When enabled, only actual library, driver, compilation, inference, output-size or finite-value
failures gate a model to CPU. Unsupported vendors and dimensions also use CPU. Since 1.2.116,
business calls neither run an extra CPU comparison nor fall back for score or character differences.
Diagnostics still report maximum score difference and timestep character agreement so users can
decide whether to disable acceleration. These numbers are not character error rates.

Before loading the MTK adapter, the host opens the system `libapuwareutils_v2.mtk.so` or
`libapuwareutils.mtk.so` and checks for `queryHwConfigInternal`. The 8.0.10 adapter calls
this entry directly during initialization; a missing entry causes a null-address jump.
The manifest declares both libraries as optional public dependencies. Unavailable libraries
or entries produce a reported LiteRT CPU fallback. An open v2 library without the entry is
rejected immediately, because the adapter does not try the legacy library in that case.
This check excludes the known initialization fault, without proving driver compatibility.

The pinned LiteRT adapter loader tries every candidate and retains the last successful load.
Android 16 traces from MT6985 show the legacy system `libneuron_adapter_mgvi.so` overriding
the bundled SDK. That library lacks `NeuronModel_setName`; the compiler plugin calls the
null pointer at `0x333a8`. An AGP `MERGED_MANIFEST` artifact transform removes the AAR's
MGVI declaration, keeping bundled SDKs 8 and 9. The current merger does not match
`uses-native-library` removal markers by name; a marker alone is insufficient.
The APK verifier rejects legacy MGVI in the final binary manifest's UTF-8/UTF-16 string pool.
Before compilation, a probe follows the upstream selection rule and checks 23 basic entries, falling back
to CPU on missing entries. Logs and `mediatek_adapter_library` report the selected library.
See the [pinned adapter source](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/vendors/mediatek/neuron_adapter_api.cc).

Kotlin `Model.handle` in LiteRT 2.1.0rc1 points to a JNI `ModelWrapper`, which cannot be
passed directly to C model APIs. The diagnostics bridge reads its first `LiteRtModel` member
under the pinned layout and accepts only `2.1.0rc1`; builds also reject unvalidated upgrades.
The layout follows the
[pinned upstream source](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/kotlin/src/main/jni/litert_model_wrapper.h).
`CompiledModel.handle` is already the raw C session and must not be unwrapped as `ModelWrapper`.
`test_ocr_jni.py` compiles production JNI on a Linux JVM for seven model-handle checks,
eight compiled-session checks, three MTK driver-entry checks, three APUSys loading checks,
and four adapter-selection checks.
Mock libraries reproduce
legacy MGVI overriding the bundled SDK and missing entries, and check SDK 8/9 selection.
These checks do not validate Android vendor drivers.

Initialization and first validation stages are flushed to launcher logs at
`debug/ocr/stages.jsonl`; copied OCR reports include `last_stage`. Launcher exports also
collect process exit reasons on Android 11+. If Android 12+ still retains a native tombstone,
the export includes `debug/process-exits/trace_*.pb`. Missing traces do not rule out native
crashes because the system may have discarded them. Kotlin cannot catch native signals,
so all inference libraries run in `:ocr`. After worker death, the main process records the
active model and rebinds with CPU recovery for that model, or all NPU models if unknown.
Gates persist until an APK version upgrade to prevent repeating the crash after restart.
If the exit summary or matching last stage identifies the CPU path, the host collects logs and
rebinds without adding an NPU gate.
AP and in-app tests use the same loopback API and retry a broken connection once.

MT6985 logs from 1.2.103 passed adapter selection and compilation calls without a new native
crash. Both tested models still fell back to CPU for missing partitions. Those logs lack vendor
compilation errors, so the operator or driver cause remains unknown. After initialization or
failure, the host saves this OCR process's logcat snapshot to
`debug/ocr/native-<first12HashChars>.log`, capped at roughly 512 KiB per model and two seconds
of collection. No extra logging permission is requested; system restrictions or discarded
logs can still leave the snapshot empty.

The test2 snapshots in `launcher_logs_20261008_154409.zip` show Neuron rejecting
`BatchMatMul` input ranks, leaving both the English and tiny recognizers with zero NPU
partitions. These products combine `[1,40,K]` activations and `[K,C]` constants; the first
tiny product also transposes its input. The preparation script clones lower-rank constant
descriptors to `[1,K,C]`, shares weight buffers, and preserves other consumers, outputs,
and transpose options. The four recognizers require 2, 9, 9, and 9 repairs, without adding
runtime operators. The [pinned MTK legalization source](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/vendors/mediatek/compiler/legalizations/batch_matmul_op_legalization.cc)
passes input shapes to Neuron without aligning their ranks. Six regression tests use real
LiteRT CPU execution, including 16 broadcasting, constant-position, and transpose combinations,
shared constant outputs, idempotence, and rejection cases. All four ONNX comparisons still
pass with maximum absolute error about `4.6e-5`. This removes the rank mismatch found in
the logs; NPU partitioning, execution, and accuracy require another updated-APK device test.
A Linux AP smoke check also replaced its host substitute with CPU execution of these updated
LiteRT models. All four recognizers decoded `12345` through the original AP factory,
RapidOCR preprocessing, loopback proxy, and CTC decoder. This does not run Android vendor
drivers or the complete PRoot runtime.

The two new 1.2.105 tombstones in `launcher_logs_20261008_155930.zip` reached
`NeuronCompilation_finish` before SDK 8 called `abort` at `0x7dcedc`. Disassembly shows
this branch follows an empty compilation-target lookup. The stacks do not identify the
missing target, and the included native snapshots are from the previous APK, so they
cannot establish a repeated rank failure in this run. For MT6985, the test policy sets
the worker's `MTKNN_ADAPTER_CONFIG_TARGET` environment variable to `mdla` before adapter
initialization to try to avoid selecting unregistered compilation targets. The variable
and target-string lookup are present in the pinned SDK binary; effectiveness still needs
device testing. Other chips retain automatic selection. `mediatek_target_policy` records
the setting, which is not NPU evidence: actual partitions, successful inference, and original
ONNX comparisons remain required. After worker death, the host collects same-UID logcat
using the exit record's PID or the current APK's latest stage PID, replacing the selected
model snapshot so native aborts do not leave only stale logs. Collection stays bounded
without persistent child processes or additional privileges.

The 1.2.106 snapshot in `launcher_logs_20261008_161220.zip` confirms the SDK accepted the
`mdla` policy: it selected 308 of 342 English-recognizer operators and produced 35 candidate
partitions. Loading `libapuwareapusys_v2.mtk.so` then failed, preventing MDLA device creation
and reaching the same SDK `abort`. Candidate partitions do not prove completed compilation
or successful inference. The manifest now declares the SDK's dynamically referenced APUSys,
XRP, HMP, CMDL, and NEON libraries as optional dependencies; previously only the APU
configuration utilities were exposed. [Android's non-NDK library rules](https://developer.android.com/guide/topics/manifest/uses-native-library-element)
require explicit requests for public vendor libraries on Android 12+. The APK verifier
also requires these declarations to prevent omissions. Before adapter loading, MT6985's
MDLA policy probes APUSys library loading. Failure reports the linker error and preserves
original ONNX CPU execution, avoiding the known crash branch. JNI regressions cover an
absent library, a present library, and missing transitive dependencies without calling
private driver entries. Declarations and loading checks do not establish service access,
driver compatibility, or hardware execution; the updated path still needs device testing.

The 1.2.107 logs in `launcher_logs_20261008_163411.zip` contain no new native exit. The
English model fails after compilation because `NeuronCompilation_getOutputPaddedDimensions`
accepts at most four dimensions. Tiny produces six actual dispatch partitions and runs,
but fails the original ONNX accuracy gate and falls back to CPU. The conversion script removes
internal singleton axes in reshape → transpose → split chains while preserving public I/O,
data order, and shared controls by cloning control tensors. Small, English, and Chinese
require 10, 11, and 11 tensor repairs respectively; tiny needs none. Five added regressions
cover real LiteRT CPU execution, shared control outputs, idempotence, and rejection cases.
All four ONNX comparisons still pass with maximum absolute error about `4.6e-5`; the updated
AP smoke test still decodes `12345` for every recognizer without running Android drivers.

The logs also show vendor-generated strides for unpadded FP32 buffers. The
[pinned dispatch source](https://github.com/google-ai-edge/LiteRT/blob/v2.1.0rc1/litert/vendors/mediatek/dispatch/litert_dispatch_invocation_context.cc)
retains these descriptions, while [upstream 2.2](https://github.com/google-ai-edge/LiteRT/blob/v2.2.0/litert/vendors/mediatek/dispatch/litert_dispatch_invocation_context.cc)
clears them for unpadded tensors. The APK wraps the pinned library through the public dispatch
C ABI: the original is renamed `libLiteRtDispatch_MediaTek_Vendor.so` with its checksum
unchanged, and the locally built wrapper provides the standard library name. It accepts
only the pinned ABI and clears strides only for static rank-one-to-four FP32 tensors with
no explicit layout strides, an exactly packed buffer size, and a stride prefix matching
the pinned generator. Actual padding and unknown layouts retain vendor requirements;
buffer types, alignment, and all execution interfaces are preserved. Linux regressions
compile the production wrapper and check input/output callbacks, concurrent initialization,
allocation-failure ownership, wrong ABI, and missing vendor libraries. Android NDK builds
check the actual wrapper. This addresses a layout suspect without establishing that tiny's
accuracy failure is resolved. First-request comparison now records `npu_accuracy` with
`max_abs_error` and `timestep_mismatches`; accuracy thresholds are unchanged. SDK binaries
are unmodified, and actual MTK behavior still needs 1.2.108 device logs.

Version 1.2.112 completed Debug and R8 Release checks on the connected MT6985 / Android 16
phone. All four recognizers produced dispatch partitions, ran, passed original ONNX comparison,
and connected to AP. See [MT6985 device validation](ocr-mt6985-validation-20261008.md) for data,
NaN isolation, model-switch fixes, and limitations. That version used a `0.15` probability
difference gate and required matching predictions. Since 1.2.116 these gates were removed at
the user's request: the acceleration switch controls the choice and score differences are
diagnostics only. This is not a character error rate. Small and Chinese terminal Softmax now
prefers GPU, falling back to stable CPU postprocessing on actual GPU errors while the model
body uses NPU partitions. Public output shapes and AP decoding stay intact; source weights
are no longer shipped.

HiAI must create a ready session using `MNN_FORWARD_USER_0` and report the V320 ready state.
Its client explicitly requests `AiModelDescription_DeviceType_NPU`. Successful requests report
`hiai_npu`; `hiai_npu_only_session_ready` records software readiness. Errors fall back to LiteRT
CPU, explicitly reported as CPU execution rather than NPU evidence.

`litert_npu_with_cpu_fallback` permits remaining operators to execute on CPU.
`npu_dispatch_partitions` and `npu_delegation_verified` provide software evidence of partitioning
and successful execution. They prove neither full NPU execution nor improved speed.
`npu_hardware_profile_verified` remains `false` until hardware profiling is implemented.
Release testing must measure OCR quality, initialization, steady inference latency, and memory.

Run `.venv/bin/python -m android_ocr` from `/opt/azurpilot` in an AP child process that inherits
host environment variables. It reports SoC, vendor, library readiness, sessions, partition
evidence, and fallback reasons without exposing the token.

Requests contain one JSON line followed by `length` little-endian FP32 bytes. `describe`
and `run` require `model_sha256`; every request requires `AZURPILOT_ANDROID_TOKEN`. `status`
returns diagnostics only. The loopback-only service limits headers to 16 KiB, payloads to
64 MiB, and connections to four. Clients release each connection, retry network interruption
once, and report failures explicitly if the service cannot be used, without original weights.

### In-app tests

See [ADB diagnostics](adb-debugging.md) for headless status, model tests, AP integration, and log export.

Open **Settings → OCR acceleration** to inspect the chip, library readiness, and each
bundled model's last backend, AP task count, test count, and CPU fallback reason. Opening
the page does not initialize inference sessions; unused models display "Not run yet".
Automatic refresh stops when the page is no longer visible.
Libraries display "Bundled"; acceleration status separately reports untested, successful
calls, or CPU recovery after failure. `npu_libraries_bundled` means installation files exist;
the legacy `npu_libraries_ready` field preserves that meaning for client compatibility.
`npu_state=verified` indicates partition evidence and successful software inference, without
proving game accuracy or hardware profiling. `user_disabled` indicates the user's CPU choice.

**Run test** sends the selected recognizer through the authenticated loopback API using
`ocr/test/sample.png`, a project-generated 320×48 white image containing "12345". First-run
time includes initialization. Steady and isolated LiteRT CPU measurements each use three
warmups and twenty timed runs with raw samples. CPU requests only CPU without a vendor
provider and checks that no NPU dispatch partitions were compiled; business sessions are separate.
Both measure fixed-shape model inference, tensor copies, and finite checks; they exclude AP
image processing, transport, and decoding. The test compares shapes, maximum absolute error, and per-timestep character
classes for reference without changing the backend. A successful CPU test does not claim NPU use.
Business calls do not run extra CPU comparisons; actual game images still need quality testing.

When AP is running, the same button also launches an AP Python child via PRoot with the
normal injected environment and calls `module.ocr.al_ocr._create_ocr`. This checks existing
RapidOCR preprocessing, host inference, and CTC decoding. Changed weights, an unpatched
session factory, or silent original-Python fallback fail the integration test. Sample text
accuracy is shown separately; integration success does not prove game-image accuracy.
This explicitly selects the chosen ONNX recognizer without changing instance settings.
Android NCNN selections route to the host; unknown versions fail explicitly. With AP
stopped, bundled-model tests remain available and the page asks users to start AP and retest.

`ocr-ap-config-test` additionally reads saved instance settings and calls normal `AlOcr.ocr`
without forcing ONNX/model choices or starting game tasks. The UI separately displays the last
AP business backend and input shape; diagnostics do not overwrite `last_ap_call`. Without
business counts, integration/configuration tests are not evidence of live game calls.

**Copy test report** includes the chip, runtime versions, actual backends, request counts,
timings, numerical comparisons, and AP recognition results, without authentication tokens.
Diagnostic calls do not increase AP task counts. Business counts cover host requests in
this OCR worker process rather than cumulative task history.

### Build and verification

Software acceptance on 2026-10-08 passed Kotlin compilation, debug/release assembly, R8
keep checks, 15 protocol/configuration tests, 17 packaging-gate tests, and string consistency checks.
A Linux smoke check also extracted and executed the unchanged `RecOnlyOCR`, `OcrSettings`,
and recognizer factory from the pinned AP source, with real RapidOCR 3.9.0 and an ONNX CPU
substitute for the host protocol. All four recognizers decoded "12345" correctly. This
dependency-isolated check did not run the full PRoot runtime or Android vendor drivers;
the in-app test covers the complete device integration.

Core LiteRT and JIT plugins are pinned together to
[v2.1.0rc1](https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.0rc1), a prerelease whose
public JIT archive contains both Qualcomm and MediaTek plugins. Core upgrades must update
or rebuild both plugins; mixing a 2.2 core with older plugins is unsupported. QNN is pinned
to 2.40.0. The downloader verifies source hashes, extracts allowlisted files without executing
archive scripts, and bundles SDK license texts.

HiAI clients are pinned to HiAIDemo commit `220e2070f1fad9005d6ed06d33520e559cdac7ec`;
MNN sources use `024a946b0b8fcf87c8a418229fadd4cd7858ffba`. The downloader extracts five
ARM64 NPU clients, matching headers, and licenses. NDK builds MNN with its HiAI plugin.
Four unused legacy ABI dispatch entries absent from this SDK are removed for ARM64;
the linker still rejects missing symbols referenced by compiled code. `hiai-runtime.json`
records source provenance, modifications, and library hashes.

Run `fetch_ocr_runtime.py`, packaging tests, the NumPy-dependent protocol tests, the preferred
Kotlin compile check, slim debug assembly, and `verify_ocr_assets.py` as shown above.
Only converted models are committed, so ordinary builds need no converter. Developer source
references live in ignored `.tmp/ocr-sources/`, using paths below AP's `bin/ocr_models/` and
verified source hashes from the manifest.
For regeneration, use Linux / Python 3.12 and the pinned `ocr-models-requirements.txt` environment
shown in the Chinese section, then run both preparation scripts and their `--verify-only` checks.
Run `test_ocr_model_ranks.py` in the same environment for normalization regression coverage.
Model format 2 uses `prepare_ocr_cpu_models.py` to regenerate matching byte descriptors and
model hashes. `--verify-only` repeats multi-shape source ONNX comparisons.

All four recognizers passed CPU comparison on blank, black, and seeded random tensors. Maximum
absolute error was about `4.6e-5` for LiteRT and `0.0058` for MNN, with identical tested
character predictions at every timestep. MNN uses FP32 high precision; fused operators have
different rounding, so model conversion checks use a separate tolerance of `0.01`. Those inputs
do not represent a full game screenshot corpus. See the device results above for MT6985;
conversion checks and APK assembly alone do not establish real NPU speed.

### Other vendor investigation (2026-10-08)

Samsung's public AI LiteCore 1.2.0 archive provides compiler libraries for Exynos 9955 / 9965.
An isolated Linux environment with `ai-edge-litert==2.2.0` attempted E9965 AOT compilation
of all four existing LiteRT recognizers. Preloading the matching LiteRT core and the SDK's
`libgraphgen.so` resolved missing plugin dependencies. All models then reached vendor
compilation, but none produced usable artifacts. Both AL OCR models reported
`Unsupported shape type UNDEFINED`; the two PP-OCR models returned compilation errors.
This establishes incompatibility of the tested plugin/SDK/model combination, not lack of
NPU hardware support. The SDK license PDF uses NASCA DRM and its contents could not be read;
SDK libraries have not been added to the APK. Consult the
[official LiteRT Samsung guide](https://developers.google.com/edge/litert/next/samsung)
for development requirements and supported chips.

The official `ai-edge-litert-sdk-google-tensor==2.2.0` installer has no default public SDK
download URL. It requires a local `GOOGLE_TENSOR_SDK_BETA` archive or a
`GOOGLE_TENSOR_ML_SDK_URL`. The Python frontend and compiler plugin alone cannot produce
target NPU models, so the beta SDK is still required. Likewise, Xring's XNN headers do not
replace its runtime libraries and model converter.
