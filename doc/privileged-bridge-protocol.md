# 特权桥协议 / Privileged bridge protocol

> 本文定义 App Runtime 客户端与特权进程内本地桥之间当前可验证的线协议。
> This document defines the currently verifiable wire protocol between the App Runtime client and the local bridge in the privileged process.

## 中文

### 范围与边界

桥服务实现位于 `app/app/src/main/kotlin/com/azurpilot/ghio/remote/internal/BridgeServer.kt`，只监听 `127.0.0.1:22301`。它不是公网服务：不要把端口转发、监听地址变更或认证方案视为兼容的小改动。特权进程拥有屏幕捕获、输入注入和 shell 执行能力，任何新端点都必须先审查最小权限、参数约束、超时与输出上限。

协议兼容性由当前服务实现和本文件共同定义。不存在可作为规范依据的历史 m0 脚本，也不得宣称与未固定的上游补丁逐字节兼容。

### 帧格式

1. 客户端向 socket 写入一行 UTF-8 JSON，并以 `\n` 结尾。请求行上限为 64 KiB；超限连接会断开。
2. 除 `screencap` 的成功负载外，服务向客户端写入一行 UTF-8 JSON 并以 `\n` 结尾。
3. 服务从请求读取可选 `id`，并在每个成功或错误 JSON 响应中写回同一 `id`。缺失时写 JSON `null`。
4. `screencap` 成功时，服务先写 JSON 元数据行，再在同一输出流紧接写入 `length` 字节的原始 BGR888 图像数据。客户端必须先完整读取该 JSON 行并按 `length` 读完字节，才能发送或读取下一条协议消息。
5. 无效 JSON 会收到一次带 `ok=false` 的错误响应，随后连接关闭。其他单请求异常通常返回错误 JSON，连接保持打开；客户端仍必须容忍断开。

### 端点

| 方法 | 必填字段 | 成功响应 | 约束 |
|---|---|---|---|
| `ping` | 无 | `ok`、`pong`、`displayId`、`capture`、`uptime`、`capabilities` | `capabilities.equipmentCode=true` 表示支持下述装备码接口。诊断不表示目标应用已经可交互；旧桥无能力字段时客户端须停止装备码操作。 |
| `screencap` | 无 | `ok`、`width`、`height`、`channels=3`、`length`、`frames`，后接 BGR888 字节 | 虚拟屏 (重)建后首帧尚未渲染时，请求最多阻塞约 3 秒等待首帧；捕获不可用或尺寸不匹配时返回错误 JSON，不发送原始字节。`frames` 是本轮帧缓冲生命周期内已发布帧的递增序号：相邻两次 screencap 的值不变即画面冻结（如目标进程被 ROM 冻结）。客户端可忽略该字段。 |
| `click` | `x`、`y` | `ok=true` | 使用当前虚拟显示；无活动显示或触摸注入失败时返回错误。 |
| `swipe` | `x1`、`y1`、`x2`、`y2` | `ok=true` | 可选 `duration` 为毫秒，负值按 0 处理。 |
| `equipment_code_read` | 无 | `ok=true`、`code` | 从系统剪贴板的首项读取装备码；无有效码返回 `code=null`，不展开 URI，不返回其他内容。无活动虚拟屏或系统拒绝访问时返回错误。 |
| `equipment_code_input` | `code` | `ok=true` | 仅接受 16–4096 字符、长度为 4 的倍数的 Base64 文本，末尾至多两个 `=`。在当前虚拟屏的已聚焦输入框执行 Ctrl+A、Delete、文本输入和 Enter；失败返回错误，并释放已按下的键。逐键序列预算 10 秒（不打断进行中的系统注入调用）。客户端仍须通过截图确认预览已加载。 |
| `shell` | `cmd` | `ok`、`code`、`stdout`、`stderr` | 可选 `timeout` 为秒；每个输出流最多保留 64 KiB，超时会返回错误。 |
| `app_prepare` | `package`、`displayId` | `ok=true` | `ping.capabilities.nativeGameResolution=true` 时可用；只允许当前 1280×720 虚拟屏和已安装的有效包名。启动前关闭目标包的标准及 APS 兼容缩放，回读验证并在 APS 设备上强停旧进程。拒绝修改时返回错误，客户端须停止任务。见 [原生分辨率](virtual-display-startup.md)。 |
| `app_focus` | `package`、`displayId` | `ok=true`、`displayId` | `ping.capabilities.appFocus=true` 时可用；只聚焦当前虚拟屏的匹配顶层包，拒绝主屏或失效 ID。按下后立即取消，不完成点击；窗口就绪重试预算为 3 秒。见 [启动聚焦](virtual-display-startup.md)。 |

