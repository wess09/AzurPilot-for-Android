# 更新日志 / Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式；
版本号见各 Release（`AzurPilot-Android-<version>`）。

This project follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
see each Release (`AzurPilot-Android-<version>`) for version numbers.

## Unreleased · 2026-10-02

### 新增 / Added

- **局域网控制**（设置 → Runtime，默认关闭）：开启并重启 Runtime 后，运行时 WebUI 绑定 0.0.0.0，同一 Wi-Fi 下的电脑可用浏览器直接控制挂机；首次使用自动生成访问口令，设置页可直接查看。
- **远程访问**（设置 → Runtime，默认关闭）：开启并重启 Runtime 后，经上游 localshare 中转生成可从公网访问的入口（P2P 直连优先，SSH 隧道兜底），出门也能用网页控制；开启时若未设访问口令会自动生成，请妥善保管。
- **重启 Runtime**（设置 → Runtime）：一键重启运行时进程，是上述开关改动的生效入口，也可用于会话卡死后的手动恢复；重启前会先让运行中的任务收尾，任务不会跨重启自动恢复。

- **LAN control** (Settings → Runtime, off by default): after enabling and restarting the Runtime, the runtime WebUI binds 0.0.0.0 so computers on the same Wi-Fi can control the automation from a browser; the access password is generated on first use and shown on the settings page.
- **Remote access** (Settings → Runtime, off by default): after enabling and restarting the Runtime, a publicly reachable entry is created via the upstream localshare relay (P2P preferred, SSH tunnel fallback) so you can control it from the web anywhere; the access password is generated automatically if not set — keep it safe.
- **Restart Runtime** (Settings → Runtime): one-tap restart of the runtime process; it applies the toggles above and recovers a wedged session. Running tasks are wound down first and do not resume across the restart.

### 修复 / Fixed

- 逐页整理设置界面：信息行标签不再被挤成多行，后台保活的机制名不再夹杂英文类名并改称「七路保活机制」（与列表一致），移除「应用锁屏保护」「开源组件」两处重复标题。
- 修复 proot 环境缺少宿主 uid 的 `/etc/passwd` 条目导致 ssh 无法启动的问题（远程访问在 Android 上运行的前提）。
- `android_host.py` 边界 overlay 改为随 APK 分发并在每次拉起会话前同步进 rootfs：只走热更的用户也能拿到 overlay 更新。
- 修复 proot 容器 resolv.conf 写死阿里 DNS 导致部分屏蔽公共 DNS 的校园网/企业网无法热更新检查的问题：会话启动时容器改读当前默认网络公布的 DNS 服务器，尾部追加阿里兜底（不超过 glibc MAXNS 三条），采集失败时退化为纯阿里兜底。mainland 兜底行为不变。

- Settings pages tidied page by page: info-row labels no longer wrap into multiple lines, the keep-alive mechanism names drop the English class names and now read "seven keep-alive mechanisms" consistent with the list, and the duplicated "App lock" / "Open-source components" section titles are removed.
- Fixed ssh failing to start because the proot environment lacked an `/etc/passwd` entry for the host app's uid (a prerequisite for remote access on Android).
- The `android_host.py` boundary overlay is now shipped with the APK and synced into the rootfs before every session spawn, so hot-update-only users also receive overlay updates.
- Fixed the proot container resolv.conf hard-coding AliDNS, which made hot-update checks fail on networks that block public DNS (some campus and enterprise networks). At session start, the container now reads the DNS servers advertised by the current default network and appends the AliDNS fallback at the tail (capped at glibc's MAXNS of three); a collection failure degrades to the plain AliDNS fallback. The mainland fallback behavior is unchanged.
