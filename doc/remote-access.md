# 远程直连与配对 / Remote direct access and pairing

## 中文

### 用户入口

首次引导在「设置运行权限」页右上角提供蓝色「跳过」，欢迎页不再提供远程快捷入口。
完成引导后，点击主页右上角设置旁的「绑定」，即可扫描 AzurPilot 配对二维码或输入六位配对码。
本机 Runtime 不会自动下载；未安装时，主页显示「下载 Runtime」，由用户主动开始部署。
绑定成功后切换到内置原生控制台；控制台断连页也保留绑定与已保存设备入口。

主页通过当前来源的 WS 总览显示启动、停止和最近三项任务，可展开查看全部。
任务沿用调度器返回的顺序、状态和下次执行时间；「最近任务」表示当前与即将执行的计划，
不是从日志推断的历史记录。远程启停使用 `scheduler.start` / `scheduler.stop`，
不触发本机 Runtime 或手机运行权限。未绑定或断连时禁用启动，不显示旧任务。
本机 Runtime 与远程 AzurPilot 的总览都提供完整「任务计划」，部署后即可查看。

绑定页以扫码为主要操作，配对码输入为次要操作，已绑定设备单独分组。六码输入只接受数字，
键盘确认和按钮共用提交条件；忙碌时禁止重复提交。设备列表区分实际连接状态与当前选择，
不会把等待连接显示成已连接。打开绑定页本身不切换运行模式。

`AppSettings.remoteClientMode` 保存客户端模式。该模式屏蔽本机自动部署、启动、定时 Runtime 更新、
Shizuku 引导与机型支持提示，应用锁和协议确认仍生效。「返回本机 AP」恢复原来的本机流程；
切换来源不会停止已经运行的本机任务。本机 Runtime 的「试用 Quick Tunnel」仅用于让本机 AP 提供远程访问，
与作为客户端连接其他 AP 是独立操作。

AFA 每次连接都请求固定入口所属服务的 `/api/v1/devices/{id}/resolve`，取得服务端验证的公网
IPv4 / IPv6 候选。从手机当前网络请求候选的 `/healthz`，确认设备 ID 与协议后，以 HTTP/WS
直连；所有直连都失败时请求 `/fallback` 启动 Cloudflare，再以 HTTPS/WSS 连接。
会话失败的入口暂时排除三十秒，每次重连重新解析。AP 重启、动态 IP 或 Tunnel URL 改变无需重新配对。
等待 Cloudflare 时继续检查新出现的直连地址，可达后立即连接，不等待隧道完成。
旧服务返回 404/405 时兼容禁止缓存的 301 跳转，严格校验 `https://<name>.trycloudflare.com/`。
设备离线时，浏览器入口以 302 跳到 `/error`；原生客户端提示稍后重试，重新打开会重新解析入口。
所有 HTTP/WS 直连都失败且服务端允许 P2P 时，AFA 会静默建立 WebRTC DataChannel 直连，不使用 TURN 中继；
P2P 再失败才回退 Cloudflare，界面和操作流程保持不变。

「试用 Quick Tunnel」将本机 AP 的远程模式保存为 `cloudflare` 并提示重启；新版 Runtime 中
`auto` 和 `cloudflare` 均先注册公网直连候选，无可达直连或客户端主动请求时才启动 Tunnel。
AP 每十秒上报网卡公网地址与端口，服务端以随机挑战验证，后台每三分钟复检。
私有 IPv4、CGNAT、链路本地和 ULA 不作为公网候选，不申请设备域名或证书。
`RemoteAccessPort: 0` 默认复用 HTTP WebUI 端口（通常 25548），HTTPS WebUI 才另用端口 + 1；`RemoteAccessPublicPort: 0`
默认使用监听端口。外部已有端口映射时可单独配置，AP 不修改路由器或防火墙。
首次启用会写入 Android ID 与随机安装因子的 SHA-256 摘要作为设备 ID，设备注册密钥由 AP 保存在
`config/remote-access.json`。原始 Android ID 不上传，已有非空设备 ID 和注册身份继续复用。
旧 SSH / WebRTC 设置保留；已有模式只在用户选择试用时切换。

### 构建前提