未知或缺失 `method` 返回 `ok=false`。请求参数类型错误由 JSON 访问器抛出后返回错误响应；客户端不得依赖具体错误文案。

### 共享内存截图 v1

`ping` 的 `capabilities.sharedMemoryScreencap` 表示特权端支持共享内存，
`sharedFrameSocket` 为抽象 Unix 地址 `com.azurpilot.ghio.frames.v1`。
App 接受本 UID 的连接后，以固定 Binder transaction 78 (`openSharedFrameChannel`)
交付已连接 FD；特权进程无需执行跨 SELinux 域的 connect/listen/accept/setopt。
socket 超时由 App 在交付前设置为 60 秒。旧 TCP `screencap` 保持不变。

客户端先发送 `{"method":"open","version":1}`，响应包含 `ok`、`version=1` 和
`capacity`，并通过 `SCM_RIGHTS` 携带一个仅可只读 mmap 的匿名内存 FD。
之后发送 `{"method":"screencap"}`，响应包含 `width`、`height`、`channels=3`、
`length`、`frames` 和 `transport="shared_memory"`；没有随附像素字节。
元数据和像素来自同一 native 帧槽，响应前完成 memcpy。
虚拟屏 (重)建后首帧尚未渲染时，该请求最多阻塞约 3 秒等待首帧，超时返回
`ok=false` 的 `no frame available`；等待只占用当前连接，不影响其它连接。

每个连接拥有独立映射，下一次请求才会覆盖该连接的帧。客户端必须先复制成自有 RGB
数组，才能发送下一条截图请求；保留映射视图或管线化请求会破坏这个保证。
此通道不取得输入锁，因此其它连接的滑动与截图互不阻塞。
分辨率扩大超出容量时返回 `ok=false,reopen=true`，客户端重连重新分配。
上限为 8 个连接、每个映射 32 MiB、请求行 4 KiB；连接结束释放 FD 和映射。

`rootfs/overlays/android_capture.py` 自动安装此通道，首次失败重连一次后回退到上游
TCP，30 秒后重试。远程或自定义代理地址继续使用 TCP；环境变量
`AZURPILOT_ANDROID_CAPTURE=tcp` 可强制旧通道。详见 [共享内存截图](shared-memory-capture.md)。

### 并发与修改规则

服务为每个客户端使用一个守护线程。`screencap`、`click`、`swipe` 及装备码接口共享设备锁，因而会相互串行；`shell` 不占用该锁。不要把该实现细节当作客户端并发授权，客户端应在自身侧保持单个设备操作序列有序。

Android 装备码适配由 `rootfs/overlays/android_equipment.py` 在 AP 模块导入后安装。APK 在每次启动运行时前同步此文件，因此已有 rootfs 和后续源码热更均可使用。适配只替换设备读写，不启用 FastInputIME，也不回退到主屏 uiautomator2；上游的导出提示确认、码校验、预览确认、保存失败及停止换船保护仍然生效。

虚拟屏前台检测由 `rootfs/overlays/android_device.py` 在 AP 的 Android 设备模块导入后安装。
解析器以 `Display: mDisplayId=…` 或编号标题划分显示器段，并精确匹配桥返回的 ID。
查询仅使用目标段的窗口焦点；焦点不可用时，在同一总超时预算内读取该屏的已恢复 Activity，
不会回退到主屏包名。APK 在 Runtime 启动前同步此文件，上游源码热更不会覆盖修复。

