# AFA API Docker 部署 / AFA API Docker deployment

## 中文

### 运行方式

API 在腾讯云主机 `/srv/agentdock/AgentDock/afa-api` 运行。
`compose.yaml` 位于该目录的 `deploy/`，服务名为 `api`，项目名为 `afa-api`。
本次仅部署 Docker；管理员负责 DNS、反代、HTTPS 和 Cloudflare 边缘 mTLS。

| 项目 | 配置 |
| --- | --- |
| 对外 API 地址 | `https://afa-api.azurpilot.com/v1/device-reports` |
| 同机反代上游 | `http://127.0.0.1:18882` |
| 健康检查 | `GET /healthz`，正常返回 `{"status":"ok"}` |
| 请求体上限 | 8192 字节 |
| 建议反代读取超时 | 至少 40 秒 |
| 容器用户 | `65532:65532` |
| 内存与 CPU | 96 MiB 上限、1 CPU，Go 内存目标 48 MiB |

Compose 使用 Linux host 网络，保留应用只接受回环监听的限制，不配置 `ports`。
反代需要在同一主机的网络命名空间内访问上游；独立桥接网络中的反代容器不能通过自身的
`127.0.0.1` 访问此服务。镜像、Compose 配置和备份不包含客户端私钥。

### 配置与持久化

`deploy/private/service.env` 保存 GitHub 配置：

```dotenv
GITHUB_TOKEN=<server-only-token>
GITHUB_REPOSITORY=wess09/AzurPilot-for-Android
```

`deploy/private/client-cert.pem` 是正文签名验证使用的公开证书，沿用源站配置。
这是 HTTP 请求正文签名校验，与管理员在 Cloudflare 边缘配置的 TLS 验证分别执行。
若替换 App 的正文签名密钥，必须同步替换此公开证书。

`/srv/agentdock/AgentDock/afa-api-state/state.json` 保存持久化去重状态，更新镜像和重新创建容器时保留。
状态目录位于构建上下文之外，默认路径为相对于 `deploy/` 的 `../../afa-api-state`。
可通过 `AFA_API_STATE_DIR` 指定其他绝对路径。
私有配置、状态和备份目录均被 Git 忽略，也被 Docker 构建上下文排除。
只运行单个 API 实例；状态文件不支持多进程同时写入。

### 首次部署与迁移

将 `server/device-report/` 中的源码、Dockerfile 和 `deploy/` 上传到生产目录。
从源站备份 `/etc/azurpilot-device-report/service.env`、
`/etc/azurpilot-device-report/client-cert.pem` 和
`/var/lib/azurpilot-device-report/state.json`，分别放入新目录：

```sh
cd /srv/agentdock/AgentDock/afa-api
mkdir -p deploy/private ../afa-api-state deploy/backups
chmod 700 deploy/private ../afa-api-state deploy/backups
# 源站文件分别放到 deploy/private/ 和 ../afa-api-state/ 后设置权限。
chown 65532:65532 ../afa-api-state ../afa-api-state/state.json
chmod 600 deploy/private/service.env ../afa-api-state/state.json
chmod 644 deploy/private/client-cert.pem
docker compose -f deploy/compose.yaml config --quiet
docker compose -f deploy/compose.yaml up -d --build --wait
curl --fail http://127.0.0.1:18882/healthz
```

`chown` 需要管理员权限。`service.env` 归运行 Compose 的宿主用户所有，供 Compose 读取，
不挂载到容器。镜像固定基础镜像摘要，构建时执行 Go 单元测试。
根文件系统只读，禁用 Linux capabilities，持久化目录单独挂载。
`unless-stopped` 在 Docker 服务恢复后自动启动 API；主机的 Docker 服务须启用开机启动。

本次迁移备份包含 41 条源站记录，备份归档为 `deploy/backups/source-20261028.tar.gz`。
旧服务器仍保留原服务。域名切换前的新增反馈须再次同步，或在切换时停止旧入口写入；
不要用旧快照覆盖新服务已经写入的状态。

### 日常操作与回滚

```sh
cd /srv/agentdock/AgentDock/afa-api
docker compose -f deploy/compose.yaml ps
docker compose -f deploy/compose.yaml logs --tail=50 api
docker compose -f deploy/compose.yaml restart api
```

