# 发布通道 / Release channel

> 本文说明固定 Release（`azurpilot-android-latest`）的 `latest.json` 字段、镜像源行为与版本号规则。
> This document covers the fixed release (`azurpilot-android-latest`): the `latest.json`
> fields, mirror behavior, and version-number rules.

## 中文

### `latest.json` 字段参考

| 字段 | 类型 | 说明 |
|---|---|---|
| `versionCode` / `versionName` | int / string | App 更新用；规则见下节 |
| `appCommit` | sha | 上次真正构建 APK 的本仓提交 |
| `azurpilotCommit` | sha | Runtime 所用上游 AzurPilot 提交 |
| `androidHostCommit` | sha | 构建该 Runtime 时的本仓提交 |
| `apkUrl` / `apkSha256` / `apkSize` | — | 轻量更新包（双 ABI、无 rootfs） |
| `updateApks.<abi>.url/sha256/size` | object | 优先使用的按架构轻量更新包（无 rootfs） |
| `runtimes.<abi>.version/url/sha256/size` | object | 按架构的 Runtime 包；`abi ∈ {arm64-v8a, x86_64}` |
| `fullApks.<abi>.url/sha256/size` | object | 按架构的完整版 APK（内置对应 rootfs） |
| `rootfsVersion` / `rootfsUrl` / `rootfsSha256` / `rootfsSize` | — | 旧版兼容字段，恒指向 arm64 包 |

兼容规则：旧版 App 只读扁平 `rootfs*` 字段，因此它们必须始终有效——发布脚本将其固定指向 arm64 条目。
APK 和 Runtime 的附件名包含内容 SHA-256 的前 16 位。GitHub 与 CNB 在完整上传后更新
固定的 `latest.json`，同步中断时旧清单仍可使用其原有附件。
新版 App 按设备 ABI 选择 `updateApks`，防止 MuMu 把通用包切成 ARM 转译。
旧清单缺少该字段时，x86_64 优先使用专用完整 APK，ARM64 沿用通用更新包。

### 版本号规则（CI `resolve` 步）

1. 读取上一份已发布 `latest.json` 的 `versionCode`/`versionName`/`appCommit`。
2. GitHub CI 与本地构建按 `1.3.<X>` 计算候选 `versionName`，其中
   `X = max(提交计数 − 303, 0)`；第 303 个提交为 `1.3.0`。候选 `versionCode` 是 HEAD 提交时间戳。
   CNB 直接分发 GitHub 产物，保留同一套版本名与版本号。
3. 若 `appCommit` 可达且为本仓祖先，并且 `git diff --quiet "$app_commit" HEAD -- app` 成功，则 Android 工程输入未变：沿用已发布的 app 版本号，并可仅更新 Runtime。
4. 若 `app/` 有变更且已有可用的上次版本，则 `versionCode = max(旧值 + 1, HEAD 时间戳)`，
   `versionName` 采用第 2 步的候选值，`appCommit` 更新为当前提交。上次版本可来自 `1.1.*`、`1.2.*`
   或 `1.3.*`，升级到新版本线时仍保持 `versionCode` 递增。
5. 没有可比较的已发布版本时，使用第 2 步的候选值并记录当前提交。

构建是否执行是另一项判断：CI 对 `app`、`rootfs` 和 `.github/workflows/rootfs.yml` 相对 `androidHostCommit` 的变化，以及上游 AzurPilot 提交进行比较。不要把“未变更 app 版本”解释为“不需要重建 Runtime 或 APK”。

> 本地构建的 `versionCode` 取 HEAD 时间戳，可能与 CI 的已发布版本号交错。adb 安装 CI 包遇 `INSTALL_FAILED_VERSION_DOWNGRADE` 时加 `-d`。

### App 更新进度

App 更新弹窗显示下载百分比、已下载大小和清单中的总大小。下载完成后切换为校验提示和
不定进度条；缓存安装包也重新校验。下载失败后可重试，进度从零重新开始。
下载和校验期间，返回键或点击弹窗外侧不会隐藏进度。
进度条用 200 ms 线性过渡连接相邻采样值，重试归零与完成时立即同步；百分比和大小显示真实值。

