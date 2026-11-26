# 多架构支持 / Multi-architecture support

> 本文说明当前支持的设备架构、CI 构建矩阵、产物对应关系与 32 位架构不可行的原因。
> This document lists the supported device architectures, the CI build matrix, the
> artifact mapping, and why 32-bit ABIs are not feasible.

## 中文

### 支持矩阵

| Android ABI | Ubuntu 架构 | CI Runner | 状态 |
|---|---|---|---|
| `arm64-v8a` | arm64 | `ubuntu-24.04-arm`（Graviton 原生） | ✅ 完整支持 |
| `x86_64` | amd64 | `ubuntu-24.04`（原生） | ✅ 完整支持 |
| `armeabi-v7a` | armhf | — | ❌ 不可行，见下 |
| `x86`（i686） | i386 | — | ❌ 不可行，见下 |

proot 不做指令翻译，rootfs 架构与设备 ABI 必须一一对应。
`provision/RuntimeArch.kt` 的 `SUPPORTED` 列表是唯一裁决点。

### 产物对应关系

- CI `build` job 按 `strategy.matrix` 并行构建两份 rootfs，产物名
  `rootfs-<abi>`；`BUILD_MANIFEST` 写入 `rootfs_arch`。
- `apk` job 构建五个包：
  1. per-arch 完整 APK：`-Pazurpilot.releaseAbi=<abi>` 收窄 ABI，
     Gradle 的 `verifyBundledAzurPilotRuntime` 校验内置 rootfs 的
     `rootfs_arch` 与之一致；
  2. 按架构轻量包：同样收窄 ABI，不内置 rootfs，供新版 App 更新；
  3. 通用轻量包：双 ABI 的 proot 库，保留旧版更新兼容；
  4. 发布附件在 `full` 或 `update` 后附加内容哈希，例如
     `AzurPilot-Android-<ver>-<abi>-full-<hash>.apk`。
- App 侧 `RuntimeArch.deviceAbi()` 取 `Build.SUPPORTED_ABIS` 中第一个受支持
  ABI；内置包架构不符时自动改走 Release 下载对应架构。
  MuMu 可能将通用 APK 优先安装为 ARM 转译；x86_64 设备应安装专用包，更新时也按架构选择。

### 32 位架构为何不可行

- **依赖缺失**：上游锁定的核心依赖均无 armv7/i686 的 Linux wheel——
  onnxruntime（连 sdist 都没有）、numpy、scipy、opencv-python、numba
  （llvmlite 需自编 LLVM）。`uv sync --frozen` 在这些平台上无法解析。
- **Python 缺位**：python-build-standalone 无 Linux i686 的 3.14 构建，
  而上游 `requires-python` 钉在 `>=3.14.6`。
- **系统底座缺位**：ubuntu-base 24.04 不提供 i386 包。
- GitHub Actions 机制本身可以跑 armhf（qemu-user-static + binfmt），
  但依赖层无法满足，强行构建只会产出缺 OCR 能力的废包。

### 新增架构的步骤

1. `provision/RuntimeArch.kt` 的 `SUPPORTED` 增加条目。
2. `rootfs/build/build-azurpilot.sh` 的 case 表增加 ABI → Ubuntu 架构 →
   宿主架构映射。
3. `.github/workflows/rootfs.yml` 的 `build.matrix.include` 增加一行
   （runner 必须为目标架构原生）。
4. 确认该平台的全量 Python wheel 可解析（先跑一次 `uv sync --dry-run`）。

## English

### Support matrix

| Android ABI | Ubuntu arch | CI runner | Status |
|---|---|---|---|
| `arm64-v8a` | arm64 | `ubuntu-24.04-arm` (native Graviton) | ✅ Fully supported |
| `x86_64` | amd64 | `ubuntu-24.04` (native) | ✅ Fully supported |
| `armeabi-v7a` | armhf | — | ❌ Not feasible; see below |
| `x86` (i686) | i386 | — | ❌ Not feasible; see below |

proot performs no instruction translation, so the rootfs architecture must
match the device ABI exactly. The `SUPPORTED` list in
`provision/RuntimeArch.kt` is the single decision point.

### Artifact mapping

- The CI `build` job builds two rootfs images in parallel through
  `strategy.matrix`, with artifacts named `rootfs-<abi>`; `BUILD_MANIFEST`
  records `rootfs_arch`.
- The `apk` job builds five packages:
  1. Per-arch full APKs: `-Pazurpilot.releaseAbi=<abi>` narrows the ABI, and
     the Gradle `verifyBundledAzurPilotRuntime` task checks that the bundled
     rootfs `rootfs_arch` matches;
  2. Per-arch slim APKs: the same ABI filter, no rootfs, used by newer app updaters;
  3. A universal slim APK: proot libraries for both ABIs, retained for older updaters;
  4. Release asset names append a content hash after `full` or `update`, as in
     `AzurPilot-Android-<ver>-<abi>-full-<hash>.apk`.
- On the app side, `RuntimeArch.deviceAbi()` picks the first supported entry
  in `Build.SUPPORTED_ABIS`; when a bundled rootfs has a different arch, the
  app falls back to the release download for the matching ABI.
  MuMu may install a universal APK in ARM translation mode. x86_64 devices should use dedicated
  APKs, including architecture-specific updates.

### Why 32-bit ABIs are not feasible

- **Missing dependencies**: the upstream-pinned core dependencies publish no
  armv7/i686 Linux wheels — onnxruntime (not even an sdist), numpy, scipy,
  opencv-python, and numba (llvmlite would require building LLVM).
  `uv sync --frozen` cannot resolve on those platforms.
- **Missing Python**: python-build-standalone publishes no Linux i686 build
  of 3.14, while upstream pins `requires-python` to `>=3.14.6`.
- **Missing base image**: ubuntu-base 24.04 ships no i386 tarball.
- GitHub Actions itself can run armhf (qemu-user-static + binfmt), but the
  dependency layer cannot be satisfied; forcing a build would only produce a
  package without OCR capability.

### Adding a new architecture

1. Add the entry to `SUPPORTED` in `provision/RuntimeArch.kt`.
2. Extend the case table in `rootfs/build/build-azurpilot.sh`
   (ABI → Ubuntu arch → host arch).
3. Add a line to `build.matrix.include` in
   `.github/workflows/rootfs.yml` (the runner must be native to the target
   architecture).
4. Confirm the full Python wheel set resolves on that platform
   (run `uv sync --dry-run` first).
