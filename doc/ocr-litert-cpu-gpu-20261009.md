# 同权重 CPU 与 GPU Softmax / Shared-weight CPU and GPU Softmax

## 中文

### 发布行为

模型格式 2 的 APK 不含原始 ONNX 权重，也不再包含 Android ONNX Runtime。
五个识别器和一个检测器各有一份 LiteRT FP32 转换，CPU 使用同一份转换权重。
海思后端仍保留四份 MNN 转换，因为 HiAI 接口需要该格式；它们不是原始 ONNX。

硬件加速开关默认开启。既有四个识别器的固定宽度请求尝试厂商 NPU，其中两个大字典
识别器的外置末尾 Softmax 使用 OpenGL ES 3.1 compute。GPU 只处理 logits，没有
OCR 权重。GPU 驱动失败时保留 NPU 主干，末尾改用 CPU 并报告错误。NPU 初始化、编译
或运行失败时整个请求改用 LiteRT CPU。关闭开关后整个请求使用 LiteRT CPU。
分数对照差异不触发回退。

| 模型 | 固定 `[N,3,48,320]` 请求 | 其他有效宽度 |
| --- | --- | --- |
| PP-OCRv6 tiny | 尝试 NPU，Softmax 在模型内 | LiteRT CPU |
| PP-OCRv6 small | 尝试 NPU，LiteRT 大字典尾部使用 GPU Softmax | LiteRT CPU |
| alocr en-US | 尝试 NPU，Softmax 在模型内 | LiteRT CPU |
| alocr zh-CN | 尝试 NPU，LiteRT 大字典尾部使用 GPU Softmax | LiteRT CPU |
| PP-OCRv6 medium（pro） | LiteRT CPU；本次新增转换尚未验证厂商 NPU | LiteRT CPU |
| PP-OCRv6 tiny 检测器 | LiteRT CPU；本次新增转换尚未验证厂商 NPU | LiteRT CPU，空间尺寸按 32 对齐 |

海思的 MNN 图保留模型内 Softmax。当前 NPU 条件与厂商限制见
[OCR 加速](ocr-acceleration.md)。本改动不代表所有厂商或所有模型已通过真机验证。

### 动态尺寸与模型清理

LiteRT NPU 基准图保持静态尺寸。CPU 会话从同一份 LiteRT 文件创建私有临时图视图，仅修改
构建验证过的输入尺寸、reshape、池化规则和输出尺寸字段，不修改训练权重。
识别器保留 AP 实际输入宽度；时间步数为 `floor((W + 3) / 8)`。CPU 池化采用原 ONNX
的 VALID 规则，因此奇数宽度也保持原模型语义。检测器按实际高度和宽度专门化。
批次逐张推理，输出仍使用 AP 期望的布局和尺寸。

2026-10-10 起，所有 Android 版本都通过私有缓存中的真实临时文件加载 CPU 模型，
避免部分 ROM 的 SELinux 拒绝重开 memfd。文件保留到模型关闭，独立名称与文件锁防止
版本或尺寸串用，并在下一次创建时清理已退出会话的残留。详见
[SELinux CPU 修复](ocr-cpu-selinux-fix.md)。
CPU 环境不提供厂商 provider，只请求 CPU，并检查不存在 NPU dispatch 分区。

CI 在原始模型质量检查后删除 AP、NCNN、旧 CnOCR 和 RapidOCR 默认权重，保留语言字典。
六个活动模型的逻辑路径替换为约百字节的 JSON 身份文件，用于 RapidOCR 路径检查和
宿主 SHA-256 白名单匹配。这些以 `.onnx` 结尾的身份文件不含模型权重。
运行旧 rootfs 时，APK 在 AP 启动前进行同样的迁移，并清理宿主旧模型缓存；AP 源码
热更恢复的已知官方权重也会回收。仅删除大小和哈希匹配的已知文件，不删除自定义文件。
上游修改活动权重时先拒绝迁移并要求更新 APK，避免误用旧转换。

新 rootfs 要求模型格式 2 的 APK，升级时先安装本版 APK。轻量测试 APK可直接使用现有
已安装的 rootfs，无需重新下载运行环境。开发缓存和 Git 历史中的原始模型不属于发布包，
仍可用于转换回归验证。

### 验证结果与边界

2026-10-09 的本地验证结果如下。

- Release 构建通过，包括 ARM64、x86_64 native 编译和 R8 接口保留检查。
- 六个 LiteRT 模型通过 136 组空白、随机和多尺寸原 ONNX 对照。识别器覆盖宽度
  `32, 317–324, 328, 480, 640, 1024`，分类选择一致，最大绝对差约 `1.59e-4`。
  另用真实 LiteRT CompiledModel 执行 31 个尺寸案例，校验实际输出缓冲区尺寸和数值。