APK 和 Runtime 共用下载器：至少 8 MiB 的文件在服务器通过首字节探测后采用四连接分段。
分段不兼容或失败时，进度归零并自动重试一次单连接下载。每次下载完成后仍校验大小和 SHA-256。

### 国内源与镜像源

GitHub 与 CNB 均维护固定标签 `azurpilot-android-latest`。`ReleaseUrls.CNB_BASE` 指向
CNB 国内下载，设置值为 `cnb`，新安装默认使用该源；已有保存的源选择不变。
App、双架构 Runtime 和前端归档共用下载源，大小与 SHA-256 与 GitHub 发布一致。
清单中的 GitHub/CNB 附件地址按当前选择转换，换源不要求重新检查版本。

`update/ReleaseUrls.kt`：`BASE` 为固定 Release 地址，镜像为 ghproxy 形态
（`前缀 + 完整 GitHub URL`）。内置五个：

| 前缀 | 实测行为（2026-09） |
|---|---|
| `https://ghproxy.net/` | 稳定但慢（4 并发聚合约 1 MB/s） |
| `https://gh-proxy.com/` | 约 1/6 概率无视 Range 回 200 全文件——对单连接无影响，多连接有风险 |
| `https://ghfast.top/` | 稳定，中等速度 |
| `https://gh.ddlc.top/` | 最快且并发稳定（本轮实测首选） |
| `https://gh-proxy.net/` | 对无 JS 引擎的下载器返回挑战页（HTML），**不可用于下载**，仅存留于列表待观察 |

用户可在设置中选择内置镜像或填自定义前缀（同样要求 ghproxy 形态，
`ReleaseUrls.normalizeCustom` 做规范化，非法输入回落直连）。
设置存储：`github_mirror`（`cnb` / `direct` / 镜像前缀 / `custom`）与
`github_mirror_custom`；旧版布尔开关 `use_github_mirror=true` 自动迁移为 ghproxy.net。

### 签名

- 正式包用 Release keystore（GitHub Secrets），v1+v2+v3 全开；CI 发布前用
  apksigner 按最低 SDK 分档逐一验证。
- 无签名材料时 CI 出 debug 包（仓内固定调试密钥，保证签名一致），
  只留构建产物、不发布。

## English

### `latest.json` field reference

| Field | Type | Meaning |
|---|---|---|
| `versionCode` / `versionName` | int / string | For app updates; rules in the next section |
| `appCommit` | sha | Repository commit the last published APK was built from |
| `azurpilotCommit` | sha | Upstream AzurPilot commit inside the Runtime |
| `androidHostCommit` | sha | Repository commit the Runtime was built from |
| `apkUrl` / `apkSha256` / `apkSize` | — | Slim update APK (both ABIs, no rootfs) |
| `updateApks.<abi>.url/sha256/size` | object | Preferred per-ABI slim update APK (no rootfs) |
| `runtimes.<abi>.version/url/sha256/size` | object | Per-ABI Runtime package; `abi ∈ {arm64-v8a, x86_64}` |
| `fullApks.<abi>.url/sha256/size` | object | Per-ABI full APK (bundles the matching rootfs) |
| `rootfsVersion` / `rootfsUrl` / `rootfsSha256` / `rootfsSize` | — | Legacy fields, always pointing at the arm64 package |

Compatibility rule: old app versions read only the flat `rootfs*` fields, so
they must stay valid — the publish step pins them to the arm64 entry.
APK and Runtime asset names include the first 16 characters of their SHA-256 checksum. GitHub
and CNB update the fixed `latest.json` after uploading the complete set, so the previous index
can still use its original payloads if a sync is interrupted.
New apps select `updateApks` by device ABI to prevent MuMu from switching universal APKs to ARM
translation. With older manifests, x86_64 prefers the dedicated full APK and ARM64 uses the
universal update APK.

### Version rules (CI `resolve` step)

