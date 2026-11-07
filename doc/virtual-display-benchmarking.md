# Android 虚拟屏性能基准测试与诊断 / Android Virtual Display Benchmarking and Diagnostics

> 本文说明 AzurPilot for Android 虚拟屏性能基准测试体系、核心性能指标与诊断工具的使用方法。
> This document describes the Android virtual display benchmarking architecture, core performance metrics, and usage of diagnostic tools for AzurPilot for Android.

## 中文

### 背景与架构体系

AzurPilot for Android 采用双层分离架构：
1. **App Shell 与特权服务层**：基于 Shizuku 或 root 启动的特权进程管理 1280×720 的无头虚拟显示器（VirtualDisplay），并通过 C++ Bridge 及本地套接字（`127.0.0.1:22301`）提供屏幕捕获与触控注入服务。
2. **PRoot Runtime 与 AzurPilot 核心**：在 Ubuntu 24.04 运行环境中执行上游 Python 自动化，通过 `azurpilot_android` 后端直连特权桥。

在过去，AzurPilot 的内置基准测试模块（`module/daemon/benchmark.py`）针对传统模拟器和物理机设计，硬编码了 `ADB` 截图重置并强制卸载 minicap，导致在 Android 虚拟屏环境下执行基准测试时发生 `RequestHumanTakeover` 异常。

现已全面重构基准测试体系，原生支持 `azurpilot_android` 后端，并构建了专项虚拟屏评测指标体系与独立工具。

---

### 核心评测指标与架构说明

| 指标项 | 典型实测值 | 性能评级 | 架构说明与诊断意义 |
|---|---|---|---|
| **Bridge Ping RTT** | `1.5 ~ 4.5 ms` | `Ultra Fast` / `Insane Fast` | 客户端与特权桥之间的协议往返耗时（行分隔 JSON）。反映本地回环通信健康度与 CPU 调度延迟。 |
| **Screencap Latency** | `25 ~ 35 ms` (机上)<br>`70 ~ 85 ms` (ADB转) | `Ultra Fast` / `Very Fast` | 单帧 1280×720 全像素抓取、传输与 RGB ndarray 转换耗时（单帧数据量 2,764,800 字节，约 2.64 MB）。 |
| **Saturated FPS** | `30 ~ 40 FPS` (机上)<br>`12 ~ 14 FPS` (ADB转) | `Ultra Fast` / `Fast` | 无间歇连续捕获时的极限吞吐帧率，代表虚拟屏图像管道的最大吞吐承载上限。 |
| **Loopback Bandwidth** | `80 ~ 110 MB/s` (机上)<br>`30 ~ 35 MB/s` (ADB转) | `Ultra High` / `Very High` | **100% 运行于 Linux 内核套接字 RAM 缓冲区，绝对零物理闪存读写，不磨损机身存储。** 计算公式为 $\text{FPS} \times 2.6367\text{ MB/s}$。 |
| **Click Injection** | `55 ~ 60 ms` | `Ultra Fast` / `Fast` | 触控点击注入总耗时。包含特权桥标称 `50 ms` 的按住保持时间（`CLICK_HOLD_MS`，防游戏漏触），协议净网络开销仅约 `5 ~ 10 ms`。 |
| **Swipe Injection** | `58 ~ 65 ms` | `Ultra Fast` / `Fast` | 手势滑动注入总耗时。包含标称 `50 ms` 的手势路径持续时间，协议净网络开销仅约 `8 ~ 15 ms`。 |
| **Surface Freshness** | `活跃 (+N 帧)` | `Healthy` | 读取底层 ImageReader 递增的 `frames` 帧序号。如两帧间序号增加，表明 GPU 与游戏画面正活跃渲染；若序号恒定，表明画面静止或进程遭系统 ROM 冻结。 |

---

### 测试工具与使用方法

#### 1. AzurPilot 内部基准测试（WebUI 任务）
- **路径**：WebUI 左侧菜单 -> **常用功能 (Tools)** -> **基准测试 (Benchmark)**。
- **行为**：自动识别 `azurpilot_android` 环境，执行单帧截图评测、触控注入评测，并随后自动运行 `benchmark_virtual_display()` 输出全套 Rich 表格报告。

#### 2. 独立专项评测工具 (`tools/benchmark_virtual_display.py`)

##### 直连模式 (Direct Socket Mode，推荐)
直接通过端口与特权桥通信，轻量快速，无需加载 AP 庞大依赖：
```bash
# 本机 PRoot 内部或 PC 经 ADB 转发运行 (默认 15 轮测试)
python tools/benchmark_virtual_display.py

# 指定高压压力测试 (持续抓取 100 帧)
python tools/benchmark_virtual_display.py --stress 100

# 导出 JSON 评测报告
python tools/benchmark_virtual_display.py --export output/vd_benchmark.json
```

##### AP 协议栈集成模式 (AzurPilot Stack Mode)
通过 AzurPilot 的 Device 与 Daemon 体系进行端到端运行：
```bash
python tools/benchmark_virtual_display.py --mode alas --rounds 15
```