修改协议前必须：

1. 更新本文件和 `BridgeServer` 的 KDoc。
2. 保持 `screencap` 元数据与原始字节的边界明确，或引入版本化协商。
3. 为新增字段采用向后兼容的可选字段，除非同步升级所有已知客户端。
4. 复核新命令是否扩大特权进程的 shell、输入或屏幕访问面。
5. 在真实设备上验证正常响应、错误响应、客户端断开和超时路径。

## English

### Scope and boundary

The bridge implementation is `app/app/src/main/kotlin/com/azurpilot/ghio/remote/internal/BridgeServer.kt` and listens only on `127.0.0.1:22301`. It is not a public network service. Treat port forwarding, bind-address changes, and authentication changes as compatibility-sensitive work. The privileged process can capture screens, inject input, and execute shell commands, so every new endpoint requires a least-privilege review of parameters, timeouts, and output caps.

The current service implementation and this document define compatibility. No historical m0 script is a normative source, and the project must not claim byte-for-byte compatibility with an unpinned upstream patch.

### Frame format

1. The client writes one UTF-8 JSON request terminated by `\n`. Requests are capped at 64 KiB; an over-limit request disconnects the client.
2. Except for a successful `screencap` payload, the service writes one UTF-8 JSON response terminated by `\n`.
3. The service reads an optional request `id` and echoes that `id` in every success or error JSON response. A missing id becomes JSON `null`.
4. For successful `screencap`, the service writes a JSON metadata line, then immediately writes `length` bytes of raw BGR888 image data on the same stream. The client must finish reading the JSON line and exactly `length` bytes before sending or reading another protocol message.
5. Invalid JSON receives one `ok=false` error response and then the connection closes. Other per-request exceptions normally return an error JSON response while the connection remains open, but clients must still tolerate disconnects.

### Endpoints

| Method | Required fields | Success response | Constraints |
|---|---|---|---|
| `ping` | None | `ok`, `pong`, `displayId`, `capture`, `uptime`, `capabilities` | `capabilities.equipmentCode=true` advertises the equipment endpoints below. Diagnostics do not prove that the target app is interactive; clients must stop equipment operations when an older bridge omits the capability. |
| `screencap` | None | `ok`, `width`, `height`, `channels=3`, `length`, `frames`, followed by BGR888 bytes | After a virtual-display (re)build with no rendered frame yet, the request blocks for at most ~3 seconds waiting for the first frame. An unavailable capture or size mismatch returns error JSON and no raw bytes. `frames` is the increasing sequence of frames published within the current frame-buffer lifetime: identical values across consecutive screencaps mean a frozen picture (for example, the target process frozen by the ROM). Clients may ignore the field. |
| `click` | `x`, `y` | `ok=true` | Uses the current virtual display; no active display or failed injection is an error. |
| `swipe` | `x1`, `y1`, `x2`, `y2` | `ok=true` | Optional `duration` is milliseconds; negative values become 0. |
| `equipment_code_read` | None | `ok=true`, `code` | Reads an equipment code from the first system clipboard item; an invalid or absent code returns `code=null`. Does not resolve URIs or return other content. An absent virtual display or denied system access returns an error. |
| `equipment_code_input` | `code` | `ok=true` | Accepts only 16–4096 Base64 characters, a length divisible by 4, and at most two trailing `=` characters. Sends Ctrl+A, Delete, text, and Enter to the focused field on the current virtual display. Failure returns an error and releases pressed keys. The key sequence has a 10-second budget, without interrupting a system injection call in progress. Clients must still confirm a loaded preview from screenshots. |
| `shell` | `cmd` | `ok`, `code`, `stdout`, `stderr` | Optional `timeout` is seconds; each output stream retains at most 64 KiB, and timeout returns an error. |
| `app_prepare` | `package`, `displayId` | `ok=true` | Available with `ping.capabilities.nativeGameResolution=true`. Requires the current 1280×720 virtual display and a valid installed package. Before launch, disables and verifies package-specific standard and APS scaling, then force-stops the old process on APS devices. Rejection returns an error; the client must stop the task. See [native resolution](virtual-display-startup.md). |
| `app_focus` | `package`, `displayId` | `ok=true`, `displayId` | Available with `ping.capabilities.appFocus=true`. Focuses only the matching top package on the current virtual display; rejects primary or stale IDs. Sends down/cancel without completing a click, with a 3-second window-readiness retry budget. See [launch focus](virtual-display-startup.md). |