1. Read `versionCode`, `versionName`, and `appCommit` from the last published `latest.json`.
2. GitHub CI and local builds calculate a candidate `versionName` as `1.3.<X>`, where
   `X = max(commit count − 303, 0)`; commit 303 is `1.3.0`. The candidate `versionCode` is the
   HEAD commit timestamp. CNB distributes the GitHub artifacts with the same version name and code.
3. If `appCommit` is reachable, is an ancestor of HEAD, and `git diff --quiet "$app_commit" HEAD -- app` succeeds, Android-project inputs are unchanged: retain the published app version and optionally publish a Runtime-only update.
4. If `app/` changed and a comparable previous version exists, set
   `versionCode = max(previous + 1, HEAD timestamp)`, use the candidate `versionName` from step 2,
   and update `appCommit` to the current commit. Previous versions may belong to `1.1.*`, `1.2.*`,
   or `1.3.*`; moving to the new series still increases `versionCode`.
5. Without a comparable published version, use the candidate values from step 2 and record the current commit.

Whether a build runs is a separate decision. CI compares `app`, `rootfs`, and `.github/workflows/rootfs.yml` against `androidHostCommit`, as well as the upstream AzurPilot commit. Do not interpret an unchanged app version as proof that no Runtime or APK rebuild is needed.

> Local builds derive `versionCode` from the HEAD timestamp, which can interleave with CI-published version codes. Add `-d` to `adb install` when you hit `INSTALL_FAILED_VERSION_DOWNGRADE`.

### App update progress

The app-update dialog shows download percentage, downloaded size, and the manifest's total size.
After download it switches to a verification message and an indeterminate progress bar; cached
APKs are also reverified. Retrying a failed download starts progress from zero. Back presses and
outside taps do not hide the dialog during download or verification.
The bar interpolates successive samples linearly over 200 ms, snapping on reset and completion;
percentage and byte labels show actual values.

APK and Runtime downloads share the downloader: files of at least 8 MiB use four connections after
a successful one-byte range probe. Incompatible or failed segmented transfers reset progress and
automatically retry once over one connection. Completed downloads still verify size and SHA-256.

### Domestic source and mirrors

GitHub and CNB both maintain `azurpilot-android-latest`. `ReleaseUrls.CNB_BASE` points to CNB
downloads, with the `cnb` settings value used by default for new installs. Saved choices remain.
App, both Runtime architectures, and frontend archives share this source; sizes and SHA-256
match GitHub. GitHub/CNB asset URLs are routed through the current choice without another check.

`update/ReleaseUrls.kt`: `BASE` is the fixed release address; mirrors use the
ghproxy form (`prefix + full GitHub URL`). Five are built in:

| Prefix | Measured behavior (2026-09) |
|---|---|
| `https://ghproxy.net/` | Stable but slow (about 1 MB/s aggregated over 4 connections) |
| `https://gh-proxy.com/` | Ignores `Range` and returns 200 with the full file about 1 in 6 requests — harmless for a single connection, risky for segmented downloads |
| `https://ghfast.top/` | Stable, moderate speed |
| `https://gh.ddlc.top/` | Fastest with stable concurrency (measured first choice) |
| `https://gh-proxy.net/` | Returns a JS challenge page (HTML) to downloaders without a JS engine — **unusable for downloads**; kept in the list for observation only |

Users pick a built-in mirror or enter a custom prefix in Settings (same
ghproxy form; `ReleaseUrls.normalizeCustom` sanitizes it, and invalid input
falls back to direct). Storage: `github_mirror` (`cnb` / `direct` / a mirror prefix /
`custom`) plus `github_mirror_custom`; the legacy boolean
`use_github_mirror=true` migrates to ghproxy.net automatically.

### Signing

- Release builds use the release keystore (GitHub Secrets) with v1+v2+v3
  enabled; CI verifies each APK with apksigner per minimum-SDK bucket before
  publishing.
- Without signing material CI produces debug APKs (the repository's fixed
  debug key keeps signatures consistent) and keeps them as build artifacts
  only, unpublished.