- AP 实际 OCR 工厂、RapidOCR 预处理、回环接口和 CTC 解码通过五个识别器的
  `12345` 测试；保存的 NCNN 配置转接宿主后，检测加识别流程也通过。
  宿主替身执行本次 LiteRT CPU 模型，禁止原 ONNX 会话构造。
- 生产 GPU 着色器通过 ES 3.2 Mesa 软件渲染测试，包括极端 logits、均匀概率和随机行。
  两个大字典规模的最大绝对差约 `2.14e-9`。App 会拒绝软件渲染器；此测试只验证
  着色器数值，不能证明手机 GPU 可用或更快。
- 包校验拒绝原始 OCR `.onnx` 资产和 Android ONNX Runtime 库；迁移、接口和 JNI
  测试通过。开发机 LiteRT 为 2.1.2，APK 钉版为 2.1.0rc1，Android 驱动需另行验证。

本版制作时 ADB 未连接设备，因此未测新 APK 在 MTK、高通或海思手机上的速度、功耗和
GPU 驱动稳定性。计时包含 GPU 上传、同步、读回和输出检查；不能预先保证 GPU 尾部更快。
NPU dispatch 分区证明存在 NPU 委派，不能单独证明全部算子在 NPU 或硬件性能收益。

已知官方 rootfs 的 22 个原权重文件合计 `365,963,402` 字节，约 349.0 MiB，可在部署和
CI 打包时移除。本版轻量 APK 约 251.81 MiB，比上一轻量测试包大约 11.83 MiB，原因是
新增约 73 MiB 的 pro 转换以保留现有模型档位；APK 同时移除了原权重和 Android ORT。
349.0 MiB 是解压后的权重大小，不是最终 rootfs 压缩包缩减量。完整包尺寸以新 CI 产物为准。
旧瘦身 CI 因宿主 `strip` 不支持 minicap 缓存中的其他 Android 架构失败；补丁按 ELF
位数、字节序和机器类型筛选，保留异架构缓存不改写，并在两种原生 runner 上先运行回归。
动态符号按段名比较完整语义，避免只比较文本段号；GNU strip 改坏 Pillow 的局部段符号
标注时拒绝该候选并保留原库，报告跳过数量，不把未通过校验的副本写回镜像。

### 真机检查

```bash
python tools/adb-debug.py ocr-status
python tools/adb-debug.py ocr-test-gpu-softmax zh --output gpu-softmax.json
python tools/adb-debug.py ocr-test-all --output ocr-tests.json
python tools/adb-debug.py ocr-hardware-acceleration off
python tools/adb-debug.py ocr-test-cpu tiny --output cpu-tiny.json
python tools/adb-debug.py runtime-start
python tools/adb-debug.py ocr-ap-config-test --output ap-config.json
python tools/adb-debug.py ocr-hardware-acceleration on
```

业务记录的 `last_ap_call.backend`、`terminal_softmax_backend`、`gpu_renderer` 和
`ap_requests` 用于检查真实 AP 调用。单测成功只证明该诊断请求，不代替业务记录。
驱动错误和 CPU 原因分别报告；用户仍可用界面开关选择 CPU。

## English

### Shipped behavior

Model format 2 APKs contain no source ONNX weights or Android ONNX Runtime. Each of the
five recognizers and one detector has a LiteRT FP32 conversion shared with CPU inference.
HiAI still needs the four MNN conversions; these are converted models, not original ONNX.

Hardware acceleration defaults to on. The four existing recognizers attempt vendor NPU
execution at their fixed width. Two of those large-dictionary recognizers use OpenGL ES 3.1 compute
for external terminal Softmax. GPU receives logits and contains no OCR weights. GPU driver
errors keep the NPU backbone and switch the tail to CPU with a reported reason. NPU loading,
compilation, or execution errors switch the entire request to LiteRT CPU. Disabling hardware
acceleration runs the entire request on LiteRT CPU. Score differences never trigger fallback.

| Model | Fixed `[N,3,48,320]` request | Other valid widths |
| --- | --- | --- |
| PP-OCRv6 tiny | Attempts NPU; Softmax stays in the model | LiteRT CPU |
| PP-OCRv6 small | Attempts NPU; LiteRT large-dictionary tail uses GPU Softmax | LiteRT CPU |
| alocr en-US | Attempts NPU; Softmax stays in the model | LiteRT CPU |
| alocr zh-CN | Attempts NPU; LiteRT large-dictionary tail uses GPU Softmax | LiteRT CPU |
| PP-OCRv6 medium (pro) | LiteRT CPU; this new conversion lacks vendor NPU validation | LiteRT CPU |
| PP-OCRv6 tiny detector | LiteRT CPU; this new conversion lacks vendor NPU validation | LiteRT CPU with spatial dimensions aligned to 32 |