An unknown or missing `method` returns `ok=false`. Type errors in request fields become an error response; clients must not depend on exact error wording.

### Shared-memory capture v1

`ping` exposes privileged-side support through `capabilities.sharedMemoryScreencap` and the
abstract Unix address `com.azurpilot.ghio.frames.v1` through `sharedFrameSocket`.
The app accepts same-UID connections and hands connected FDs to the privileged service through
fixed Binder transaction 78 (`openSharedFrameChannel`). The privileged process does not perform
cross-domain connect/listen/accept/setopt; the app sets the socket timeout to 60 seconds before
handoff. Legacy TCP `screencap` remains unchanged.

Clients first send `{"method":"open","version":1}`. The response includes `ok`, `version=1`,
and `capacity`, with one anonymous-memory FD delivered through `SCM_RIGHTS` and restricted to
read-only mmap. Subsequent `{"method":"screencap"}` responses include `width`, `height`,
`channels=3`, `length`, `frames`, and `transport="shared_memory"`, without trailing pixel bytes.
Metadata and pixels come from the same native slot; memcpy finishes before the response.
After a virtual-display (re)build with no rendered frame yet, a screencap blocks for at most
~3 seconds waiting for the first frame, then returns `ok=false` with `no frame available`; the
wait occupies only the requesting connection and does not affect other connections.

Each connection owns its mapping, which is overwritten only on its next request. Clients must
copy into an owned RGB array before requesting again; retaining mapping views or pipelining
requests violates that guarantee. This channel does not take the input lock, so another
connection's swipe cannot block capture. Growth beyond capacity returns `ok=false,reopen=true`,
requiring reconnection. Limits are 8 connections, 32 MiB per mapping, and 4 KiB request lines.
Connection teardown releases FDs and mappings.

`rootfs/overlays/android_capture.py` installs capture automatically, reconnecting once on initial
failure before falling back to upstream TCP and retrying after 30 seconds. Remote or customized
proxy addresses retain TCP; `AZURPILOT_ANDROID_CAPTURE=tcp` forces the legacy channel.
See [shared-memory capture](shared-memory-capture.md).

### Concurrency and change rules

The service uses one daemon thread per client. `screencap`, `click`, `swipe`, and equipment endpoints share a device lock and therefore serialize with one another; `shell` does not use that lock. Do not treat this implementation detail as permission for client-side concurrency. A client should keep one device-operation sequence ordered.

`rootfs/overlays/android_equipment.py` installs the Android adapter after the AP module imports. The APK syncs this file before each runtime start, supporting existing rootfs installations and subsequent source updates. The adapter replaces device I/O without enabling FastInputIME or falling back to primary-display uiautomator2. Upstream export confirmation, code validation, preview confirmation, save-failure handling, and equipment-transfer safeguards remain in effect.

`rootfs/overlays/android_device.py` installs virtual-display app detection after AP's Android
device module imports. It separates displays by `Display: mDisplayId=…` or numbered headers
and matches the bridge-provided ID exactly. Queries use only the target section's focus;
when unavailable, its resumed Activity is queried within the same total timeout budget,
without falling back to the primary-display package. The APK syncs this file before Runtime
startup, and upstream source updates do not overwrite the fix.

Before changing the protocol:

1. Update this document and the `BridgeServer` KDoc.
2. Keep the `screencap` metadata/raw-byte boundary explicit, or introduce versioned negotiation.
3. Make new fields optional and backward compatible unless every known client upgrades together.
4. Review whether a new command expands the privileged process's shell, input, or screen-access surface.
5. Verify success, error, client-disconnect, and timeout paths on a physical device.
