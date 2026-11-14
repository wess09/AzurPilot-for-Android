# 远程注册服务 / Remote registry

服务端已迁移到独立私有仓库 [AzurPilot-Remote](https://github.com/wess09/AzurPilot-Remote)。

Go 服务负责设备身份注册、公网 IPv4/IPv6 探测、每三分钟复检、Cloudflare 回退请求及一次性配对。
Docker 镜像、Compose、部署示例和服务测试均在该仓库维护。原 SQLite 数据库可直接升级，
保留设备 ID、注册密钥摘要及配对记录；升级前先备份数据库。

AFA 客户端实现位于 `app/app/src/main/kotlin/com/azurpilot/ghio/remoteaccess/`，
使用说明见 [remote-access.md](../../doc/remote-access.md)。

The server has moved to the separate private repository
[AzurPilot-Remote](https://github.com/wess09/AzurPilot-Remote).

The Go service handles registration, public IPv4/IPv6 verification, three-minute rechecks,
Cloudflare fallback requests, and one-time pairing. Docker images, Compose, deployment examples,
and server tests are maintained there. Existing SQLite databases upgrade while retaining device IDs,
registration key hashes, and pairings; back up the database before upgrading.

AFA client code remains under `app/app/src/main/kotlin/com/azurpilot/ghio/remoteaccess/`.
See [remote-access.md](../../doc/remote-access.md) for usage.