本机提供远程入口需要新版 AP Runtime：必须包含 AP 仓库的 `remote_direct.py`、`remote_cloudflare.py`、配对 API、
前端及 `pyflared==0.1.0` 锁定依赖。新 APK 不会把旧 Runtime 的 Python 源码替换成开发机副本。
构建 Runtime 时，将 `AZURPILOT_REPO` / `AZURPILOT_REF` 指向包含对应 AP 实现的源码与提交，
然后执行 `rootfs/build/build-azurpilot.sh`；架构须与设备一致。构建步骤见
[runtime-provisioning.md](runtime-provisioning.md) 和 [multi-arch.md](multi-arch.md)。

只连接远程 AzurPilot 时不需要更新本机 Runtime。远端必须已启用远程访问，并在远程访问页
生成配对码。使用普通二维码，载荷固定为 `azurpilot://pair?secret=<32 位小写十六进制密钥>`；
解码器校验完整格式后才允许领取配对。

### 存储与安全边界

- `remoteaccess/RemotePairings.kt` 使用 OkHttp 和 kotlinx.serialization 调用注册服务。
  服务器地址固定为 `https://remote.azurpilot.com`，领取接口为 `/api/v1/pairings/claim`，不接受扫码内容指定任意服务器。
  旧试验地址的已保存配对与本机注册配置会迁移到新域名，继续使用原设备 ID 和独立授权。
- 设备列表和独立凭据通过 Android Keystore AES-GCM 加密，保存到 `noBackupFilesDir`。
  长期凭据不进入 DataStore 明文偏好、Android 备份、日志或 Intent。扫描 Activity 仅返回本次配对的一次性密钥。
- `QrScanActivity` 用 CameraX 提供全屏预览，ZXing 解码亮度平面；预览和分析共用全屏视口，
  按 `ImageProxy.cropRect`、行填充和像素步长读取全部可见区域。
  CameraX 坐标变换将二维码边界映射到预览。识别后白框以 120ms 显现，再以 280ms 平移和缩放到
  中央，短暂停留后只返回一次密钥；系统关闭动画时省去位移。关闭或后台状态不会提交结果。
  相机帧和识别截图只保存在内存中。
- `AzurPilotGateway` 每次连接重新解析固定入口，以 WS 直连或 WSS Tunnel 完成鉴权后才发布就绪并恢复订阅。
  会话每十五秒发送心跳，撤销的凭据会在后续请求时失去授权。直连 HTTP 使用 Android 的明文网络许可。
  内置原生总览、配置、日志和统计复用同一个 `AzurPilotRepository`，远程 AP 支持实例选择。
- 切换来源前等待编辑保存，关闭旧连接并清空缓存。原生总览显示来源，设置可返回本机 AP。
  本机 PRoot、悬浮窗、设备控制和启动器 HTTP 地址不变；远程配置不会同步为本机偏好。
- AFA「移除」只清除本机保存的入口；在 AP「已授权的配对」撤销后，已连接会话在下一次请求
  或心跳时失去授权。主 WebUI 密码无需改变。

### 服务与验证

