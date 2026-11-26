# GitHub 产物分发到 CNB Releases / Mirroring GitHub artifacts to CNB Releases

## 中文

GitHub Actions 负责构建、签名和校验，CNB Releases 提供国内下载。
两个平台使用相同的 APK、双架构 Runtime 和前端归档字节；CNB 清单只改写下载地址。

- GitHub 仓库：[`wess09/AzurPilot-for-Android`](https://github.com/wess09/AzurPilot-for-Android)。
- CNB 仓库：[`azurpilot/AzurPilot-for-Android`](https://cnb.cool/azurpilot/AzurPilot-for-Android)。
- 国内下载：[CNB Releases](https://cnb.cool/azurpilot/AzurPilot-for-Android/-/releases)。

### 配置访问令牌

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 配置 `CNB_TOKEN`。
仓库已有源码同步用的同名 Secret，可以复用，但令牌必须有目标 CNB 仓库的
`repo-release:rw` 权限。源码同步及发布 tag 更新还需要界面中 `repo-code` 的“读写”权限。
令牌所属账号也必须有该仓库的 Release 写权限。

参考 CNB 官方[访问令牌说明](https://docs.cnb.cool/en/guide/access-token.html)、
[仓库角色权限](https://docs.cnb.cool/en/guide/role-permissions.html)及
[OpenAPI](https://docs.cnb.cool/en/develops/openapi.html)。
只读令牌或只有源码写权限的令牌不能发布 Release；上传步骤会明确报告权限不足。

正式签名和设备上报凭据继续使用 GitHub 上现有的六个 Secrets。
CNB 不需要私密凭据文件或 Android 签名密钥，因为分发步骤不会重新构建、重新签名。
App 版本沿用 GitHub 的 `1.3.*` 版本规则，见[发布通道](release-channel.md)。

### 自动发布

`.github/workflows/rootfs.yml` 的 `publish` 作业在正式 GitHub Release 发布后，
调用 `tools/cnb_release.py publish` 分发当前作业的产物：

- ARM64、x86_64 轻量更新 APK 与完整 APK，以及兼容旧版的通用轻量更新 APK。
- ARM64 和 x86_64 Runtime 归档及各自的 `BUILD_MANIFEST`。
- 构建产物中存在的前端归档与对应校验文件。
- 指向 CNB 附件的 `latest.json`，以及重新生成的 `SHA256SUMS`。

脚本核对 GitHub 清单中的大小、SHA-256、当前主机提交及 Runtime 元数据。
大型文件以流式方式上传；每次重试重新取得预签名地址，上传后通过 API 确认附件。
CNB 访问令牌只发送到目标仓库的 CNB API 和 Git 服务，不发送到附件对象存储。

CNB 与 GitHub 共用唯一固定标签 `azurpilot-android-latest`。发布前先通过 Git 上传目标
主机提交并推进标签，不依赖独立源码同步的顺序，也覆盖 `[skip ci]` 的上游检查提交。
首次发布创建草稿，后续复用同一个 Release；SHA-256 已匹配的附件无需重复上传。
其它附件先上传并验证，`latest.json` 最后提交，再更新 Release 标题和 Latest 标记。

APK 和 Runtime 的文件名含内容哈希。上传中断时，旧清单和它引用的附件仍可下载。
成功后清理旧 CNB Release 和 Git tag，固定发布保留当前附件及每种带哈希附件的最近两份旧文件。
补传仍支持不带哈希的旧发布。清单保留 ARM64 扁平兼容字段。

App 首次安装默认选用“CNB 国内源”；设置页和部署页可切回 GitHub 或代理镜像。
App、Runtime 和前端共用该选择；已有保存的选择不变。

`.github/workflows/sync-cnb.yml` 只同步分支；固定发布标签由产物分发步骤更新。
自动发布与手动补传共用 `cnb-release-publish` 并发组，避免发布期间相互覆盖下载源。

### 手动补传已有产物

在 GitHub **Actions → Publish existing GitHub release to CNB → Run workflow** 执行。
`github_tag` 默认 `azurpilot-android-latest`；该 GitHub Release 必须包含正式签名的
轻量 APK、两个架构的完整 APK、两个 Runtime 和有效的 `latest.json`。

该工作流只下载清单对应的当前版本资产，校验后上传 CNB，不运行 Gradle 或 Runtime 构建。
旧发布只有一份 `BUILD_MANIFEST`、没有双架构元数据附件时也可补传；已有前端归档仍需校验。
GitHub Release 保留的历史版本 APK 不会一起下载。

若自动上传失败，GitHub 的构建和 Release 已完成，可通过此入口重试分发。
出现 `CNB_TOKEN requires repo-release:rw` 时，给令牌增加权限并更新 GitHub Secret，
然后重跑补传工作流。成功补传会自动清理旧发布及标签。

### 停用 CNB 原生构建

`.cnb.yml` 和 `.cnb/rootfs.yml` 为空配置；`.cnb/web_trigger.yml` 没有构建按钮。
原来的每日 03:17 构建、API 触发和手动构建入口均已停用。
旧 `.cnb/` 构建脚本仅保留为历史实现，不由任何活动流水线调用。

本地验证上传协议和失败行为：

```powershell
uv run --no-project python tools/tests/test_cnb_release.py
```

修改 CI 时还需检查 GitHub 工作流的 YAML 和 Bash 语法，及 CNB 的官方
[流水线 schema](https://docs.cnb.cool/conf-schema-en.json)和
[手动按钮 schema](https://docs.cnb.cool/web-trigger-schema-en.json)。
本地模拟测试不能确认线上令牌的实际权限；首次上传结果以 GitHub Actions 为准。

## English

GitHub Actions builds, signs, and verifies artifacts; CNB Releases provides domestic downloads.
Both platforms serve identical APK, dual-architecture Runtime, and frontend archive bytes.
The CNB index changes only their download URLs.

- GitHub repository: [`wess09/AzurPilot-for-Android`](https://github.com/wess09/AzurPilot-for-Android).
- CNB repository: [`azurpilot/AzurPilot-for-Android`](https://cnb.cool/azurpilot/AzurPilot-for-Android).
- Domestic downloads: [CNB Releases](https://cnb.cool/azurpilot/AzurPilot-for-Android/-/releases).

### Configure the access token

Configure `CNB_TOKEN` under the GitHub repository's
**Settings → Secrets and variables → Actions**.
The existing source-mirroring Secret can be reused, provided its token has `repo-release:rw`
for the target CNB repository. Git source sync and release-tag updates need Read/Write
access for `repo-code`. Its account must also have Release write access.

See CNB's official [access-token guide](https://docs.cnb.cool/en/guide/access-token.html),
[repository role permissions](https://docs.cnb.cool/en/guide/role-permissions.html), and
[OpenAPI documentation](https://docs.cnb.cool/en/develops/openapi.html).
Read-only tokens or tokens granting only source write access cannot publish Releases;
the upload step reports permission failures explicitly.

The six existing GitHub Secrets continue to supply signing and device-reporting credentials.
CNB needs no private credential file or Android signing key because distribution does not
rebuild or re-sign artifacts. App versions follow GitHub's `1.3.*` rules; see
[release channels](release-channel.md).

### Automatic publication

After publishing the signed GitHub Release, the `publish` job in
`.github/workflows/rootfs.yml` calls `tools/cnb_release.py publish` with this job's artifacts:

- ARM64 and x86_64 slim updates and full APKs, plus the universal slim update for older apps.
- ARM64 and x86_64 Runtime archives and their `BUILD_MANIFEST` files.
- Frontend archives and checksum files present in the build outputs.
- `latest.json` pointing to CNB assets and a regenerated `SHA256SUMS`.

The script checks sizes, SHA-256 hashes, the current host commit, and Runtime metadata
against the GitHub index. Large files are streamed. Each retry obtains a fresh presigned URL,
and uploaded assets are confirmed through the API. The CNB token is sent only to the target
repository's CNB API and Git service, never to attachment object storage.

CNB and GitHub share the single fixed tag `azurpilot-android-latest`. Before publication,
Git uploads the host commit and advances that tag, independently of source-sync ordering
and `[skip ci]` upstream-check commits. The first run creates a draft; subsequent runs reuse
the same release. Assets matching SHA-256 need no upload. Other assets are uploaded and verified
first, `latest.json` is committed last, and then the title and Latest marker are updated.

APK and Runtime names contain content hashes. If upload is interrupted, the previous index and
its referenced files remain downloadable. After success, the fixed release retains the current
files and the two most recent older hash-named files of each kind. Other CNB releases and Git
tags are cleaned up. Backfills still support older releases without hash-named assets, and the
index retains the legacy ARM64 flat fields.

New installations default to CNB. Settings and provisioning allow switching to GitHub or proxies.
App, Runtime, and frontend downloads share the selection; existing saved choices are retained.

`.github/workflows/sync-cnb.yml` mirrors only branches; artifact publication advances the fixed tag.
Automatic publishing and manual backfills share the `cnb-release-publish` concurrency group,
preventing conflicting publication and changes to the download source during a backfill.

### Backfill existing artifacts manually

Open GitHub **Actions → Publish existing GitHub release to CNB → Run workflow**.
The `github_tag` input defaults to `azurpilot-android-latest`. That GitHub Release must contain
the signed update APK, both full APKs, both Runtimes, and a valid `latest.json`.

The workflow downloads only the current assets referenced by the index, verifies them,
and uploads to CNB without running Gradle or building Runtimes. Older releases with only one
`BUILD_MANIFEST` and no per-architecture metadata attachments are supported. Any existing
frontend archive must still pass checksum verification. Historical APK versions retained
in the GitHub Release are excluded.

If automatic uploading fails, the GitHub build and Release have already completed;
retry distribution through this workflow. For `CNB_TOKEN requires repo-release:rw`, grant
the permission, update the GitHub Secret, and rerun the backfill workflow.
Successful backfills automatically remove old releases and tags.

### Disable CNB native builds

`.cnb.yml` and `.cnb/rootfs.yml` contain empty configurations; `.cnb/web_trigger.yml` has no
build buttons. The former daily 03:17 build, API triggers, and manual build entry points
are disabled. Old `.cnb/` build scripts remain as historical implementations and are not
called by active pipelines.

Validate upload ordering and failure behavior locally:

```powershell
uv run --no-project python tools/tests/test_cnb_release.py
```

CI changes also require GitHub workflow YAML and Bash syntax checks, plus validation against
CNB's official [pipeline schema](https://docs.cnb.cool/conf-schema-en.json) and
[manual-button schema](https://docs.cnb.cool/web-trigger-schema-en.json).
Local simulations cannot confirm the live token's permissions; the first GitHub Actions
upload determines whether they are sufficient.