#### 3. 截图后端压力测试工具 (`dev_tools/emulator_test.py`)
在 AzurPilot 源码根目录下运行：
```bash
python dev_tools/emulator_test.py
```
可直接配置 `SERIAL = 'azurpilot_android'` 与 `DEVICE_SCREENSHOT_METHOD = 'azurpilot_android'`，实时输出每帧延迟、标准差、实时帧率与传输带宽。

---

## English

### Background and Architecture

AzurPilot for Android adopts a decoupled two-layer architecture:
1. **App Shell and Privileged Services**: A privileged process started via Shizuku or root manages a headless 1280×720 VirtualDisplay, exposing screen capture and touch injection services via C++ native bridges and a local socket (`127.0.0.1:22301`).
2. **PRoot Runtime and AzurPilot Core**: Upstream Python automation executes inside Ubuntu 24.04 PRoot, directly interacting with the privileged bridge using the `azurpilot_android` backend.

Previously, AzurPilot's built-in benchmark module (`module/daemon/benchmark.py`) was designed for emulators and physical devices, hardcoding `ADB` overrides and invoking `uninstall_minicap()`, which crashed with `RequestHumanTakeover` on Android virtual display environments.

The benchmark system has now been thoroughly revamped to natively support `azurpilot_android`, introducing a dedicated performance metric suite and standalone diagnostic utilities.

---

### Core Performance Metrics

| Metric | Typical Value | Rating | Architectural Notes & Diagnostics |
|---|---|---|---|
| **Bridge Ping RTT** | `1.5 ~ 4.5 ms` | `Ultra Fast` / `Insane Fast` | Protocol round-trip time between client and privileged bridge (line-delimited JSON). Reflects loopback IPC health and scheduling jitter. |
| **Screencap Latency** | `25 ~ 35 ms` (on-device)<br>`70 ~ 85 ms` (via ADB) | `Ultra Fast` / `Very Fast` | Single-frame 1280×720 capture, socket read, and RGB ndarray conversion time (2,764,800 bytes per frame, ~2.64 MB). |
| **Saturated FPS** | `30 ~ 40 FPS` (on-device)<br>`12 ~ 14 FPS` (via ADB) | `Ultra Fast` / `Fast` | Maximum continuous capture frame rate without wait intervals, representing the upper bound of the image pipeline. |
| **Loopback Bandwidth** | `80 ~ 110 MB/s` (on-device)<br>`30 ~ 35 MB/s` (via ADB) | `Ultra High` / `Very High` | **100% transmitted in Linux kernel socket RAM buffers with ZERO flash storage I/O or NAND flash wear.** Calculated as $\text{FPS} \times 2.6367\text{ MB/s}$. |
| **Click Injection** | `55 ~ 60 ms` | `Ultra Fast` / `Fast` | Total click injection latency. Includes bridge-enforced `50 ms` hold time (`CLICK_HOLD_MS` to prevent missed taps); net wire overhead is only `~5 to 10 ms`. |
| **Swipe Injection** | `58 ~ 65 ms` | `Ultra Fast` / `Fast` | Total gesture injection latency. Includes nominal `50 ms` stroke duration; net wire overhead is only `~8 to 15 ms`. |
| **Surface Freshness** | `Active (+N frames)` | `Healthy` | Tracks the monotonic `frames` counter from the underlying ImageReader. Increments indicate active rendering; static values indicate frozen frames or ROM battery saver throttling. |

---

### Usage and Tooling

#### 1. In-App Benchmark Task (WebUI)
- **Path**: WebUI -> **Tools** -> **Benchmark**.
- **Behavior**: Auto-detects `azurpilot_android`, benchmarks capture and touch, and executes `benchmark_virtual_display()` to print a Rich table report.

#### 2. Standalone Benchmark Suite (`tools/benchmark_virtual_display.py`)

##### Direct Socket Mode (Recommended)
Connects directly to the bridge socket without loading full AP dependencies:
```bash
# Inside on-device PRoot or on PC with forwarded port (default 15 rounds)
python tools/benchmark_virtual_display.py

# High-pressure stress testing (100 frames continuously)
python tools/benchmark_virtual_display.py --stress 100

# Export JSON benchmark report
python tools/benchmark_virtual_display.py --export output/vd_benchmark.json
```

##### AzurPilot Stack Mode
Runs through AzurPilot's complete Device and Daemon pipeline:
```bash
python tools/benchmark_virtual_display.py --mode alas --rounds 15
```

#### 3. Stress Test Utility (`dev_tools/emulator_test.py`)
Run from the AzurPilot repository root:
```bash
python dev_tools/emulator_test.py
```
Configure `SERIAL = 'azurpilot_android'` and `DEVICE_SCREENSHOT_METHOD = 'azurpilot_android'` to see real-time latency, standard deviation, FPS, and bandwidth.