Go + SQLite 服务及 Docker 部署位于独立私有仓库 [AzurPilot-Remote](https://github.com/wess09/AzurPilot-Remote)。
浏览器固定入口显示 IPv4、IPv6 和 Cloudflare，由用户手动选择，不代理业务流量。
服务端及一次性配对保持 HTTPS；设备直连使用 HTTP/WS。

开发验证使用临时设备身份、隔离 WebUI 和真实 Quick Tunnel，不启动真实游戏任务。
Kotlin 使用 `compileDebugKotlin`；`app/gradlew -p tools/remote-gateway-check test` 直接验证
生产网关和光学协议，不运行本仓库已知孤立测试桩的 `testDebugUnitTest`。
Android 15 x86_64 独立模拟器已验证实际 Keystore 加密保存、六码配对、原生控制台读取配置、
Tunnel 换址后重连与活动凭据撤销。当前相机验收应使用包含普通配对二维码的视频，送入模拟器
`videofile` 摄像头，由实际 CameraX 采集并调用生产解码器，最后通过扫描密钥向真实服务领取配对。
物理手机的相机成像与厂商后台限制仍需单独评估。

独立设备测试构建命令：

```powershell
.\app\gradlew.bat -p app :app:assembleDebugAndroidTest -PremoteAccessTestsOnly=true '-Pazurpilot.slimApk=true'
```

`RemoteEntryInstrumentedTest` 的历史新安装用例仍假定欢迎页存在远程快捷入口；更新步骤断言前，
不应启用 `freshRemoteEntry=true`。当前新安装验收需经过协议、跳过运行权限、完成引导，再从
主页打开绑定，同时断言全过程不自动安装或启动本机 Runtime。

其他测试类为 `com.azurpilot.ghio.remoteaccess.RemoteAccessInstrumentedTest`。先确认测试设备
`sys.boot_completed=1` 且外部存储已挂载；通过 instrumentation 参数 `pairingCode` 执行原生
配对用例，`scanSecret` 执行相机用例，`runtimeHosting=true` 执行内置 Runtime 提供远程入口的用例。
`reconnect=true` / `revoke=true` 启用宿主协调的断连和撤销验收；阶段写入测试实例的私有
`files/remote-acceptance-phase`。测试数据只能使用独立安装、临时 AP 配置及单次凭据。
默认不写相机帧；只有显式传入 `cameraDiagnostics=true` 才保存合成相机验收帧供定位。
Quick Tunnel 处于试验范围，不能据此承诺生产环境可用性。

## English

### User entry

The runtime-permission step offers a blue Skip action at the top right. Welcome has no remote shortcut.
After setup, Bind next to Settings on home opens QR scanning and six-digit pairing. Missing local Runtime
is never downloaded automatically; Download Runtime on home starts deployment explicitly. Successful
binding opens the native console, which retains binding and saved-device controls while disconnected.

Home uses the current source's WebSocket overview for start/stop controls and the first three tasks,
with an action to expand the full list. Tasks retain scheduler order, state and next-run time;
Recent tasks means current and upcoming plans rather than history inferred from logs. Remote control
calls `scheduler.start` / `scheduler.stop` without starting local Runtime or requesting phone permissions.
Unbound and offline sources disable starting and hide stale tasks. Both local Runtime and remote
AzurPilot overview pages show the complete Task plan.

Binding prioritizes scanning, offers code entry second, and groups saved devices separately. Only six
digits enable submission, including the keyboard action; busy state blocks duplicate submissions. Saved
devices distinguish actual connectivity from selection. Opening the page alone does not switch modes.

`AppSettings.remoteClientMode` persists client mode and suppresses local automatic provisioning, startup,
scheduled Runtime updates, Shizuku guidance and device-support prompts. App lock and legal acknowledgement
remain effective. Return to local AP restores the existing local flow without stopping running local tasks.
Hosting a local Quick Tunnel is separate from controlling another AP.

Standard QR codes carry `azurpilot://pair?secret=<32 lowercase hexadecimal characters>`. The decoder
validates this exact payload before allowing a pairing claim.

Every connection reads `/api/v1/devices/{id}/resolve` from the registry, checks each verified public IP
from the phone's network using `/healthz`, validates the device ID, and connects over HTTP/WS.
If no direct route works, it requests `/fallback` and connects through Cloudflare over HTTPS/WSS.
Failed sessions exclude their route for thirty seconds. Reconnection resolves again, so AP restarts,
dynamic IP changes, and tunnel changes do not require pairing again.
Direct routes are checked again while waiting for Cloudflare, so a newly reachable IP connects immediately.
Legacy 404/405 responses retain the non-cacheable 301 path with strict `https://<name>.trycloudflare.com/` validation.
Offline browser entries receive a 302 to `/error`. Native clients show a retry message; reopening resolves the entry again.
When every HTTP/WS direct route fails and the server allows P2P, AFA silently opens a WebRTC DataChannel
direct connection without TURN relaying. It falls back to Cloudflare only when P2P also fails; the UI and interaction flow remain unchanged.

Try Quick Tunnel saves `cloudflare` as the local AP remote mode and requests a runtime restart.
With the new Runtime, both `auto` and `cloudflare` register public interface IPv4/IPv6 and ports every
ten seconds. The server verifies ownership using random challenges and checks routes every three minutes.
Cloudflare starts when no direct route works or a client requests it. Private IPv4, CGNAT, link-local,
and ULA addresses are excluded; device DNS names and certificates are unnecessary.
`RemoteAccessPort: 0` reuses HTTP WebUI (normally 25548), using port plus one only for HTTPS WebUI; `RemoteAccessPublicPort: 0` uses
the listener port. Existing external mappings can use a separate port; AP does not change routers or firewalls.
First use stores a SHA-256 digest of Android ID plus an installation nonce as the ID; AP persists a registration
key in `config/remote-access.json`. The raw Android ID is not uploaded. Existing nonempty device IDs
and registration identities are retained. Existing SSH/WebRTC modes change only on explicit trial selection.

### Build prerequisite

Hosting remote access requires an AP runtime containing `remote_direct.py`, `remote_cloudflare.py`, the pairing API, frontend,
and locked `pyflared==0.1.0` dependency. Installing a new APK does not replace the Python source of an
old runtime with a development checkout. Point `AZURPILOT_REPO` and `AZURPILOT_REF` at the matching
AP implementation and commit, then run `rootfs/build/build-azurpilot.sh` for the device ABI. See
[runtime-provisioning.md](runtime-provisioning.md) and [multi-arch.md](multi-arch.md).

Controlling a remote AP does not require upgrading the local runtime. The remote AP must have
remote access enabled and generate a code on its remote access page.

### Storage and trust boundaries

- `remoteaccess/RemotePairings.kt` uses OkHttp and kotlinx.serialization. The registry is fixed to
  `https://remote.azurpilot.com`, with claims at `/api/v1/pairings/claim`; scanned data cannot select arbitrary servers.
  Saved pairings and local registration settings from the old trial host migrate while retaining device IDs and credentials.
- Android Keystore AES-GCM encrypts entries and independent credentials in `noBackupFilesDir`.
  Long-term credentials are excluded from plaintext DataStore, backups, logs, and Intents. The scanner activity returns only a one-time pairing secret.
- `QrScanActivity` presents full-screen CameraX preview and decodes luminance with ZXing. Preview and
  analysis share the full-screen viewport; sampling respects `ImageProxy.cropRect`, row and pixel strides.
  CameraX transforms map detected bounds into preview coordinates. A white frame appears over 120ms,
  then translates and scales to center over 280ms before returning the secret once after a short hold.
  Reduced motion omits movement. Close and background state never submit a result. Frames and captured
  crops stay in memory.
- `AzurPilotGateway` resolves the stable entry on each connection, authenticates over direct WS or tunnel WSS before publishing
  readiness, and restores subscriptions. Native overview, configuration, logs and statistics use the existing
  repository; remote APs support instance selection. A fifteen-second heartbeat checks authorization during
  sessions. Android cleartext network permission allows public HTTP direct access.
- Switching settles edits, closes old sockets and clears caches. Settings can return to local AP.
  Local PRoot, overlays, device control and host HTTP endpoints remain local. Remote settings do not overwrite
  local app preferences.
- Remove in AFA deletes the local saved entry. Revoking a pairing in AP rejects its next request or
  heartbeat, including already connected sessions, without changing the primary WebUI password.

### Service and verification

The Go/SQLite service and Docker deployment live in the separate private repository
[AzurPilot-Remote](https://github.com/wess09/AzurPilot-Remote). Browser entries offer IPv4, IPv6, and
Cloudflare choices without proxying business traffic. Registry and pairing use HTTPS; direct device
connections use HTTP/WS.

Development verification uses temporary identities, an isolated WebUI and a real Quick Tunnel,
without starting game tasks. Kotlin is checked with `compileDebugKotlin`; the known orphaned unit
test source set is not run. `app/gradlew -p tools/remote-gateway-check test` verifies the production gateway
and optical decoder on JVM. An isolated Android 15 x86_64 emulator also verifies Keystore encryption,
six-digit pairing, native configuration reads, reconnection after tunnel changes, and active credential revocation.
Current camera acceptance requires a video containing standard pairing QR codes through the emulator's
`videofile` camera, actual CameraX analysis and the production decoder, followed by a real registry claim.
Physical camera characteristics and vendor background restrictions still need separate assessment.

The historical fresh-install case in `RemoteEntryInstrumentedTest` assumes a remote welcome shortcut.
Do not enable `freshRemoteEntry=true` until its step assertions are updated. Current entry acceptance
must acknowledge legal notices, skip runtime permissions, finish setup, and open Bind from home while
asserting no automatic local Runtime installation or startup.

Build instrumented tests with `app/gradlew -p app :app:assembleDebugAndroidTest -PremoteAccessTestsOnly=true
-Pazurpilot.slimApk=true`. Run `RemoteAccessInstrumentedTest` only after Android boot and storage mount complete.
Arguments `pairingCode`, `scanSecret`, and `runtimeHosting=true` select the native pairing, camera, and bundled
runtime hosting cases. `reconnect=true` and `revoke=true` coordinate host-controlled recovery checks through the
private `files/remote-acceptance-phase` file. Use isolated installs, temporary AP configurations, and one-time tokens.
Camera frames are saved only when `cameraDiagnostics=true` explicitly enables synthetic-frame diagnostics.
Quick Tunnel is experimental.