更新前保存当前镜像 ID，并在停止容器后备份 `../afa-api-state/` 和 `deploy/private/`。
然后执行 `docker compose -f deploy/compose.yaml up -d --build --wait`。
应用回滚可通过 `AFA_API_IMAGE=<previous-image-id>` 配合
`docker compose -f deploy/compose.yaml up -d --no-build --wait` 使用旧镜像。
回滚默认保留当前状态，不恢复旧快照。`docker compose down` 也不会删除绑定挂载的状态。

容器健康检查只读取本机 API，不向 GitHub 创建测试评论。
运行中的 API 只校验 HTTP 正文签名，公网 mTLS 应在边缘验收。

本次已验证 Go 测试与 vet、Kotlin 编译、本机接口的鉴权与字段校验、重放拒绝、
请求体限制和容器重新创建后的状态完整性。GitHub 凭据通过只读请求检查，未发布测试评论。

## English

### Runtime

The API runs at `/srv/agentdock/AgentDock/afa-api` on the Tencent Cloud host.
Use `deploy/compose.yaml`, project `afa-api`, service `api`.
This deployment covers Docker only; the administrator configures DNS, reverse proxy,
HTTPS, and Cloudflare edge mTLS.

The public submission URL is `https://afa-api.azurpilot.com/v1/device-reports`.
Point a reverse proxy on the same host at `http://127.0.0.1:18882`.
`GET /healthz` returns `{"status":"ok"}`. Requests are limited to 8192 bytes;
allow at least 40 seconds for upstream reads.

Linux host networking retains the application's loopback-only listener. There is no port mapping.
A proxy in a separate bridge network cannot reach this service through its own loopback address.
The container uses UID/GID `65532:65532`, a read-only root filesystem, no Linux capabilities,
96 MiB of memory, one CPU, and a 48 MiB Go memory target.

### Configuration and migration

Copy the source service's `/etc/azurpilot-device-report/service.env` and `client-cert.pem`
into `deploy/private/`, and `/var/lib/azurpilot-device-report/state.json` into `../afa-api-state/`.
The environment file contains `GITHUB_TOKEN` and
`GITHUB_REPOSITORY=wess09/AzurPilot-for-Android`.
Keep the environment file at mode 0600, owned by the host user running Compose; it is not mounted.
The state directory must be owned by UID/GID 65532,
at mode 0700, with `state.json` at mode 0600. The public certificate may use mode 0644.
State lives outside the build context at `/srv/agentdock/AgentDock/afa-api-state/`, defaulting to
`../../afa-api-state` relative to `deploy/`. Override it with `AFA_API_STATE_DIR` if needed.

The imported public certificate verifies HTTP body signatures, independently of TLS validation
at the Cloudflare edge. Replacing the App's body signing key also requires replacing this certificate.
No client private key is included in the image, deployment configuration, or migration backup.
Private configuration, state, and backups are excluded from Git and Docker build context.
Run one API instance because the JSON state file does not support concurrent writers.

Build and start with `docker compose -f deploy/compose.yaml up -d --build --wait`
from the production directory. Verify `curl --fail http://127.0.0.1:18882/healthz`.
Base images are pinned by digest and the build runs Go tests.
The `unless-stopped` policy restarts the service when Docker starts; enable Docker on host boot.

The migration snapshot includes 41 source records in `deploy/backups/source-20261028.tar.gz`.
The old service remains available. Synchronize any later source writes at cutover, or stop writes
to the old entry then. Never overwrite newer state with the original snapshot.

### Operations and rollback

Use `docker compose -f deploy/compose.yaml ps`, `logs --tail=50 api`, and `restart api`.
Before upgrading, record the image ID, stop the container, and back up `../afa-api-state/` and
`deploy/private/`. Upgrade with `up -d --build --wait`.
To roll back the application, set `AFA_API_IMAGE=<previous-image-id>` and run
`docker compose -f deploy/compose.yaml up -d --no-build --wait`.
Retain current state unless a separate data restoration is intended. Bind-mounted data survives `down`.
Health checks read the local API without creating GitHub comments. Public mTLS is verified at the edge.
Validation covered Go tests/vet, Kotlin compilation, local API authentication and schema checks,
replay rejection, body limits, and unchanged state after container recreation.
GitHub credentials were checked through a read-only request without publishing test comments.
