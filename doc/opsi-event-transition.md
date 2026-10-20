# 大世界事件过渡保护 / Operation Siren event transitions

## 中文

### 问题与范围

Redmi K60 的 Android PRoot 环境在点击扫描装置后，剧情选项尚在出现和确认阶段，
地图背景仍可能满足地图检测。剧情处理器此时返回 `False` 并不表示可以继续定位。
2026-10-20 的日志中，00:44:42 点击装置，00:44:45 开始错误调整镜头，
00:44:47 才在换队流程中提交探测资源，原事件流程漏接后续自律，00:46:08 报错。

最初的手机补丁修改了 `fleet.py` 和 `event_recovery.py`。但本仓当前固定核心
`f8792ac3178875c78a7ecfafed3f0a772620b526` 没有 `event_recovery.py` 和 `event_target`。
这里独立移植共同的过渡保护，不引入该恢复模块、入口全图扫描、任务调度、NTP 或配置迁移。

### 行为

- 在原移动循环已处理事件之后，若仍检测到剧情且调用方允许事件收尾，则等待新截图，
  不继续地图定位、确认到达或切换舰队。资源模式仍通过原返回值交回原装置处理流程。
- 扫描装置、探索容器、资源奖励点、记录塔和问号原本已使用一次点击接近，保留这些入口。
  共同的移动等待保护覆盖这些入口，不增加移动指令或扫描。
- 明石的原 `ui_click` 入口可能重试移动，改为一次接近后持续识别。
  明确进入商店后调用原购买和返回流程。选项、舰队菜单与未知页面不点击。
  连续十二秒无进展抛出原 `GameStuckError`；实际移动和已处理的剧情重置预算。
- 定位会刷新截图，因此定位后再确认剧情、商店和地图，避免点击刚出现的选项。

### Runtime 集成

`rootfs/overlays/android_opsi_event.py` 由 `sitecustomize.py` 在
`AZURPILOT_ANDROID=1` 时注册延迟导入钩子。原移动函数经 AST 插入单一保护分支，
保留原战斗、奖励、退役、移动稳定和异常处理。找不到预期循环接口时记录警告并保留原函数；
构建质量门要求两处适配标记均存在，因此不兼容版本不能静默发布。

构建脚本把 overlay 安装进 venv，并真实导入两个核心模块验证接线。
Gradle 把同一文件打进 APK 资产，`ProotHost` 每次启动前同步到 `/opt/azurpilot`，
该目录已在 `PYTHONPATH` 中。已有 Runtime 也能通过轻量 APK 更新获得保护。
核心热更只重置跟踪源码，不覆盖这些宿主 overlay；新 worker 重新导入当前核心并接线。
单独热更 Python 核心、但不更新 APK 或 Runtime 的旧安装，不会凭空获得新 overlay。

### 验证边界

原始修复目录保留的历史结果：99 项隔离测试通过，修改前同组测试为 6 失败、1 报错。
这些测试复用了当时的核心测试，通过候选路径覆盖导入；它们不属于本 PR 新增测试。
手机曾部署两个 Python 文件并恢复调度，但未完成每种资源点的完整真机验证。
当时为接续已选资源模式而修正的私人运行记录不包含在本 PR 中。

本 PR 新增测试通过 `AZURPILOT_SOURCE` 编译当前固定核心的真实移动和事件入口方法，
使用虚拟截图帧验证选项延迟、原错误复现、各事件入口、相邻明石、无进展超时、
长期移动、未知页面、定位期间出现剧情，以及打包、启动门控和幂等接线。
CI 从本仓 `.github/azurpilot-upstream.txt` 读取固定提交，不依赖未合并的 #1271。
测试不连接设备、不修改配置、不消耗账号资源。此次 overlay 尚未在 K60 安装验证。

```bash
AZURPILOT_SOURCE=/path/to/AzurPilot python3 rootfs/tests/test_android_opsi_event.py -v
```

## English

### Problem and scope

On Redmi K60 under Android PRoot, the map remains visible while device options appear and stabilize.
A story handler returning `False` during confirmation does not authorize map localization.
On 2026-10-20 the device was clicked at 00:44:42; camera adjustment began at 00:44:45;
resource detection was selected in fleet switching at 00:44:47, outside the original event flow;
the missing collection handoff ultimately caused an error at 00:46:08.

The original phone patch changed `fleet.py` and `event_recovery.py`. The Android repository's
pinned core, `f8792ac3178875c78a7ecfafed3f0a772620b526`, has neither `event_recovery.py`
nor `event_target`. This port includes transition protection only, without that recovery module,
entry scans, scheduling changes, NTP changes, or configuration migrations.

### Behavior and integration

After the existing event handler, visible story options keep event-enabled walks in their screenshot
loop, blocking localization, premature arrival, and fleet-switch escalation. Resource selection
returns through the existing result to existing device handling. Scanning devices, containers,
reward points, logging towers, and radar questions retain their existing single approach commands.

Akashi receives one approach command, then recognized frames drive entry. Only a positively detected
shop invokes original purchasing and return handling. Options, fleet menus, and unknown pages are
not clicked. Twelve seconds without progress raises the existing `GameStuckError`; actual movement
and handled story actions reset the budget. Localization is followed by another page check.

The Android-gated `sitecustomize.py` installs lazy import hooks. One AST branch augments the
original walk method, retaining battle, reward, retirement, stability, and exception handling.
Unsupported method shapes log a warning and retain upstream behavior; the rootfs build requires
both adapter markers on actual imports, preventing incompatible releases from silently passing.

The overlay is installed into the venv, bundled into APK assets, and synchronized by `ProotHost`
to `/opt/azurpilot` before runtime startup. Existing `PYTHONPATH` wiring loads it for installed
runtimes and subsequent workers. Core hot updates do not replace host overlays. An old installation
must receive the updated APK or Runtime; a core-only update cannot deliver this new overlay.

### Evidence and limits

Historical isolated testing recorded 99 passing tests, versus six failures and one error before the
phone patch. Those reused core tests are distinct from this PR's new tests. The phone received two
Python files and resumed scheduling, but not every resource type was validated. The private recovery
record adjusted during deployment is excluded from this PR.

New tests execute methods from the pinned core using virtual frames, including the original defect,
delayed options, real event entries, adjacent Akashi, timeouts, long movement, unknown pages,
mid-localization page changes, packaging, Android gating, and idempotence. CI resolves the pin from
this repository, with no dependency on unmerged #1271. Tests do not access a device, configuration,
or game resources. The new Android overlay has not been installed on the K60 for validation.