HiAI MNN graphs retain Softmax internally. See [OCR acceleration](ocr-acceleration.md) for
vendor requirements. This change does not establish hardware support for every vendor or model.

### Dynamic dimensions and weight retirement

The LiteRT NPU baseline stays static. CPU sessions create a private temporary graph view from the
same LiteRT file, changing only build-verified input dimensions, reshape controls, pooling
rules, and output annotations. Trained weights remain intact. Recognizers preserve AP input
width, with `floor((W + 3) / 8)` time steps. CPU pooling follows source ONNX VALID semantics,
including odd widths. Detection specializes both spatial dimensions. Batches run one sample
at a time and return AP's expected layout and dimensions.

Starting on 2026-10-10, all Android versions load CPU models through real private-cache files
to avoid SELinux denials when reopening memfd on some ROMs. Files stay until the model closes;
unique names and file locks prevent mixing versions or shapes. The next creation reclaims
files from terminated sessions. See [the SELinux CPU fix](ocr-cpu-selinux-fix.md).
CPU environments omit vendor providers, request CPU only,
and check for zero NPU dispatch partitions.

After the source-model quality gate, CI removes AP, NCNN, legacy CnOCR, and RapidOCR default
weights while retaining dictionaries. Six active logical paths become approximately 100-byte
JSON identity descriptors for RapidOCR path checks and the host SHA-256 allowlist. Their
`.onnx` filenames contain no weights. The APK migrates existing rootfs installations before
AP starts, prunes old host model caches, and reclaims known official weights restored by AP
source updates. Size/hash checks retain custom files. Changed active upstream weights fail
before migration and require an APK update to avoid stale conversions.

The new rootfs requires a model format 2 APK: install this APK first. The slim test APK can
use an existing installed rootfs without downloading another runtime. Developer caches and
Git history are outside shipped artifacts and remain available for conversion regression tests.

### Validation and limits

Local validation on 2026-10-09 established:

- Release compilation, ARM64/x86_64 native builds, and R8 interface retention passed.
- Six LiteRT models passed 136 blank/random comparisons against source ONNX over multiple
  shapes. Recognition widths cover `32, 317–324, 328, 480, 640, 1024`, with identical class
  choices and maximum absolute difference around `1.59e-4`. Another 31 cases execute real
  LiteRT CompiledModel sessions and check actual output-buffer layouts and values.
- The actual AP OCR factory, RapidOCR preprocessing, loopback protocol, and CTC decoding
  recognized `12345` with all five recognizers. Saved NCNN configuration also passed detection
  plus recognition after host adaptation. The host fixture executes these LiteRT CPU models
  and forbids original ONNX session construction.
- The production shader passed Mesa ES 3.2 software-rendering tests with extreme logits,
  uniform probabilities, and random rows. The two large-dictionary sizes differed by at most
  approximately `2.14e-9`. The app rejects software renderers; this establishes shader
  numerics, not Android GPU availability or performance.
- Packaging rejects source OCR `.onnx` assets and Android ORT libraries. Migration, protocol,
  and JNI tests passed. Developer validation uses LiteRT 2.1.2; the APK pins 2.1.0rc1, so
  Android driver behavior requires separate device testing.

ADB had no connected device when this APK was produced. New Android GPU latency, power,
and driver stability have not been measured on MTK, Qualcomm, or HiAI phones. Timings include
upload, synchronization, readback, and output checks, so a faster GPU tail is not guaranteed.
Dispatch partitions establish NPU delegation, not complete NPU execution or a performance gain.

The 22 known official source-weight files total `365,963,402` bytes, approximately 349.0 MiB,
removable during deployment and CI packaging. The slim APK is approximately 251.81 MiB,
about 11.83 MiB larger than the preceding slim test: a roughly 73 MiB pro conversion moves
into the APK to preserve model choices, while source weights and Android ORT are removed.
349.0 MiB describes unpacked weights, not compressed rootfs savings. Full package size must
be measured from the new CI artifact.
The preceding slimming CI failed when native `strip` encountered other Android architectures
in minicap caches. The fix filters ELF class, byte order, and machine type, retaining foreign
cache files unchanged and running regressions on both native runner architectures first.
Dynamic symbols compare full semantics with named sections rather than raw section numbers.
When GNU strip damages Pillow's local-section symbol annotations, the candidate is rejected,
the original library stays intact, and the report counts the skip. Unverified copies are never
written back. See the [GNU readelf options](https://sourceware.org/binutils/docs/binutils/readelf.html)
for dynamic-symbol and section-header output.

### Device checks

Use the commands in the Chinese section. Inspect business `last_ap_call.backend`,
`terminal_softmax_backend`, `gpu_renderer`, and `ap_requests` to verify actual AP calls.
Standalone diagnostics do not substitute for business records. Driver errors and CPU reasons
are reported separately, and users retain the UI switch for choosing CPU.
