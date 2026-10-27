# 机型适配提交 / Device compatibility reporting

## 中文

用户在 **设置 → 提交你的机型** 查看自动检测结果，选择使用结果，并勾选同意公开到
GitHub 后提交。报告追加到 [机型支持列表 Issue #1](https://github.com/wess09/AzurPilot-for-Android/issues/1)
的评论，不创建新 Issue。界面使用 Material 3；成功时弹出确认框，支持直达对应评论。
重复提交会提示已有记录。打开页面只在设备本地采集，发送动作只在确认后执行。

### 数据与去敏

协议第 2 版只接受厂商、品牌、商品名称、型号代码、厂商系统名称/版本、完整构建版本、
构建显示编号、产品/设备代号、硬件和芯片、Android 版本及安全补丁、
ABI、主屏参数、约整 GiB 内存、App 版本、配置的 Shizuku/Root 后端及前台/后台模式、
用户声明的使用结果。配置模式不代表已成功运行。

只接受用户实际使用后的“可正常使用”或“无法正常使用”。页面不预选结果，用户未明确
选择时提交按钮不可用；服务器拒绝空结果及“未测试”，且不写入状态或消耗提交配额。

商品名称优先取只读 `ro.product.marketname` 及其 vendor 变体，缺失时按品牌、型号和
代号精确匹配 CI 打包的 Google 公开机型目录；有歧义或无匹配时保留型号代码。
目录来自 [Google 支持的 Android 设备](https://support.google.com/googleplay/answer/1727131)，
由 `app/scripts/build_device_catalog.py` 下载公开 CSV 并压缩；设备不向第三方查询自身参数。
当前目录约 502 KiB，随 CI 构建更新。未运行目录脚本的本地构建仍可使用系统商品名。

厂商系统通过 `DeviceIdentity` 中的只读属性白名单识别，始终保留
`Build.VERSION.INCREMENTAL` 与 `Build.DISPLAY`，完整构建版本另列。
无法读取的字段保持空值或退回 Android，不读取用户可修改且可能带个人信息的设备名称。

不读取 IMEI、IMSI、序列号、Android ID、MAC、账户、电话服务、完整系统属性、日志或截图。
文本中的长数字、标识符提示、UUID、MAC 和邮箱会替换为去敏标记。服务器再次校验范围、
长度、枚举和去敏；包含额外字段的请求直接拒绝。IP 不写入报告、状态文件或访问日志。

### 认证路径

公网地址为 `https://afa-api.azurpilot.com/v1/device-reports`。
Cloudflare 边缘的 mTLS、反代和 DNS 由管理员配置；Docker 服务只提供本机 HTTP API。
App 按 Android 系统信任链检查服务器，保持正常的主机名验证。

Cloudflare 终止客户端 TLS，因此源站不能从该连接直接取得 App 的客户端证书。
App 使用同一客户端 RSA 私钥对下列 UTF-8 内容进行 SHA256withRSA / PKCS#1 v1.5 签名：

```text
POST\n/v1/device-reports\n<TIMESTAMP>\n<NONCE>\n<RAW_JSON_BYTES>
```

时间戳为 Unix 秒，随机 nonce 为 32 位小写十六进制，签名采用标准 Base64，
分别放入 `X-Report-Timestamp`、`X-Report-Nonce`、`X-Report-Signature`。
服务器用固定客户端证书公钥校验，允许前后 5 分钟时钟误差，拒绝过期证书与进程内 nonce
重放。客户端不自动重试 POST，不跟随重定向；离开页面取消会话时关闭 HTTP 请求。

旧部署的 Cloudflare 源站连接使用 HTTP 80，Nginx 转发到本机 `127.0.0.1:18882`，
每份报告仍必须通过正文签名。独立的源站 443 配置供直接 mTLS 检查使用，通过证书指纹
钉住指定叶证书，并再次执行正文验签。其服务端证书当前为自签证书，不能直接用于
Cloudflare Full (strict)。切换源站加密时，需要可信服务器证书和适配 Cloudflare 源站
连接的配置；Cloudflare 不会转发 App 的客户端证书。

APK 内共享客户端私钥可以被提取，mTLS 和签名只能证明持有该密钥，不能证明设备信息真实
或 App 未经修改。限制正文为 8 KiB、串行转发、Nginx 限流以及每小时 10 个/每天 50 个新
评论的持久配额用于减少刷报风险。该功能不申请新的敏感权限。

### GitHub CI 注入

仓库 Actions Secrets：

- `AZURPILOT_DEVICE_REPORT_CERT_BASE64`：客户端 PEM 证书的 Base64。
- `AZURPILOT_DEVICE_REPORT_KEY_BASE64`：PKCS#8 PEM 私钥的 Base64。

Base64 只是编码，保密由 Actions Secrets 提供。`rootfs.yml` 在构建阶段解码到被 Git
忽略的 `.tmp/report-credentials/`，检查有效期和公私钥匹配，由 Gradle 生成
`assets/device-report/`。CI 禁用任务输出缓存，避免将包含私钥的资产复制到共享构建缓存。
构建后清理临时文件及生成目录。
CI 使用 `verify_device_report_assets.py` 检查所有最终 APK 的压缩目录可读，
以及证书/私钥与注入文件完全一致；本地验证同一脚本时要求 APK 不含提交凭据。
CI 缺少任一 Secret 则构建失败。本地源码编译无需凭据；没有凭据的本地包在提交时显示
不可用，不应作为带提交功能的发布包。

GitHub API Token 只保存在服务器的 `deploy/private/service.env`，权限 0600，
不会进入 APK。目标仓库是 `wess09/AzurPilot-for-Android`，工单编号固定为 1，
仅调用 `POST /repos/{owner}/{repo}/issues/1/comments`。结果包含 64 位评论编号及固定工单下的评论链接。

### 低资源服务与部署

`server/device-report/` 为 Go 标准库实现，静态 Linux 二进制，无独立数据库。
Docker 以非 root 用户运行，设置 48 MiB Go 内存目标、96 MiB 硬限制和单核调度。
状态挂载到容器的 `/var/lib/azurpilot-device-report/state.json`，仅保存报告哈希、创建时间及评论
编号/地址，不保存报告正文。

同一报告（忽略 App 版本）的后续提交返回已有评论。创建前先原子持久化待确认状态；
响应不确定时重试会从 Issue #1 的评论查回匹配的隐藏标记，减少重复评论。
查不到时至少等待 2 分钟再尝试创建。跨系统的写操作没有绝对的 exactly-once 保证；
异常长的 GitHub 可见性延迟仍可能产生重复。状态上限为 10,000 条，到达上限会明确拒绝
新报告，需要管理员归档或扩容。

Docker 部署与迁移步骤见 [Docker 运维说明](../server/device-report/deploy/README.md)。
生产目录为 `/srv/agentdock/AgentDock/afa-api`；反代上游使用 `http://127.0.0.1:18882`。
容器沿用源站的正文签名公钥和去重记录；Cloudflare 的客户端 TLS 证书独立在边缘管理。
源站原有 systemd 安装方式保留如下，供旧部署维护和回滚使用。

交叉编译并上传二进制及公开客户端证书，不上传客户端私钥：

```sh
cd server/device-report
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o device-report .
```

准备好服务器证书、私钥和 `service.env`，其内容为：

```dotenv
GITHUB_TOKEN=<server-only-token>
GITHUB_REPOSITORY=wess09/AzurPilot-for-Android
```

将 `deploy/` 上传后以 root 运行安装脚本，第三个参数必须是当前 Nginx 已包含的
vhost 目录。本服务器使用：

```sh
sh deploy/install.sh ./device-report ./client-cert.pem /.__gmssh/data/plugin/xz/site/data/nginx/conf/vhost
systemctl status azurpilot-device-report
journalctl -u azurpilot-device-report
```

替换二进制采用新文件重命名；Nginx 校验失败会恢复旧 vhost。检查公网无证书请求为 403、
只有证书但无签名为 401、有效签名包含未知字段为 400，真实白名单报告返回 201，
重复报告返回 200。

### 验证

```sh
cd server/device-report
go test ./...
go vet ./...
cd ../../app
./gradlew compileDebugKotlin -x verifyBundledAzurPilotRuntime
./gradlew assembleDebug -Pazurpilot.slimApk=true
cd ..
uv run app/scripts/check_i18n_strings.py
```

## English

Users open **Settings → Submit your device**, review locally detected information, choose a
compatibility result and consent to GitHub publication before sending. The page uses Material 3.
Reports are appended as comments on [device support issue #1](https://github.com/wess09/AzurPilot-for-Android/issues/1).
Success opens a confirmation dialog with a direct comment link; duplicates show the existing record.
Opening the page performs local collection only.

### Data and privacy

Protocol version 2 allowlists manufacturer, brand, marketing name, model code, vendor OS name/version,
full incremental build, build display ID, product/device codenames, hardware/SoC,
Android release and security patch, ABIs, main display parameters, memory rounded to GiB, app
version, configured Shizuku/Root backend, foreground/background mode and user-declared result.
Configured modes do not imply successful operation.

Only explicitly selected working/not-working results after actual use are accepted. No result is
preselected. Submission stays disabled until a choice is made, and the server rejects empty or
untested results without persisting state or consuming submission quota.

Marketing names use read-only market-name properties, then exact brand/model/codename matching
against Google's public supported-device catalog bundled by CI. Ambiguous or unknown names retain
the model code. `app/scripts/build_device_catalog.py` downloads the public CSV and builds a roughly
502 KiB offline asset. Devices never send their details to a third-party lookup service.
Local builds without the catalog still support system marketing-name properties.

Allowlisted read-only properties identify vendor systems. `Build.VERSION.INCREMENTAL` and
`Build.DISPLAY` retain the full build details alongside the normalized system version.
Unavailable values remain empty or fall back to Android. User-editable device names are never read.

It never reads IMEI, IMSI, serial, Android ID, MAC, accounts, telephony, complete system properties,
logs or screenshots. Suspicious long numbers, identifier hints, UUIDs, MACs and email addresses
are redacted. The server repeats validation and redaction, rejecting unknown fields. IPs are not
written to reports, state files or access logs.

### Authentication

The public endpoint is `https://afa-api.azurpilot.com/v1/device-reports`.
The administrator configures Cloudflare edge mTLS, DNS, and the reverse proxy;
the Docker service exposes a loopback HTTP API. Android retains system server trust
and normal hostname verification.

Cloudflare terminates client TLS, so the origin cannot obtain the app certificate from that
connection. The app signs UTF-8 `POST\n/v1/device-reports\n<TIMESTAMP>\n<NONCE>\n<RAW_JSON_BYTES>`
using the same client RSA key with SHA256withRSA / PKCS#1 v1.5. Unix seconds, a random 32-character
lowercase hex nonce and standard Base64 signature are sent in `X-Report-Timestamp`,
`X-Report-Nonce` and `X-Report-Signature`. The origin verifies the pinned public key, a five-minute
clock window, certificate validity and process-local nonce replay protection. POST is not
automatically retried or redirected. Cancelling the page session closes the request.

The legacy Cloudflare origin path uses HTTP port 80, forwarded by Nginx to loopback port 18882.
Every report still requires a valid body signature. The separate origin port 443 configuration
supports direct mTLS tests with the pinned client leaf and body signatures. Its current server
certificate is self-signed and cannot serve Cloudflare Full (strict). Encrypting that origin hop
requires a trusted server certificate and configuration for Cloudflare's separate origin
connection; Cloudflare does not forward the app client certificate.

A shared APK client key can be extracted. mTLS and signatures prove possession of that key, not
genuine device data or an unmodified app. The 8 KiB body limit, serialized forwarding, Nginx rate
limit and persistent quotas of 10 new comments per hour / 50 per day reduce abuse. No additional
sensitive Android permissions are requested.

### CI credentials

Actions Secrets `AZURPILOT_DEVICE_REPORT_CERT_BASE64` and
`AZURPILOT_DEVICE_REPORT_KEY_BASE64` contain the Base64 PEM certificate and PKCS#8 PEM key.
Base64 is encoding; Actions Secrets provide confidentiality. `rootfs.yml` decodes to ignored
`.tmp/report-credentials/`, validates expiry and key matching, then Gradle generates
`assets/device-report/`. CI disables task output caching to keep credential assets out of shared
caches. Temporary and generated files are removed after the build.
`verify_device_report_assets.py` checks readable compressed catalogs in every final APK and
exact credential equality with CI inputs. Local verification requires credential-free APKs.

CI fails if either Secret is absent. Local compilation needs no credentials; credential-free local
APKs report that submission is unavailable and should not be distributed as reporting-enabled builds.
The GitHub token is server-only in `deploy/private/service.env` with mode 0600.
It never enters the APK. Reports use only `POST /repos/wess09/AzurPilot-for-Android/issues/1/comments`.
Results include a 64-bit comment ID and a validated URL within that fixed issue.

### Service and deployment

The Go standard-library service builds a static Linux binary without a database process. Docker
runs a non-root user with a 48 MiB Go memory target, 96 MiB hard limit and one scheduler core.
`/var/lib/azurpilot-device-report/state.json` stores report hashes, timestamps and comment references,
never report bodies.

Identical reports excluding app version reuse existing comments. Pending state is persisted before
creation; retries reconcile uncertain results using markers within issue #1 comments and wait at least two
minutes before another creation. Cross-system writes cannot guarantee absolute exactly-once
delivery; extreme GitHub visibility delays can still duplicate a comment. State is capped at 10,000
records; new reports are rejected at capacity pending administrator archival or expansion.

See the [Docker operations guide](../server/device-report/deploy/README.md) for deployment and migration.
The production directory is `/srv/agentdock/AgentDock/afa-api`, with a local reverse proxy upstream
at `http://127.0.0.1:18882`. The container retains the source public key for body signatures and
deduplication state. Cloudflare client TLS certificates are managed independently at the edge.
The following systemd instructions are retained for legacy maintenance and rollback.

Build with `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w'` in
`server/device-report/`. Upload the binary, public client certificate and `deploy/`, keeping the
client private key off the server. Prepare origin certificate/key and the environment file with
`GITHUB_TOKEN` and `GITHUB_REPOSITORY=wess09/AzurPilot-for-Android`.
Run `sh deploy/install.sh ./device-report ./client-cert.pem <included-nginx-vhost-directory>`.
The current directory is `/.__gmssh/data/plugin/xz/site/data/nginx/conf/vhost`.
The installer atomically replaces the binary and restores the previous vhost if Nginx validation
fails. Inspect the service using systemctl and journalctl.

Validate no certificate → 403, certificate without signature → 401, signed unknown fields → 400,
valid report → 201 and duplicate → 200. Run Go tests/vet, Kotlin compilation, slim debug assembly
and the i18n script shown in the Chinese section.
