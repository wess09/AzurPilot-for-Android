#!/usr/bin/env bash
# 构建 AzurPilot for Android rootfs（CI 用：rootfs.yml 的 build job 调用，也可本地手动执行）。
# 目标架构由 AZURPILOT_ABI 决定：
#   arm64-v8a（默认）→ Ubuntu arm64 base，需原生 ARM64 runner（ubuntu-24.04-arm）
#   x86_64           → Ubuntu amd64 base，需原生 x86_64 runner（ubuntu-24.04）
# proot 不做指令翻译，rootfs 与设备 ABI 必须一一对应；CI 按矩阵各出一份并分别发布。
# 环境变量（均有默认值）：AZURPILOT_ABI / AZURPILOT_REF / AZURPILOT_REPO /
#   UBUNTU_BASE / WORK_DIR / DIST_DIR / ROOTFS_EXECUTOR（chroot 默认，或 proot）；
#   需要 uv；Node.js/npm 内置于 guest；chroot 需要 root，proot 不需要挂载权限。
#
# Builds the AzurPilot for Android rootfs. Invoked by the build job of the rootfs.yml
# CI workflow; can also be run manually. The target architecture comes from
# AZURPILOT_ABI:
#   arm64-v8a (default) -> Ubuntu arm64 base, needs a native ARM64 runner
#   (ubuntu-24.04-arm); x86_64 -> Ubuntu amd64 base, needs a native x86_64 runner
#   (ubuntu-24.04). PRoot does no instruction translation, so the rootfs ABI must
#   match the device exactly; CI builds and publishes one artifact per matrix
#   entry. Environment (all defaulted): AZURPILOT_ABI / AZURPILOT_REF /
#   AZURPILOT_REPO / UBUNTU_BASE / WORK_DIR / DIST_DIR / ROOTFS_EXECUTOR (chroot
#   by default, or proot). Requires uv; chroot requires root (re-execs
#   through sudo), while proot requires no mount privileges. Node.js/npm are bundled in the guest.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK_DIR="${WORK_DIR:-$REPO_ROOT/.tmp/azurpilot-build}"
ROOTFS_DIR="$WORK_DIR/rootfs"
DIST_DIR="${DIST_DIR:-$REPO_ROOT/dist}"
SOURCE_REPO="${AZURPILOT_REPO:-https://github.com/wess09/AzurPilot.git}"
SOURCE_BRANCH="${AZURPILOT_BRANCH:-dev}"
NODE_VERSION=24.21.0
# 上游源码固定到具体提交，保证产物可复现；CI 会用 AZURPILOT_REF 覆盖为最新解析结果。
SOURCE_REF="${AZURPILOT_REF:-1841cb1941751a81ab70668b4d2383c369c4506e}"
TARGET_ABI="${AZURPILOT_ABI:-arm64-v8a}"
ROOTFS_EXECUTOR="${ROOTFS_EXECUTOR:-chroot}"
case "$ROOTFS_EXECUTOR" in
    chroot|proot) ;;
    *) echo "Unsupported rootfs executor: $ROOTFS_EXECUTOR" >&2; exit 1 ;;
esac

case "$TARGET_ABI" in
    arm64-v8a) UBUNTU_ARCH=arm64; HOST_ARCH=aarch64; NODE_ARCH=arm64
        NODE_SHA256=6ad1325edbdb5649c379b75a237147a666c95d4f9ae8d340fef2d1575d289ad2 ;;
    x86_64) UBUNTU_ARCH=amd64; HOST_ARCH=x86_64; NODE_ARCH=x64
        NODE_SHA256=fd8e59d5a511510f6a298afb548f18c7d2b1be404d8b4a27d94fbe49f56cb2d6 ;;
    *) echo "AZURPILOT_ABI 仅支持 arm64-v8a / x86_64，收到：$TARGET_ABI" >&2; exit 1 ;;
esac
BASE_URL="${UBUNTU_BASE:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-$UBUNTU_ARCH.tar.gz}"

if [[ $ROOTFS_EXECUTOR == chroot && $(id -u) -ne 0 ]]; then
    # setup-node/setup-uv 在 runner 的工具目录注入 PATH；sudo 默认 secure_path 会丢掉它们。
    exec sudo -E env "PATH=$PATH" "AZURPILOT_ABI=$TARGET_ABI" bash "$0" "$@"
fi
[[ $(uname -m) == "$HOST_ARCH" ]] || { echo "构建 $TARGET_ABI rootfs 需要原生 $HOST_ARCH runner，当前 $(uname -m)" >&2; exit 1; }
if ! command -v uv >/dev/null; then
    echo '构建环境需要 uv' >&2; exit 1;
fi
if ! command -v strip >/dev/null || ! command -v readelf >/dev/null; then
    echo '构建环境需要 binutils（strip / readelf）' >&2; exit 1;
fi
if [[ $ROOTFS_EXECUTOR == proot ]] && ! command -v proot >/dev/null; then
    echo 'PRoot executor requires proot in PATH' >&2; exit 1;
fi

mkdir -p "$WORK_DIR" "$DIST_DIR"
BASE_ARCHIVE="$WORK_DIR/ubuntu-base.tar.gz"
if [[ ! -s $BASE_ARCHIVE ]]; then
    curl -fL --retry 3 "$BASE_URL" -o "$BASE_ARCHIVE"
fi
rm -rf -- "${ROOTFS_DIR:?}/"
mkdir -p "$ROOTFS_DIR"
if [[ $ROOTFS_EXECUTOR == proot ]]; then
    # /dev 由 PRoot 绑定提供，跳过设备节点可让普通用户解压 Ubuntu base。
    tar -xzf "$BASE_ARCHIVE" -C "$ROOTFS_DIR" --exclude='./dev/*' --exclude='dev/*'
else
    tar -xzf "$BASE_ARCHIVE" -C "$ROOTFS_DIR"
fi
# shellcheck source=rootfs/build/guest-env.sh
source "$REPO_ROOT/rootfs/build/guest-env.sh"
trap cleanup_guest EXIT
setup_guest
echo "Rootfs guest executor: $ROOTFS_EXECUTOR ($TARGET_ABI)"

guest apt-get -o APT::Update::Error-Mode=any update
# 远程访问/模拟器隧道走上游 module/base/ssh.py，直接 Popen 系统 ssh（无捆绑二进制）
guest apt-get install -y --no-install-recommends \
    ca-certificates curl git xz-utils libglib2.0-0t64 libgomp1 libgl1 \
    libstdc++6 libatomic1 libsm6 libxext6 libsndfile1 libvulkan1 python3 \
    openssh-client
# apt 缓存只服务安装；留在镜像里会把已安装的包再打包一份。
guest apt-get clean
rm -f "$ROOTFS_DIR/var/cache/apt/pkgcache.bin" "$ROOTFS_DIR/var/cache/apt/srcpkgcache.bin"
# 使用按 ABI 固定并校验的官方 Node 分发包，构建和设备端热更共用同一工具链。
NODE_ARCHIVE="$WORK_DIR/node-v${NODE_VERSION}-linux-${NODE_ARCH}.tar.xz"
if [[ ! -s $NODE_ARCHIVE ]] || ! echo "$NODE_SHA256  $NODE_ARCHIVE" | sha256sum -c --status; then
    curl -fL --retry 3 "https://nodejs.org/dist/v${NODE_VERSION}/$(basename "$NODE_ARCHIVE")" -o "$NODE_ARCHIVE.part"
    echo "$NODE_SHA256  $NODE_ARCHIVE.part" | sha256sum -c -
    mv "$NODE_ARCHIVE.part" "$NODE_ARCHIVE"
fi
echo "$NODE_SHA256  $NODE_ARCHIVE" | sha256sum -c -
tar -xJf "$NODE_ARCHIVE" -C "$ROOTFS_DIR/usr/local" --strip-components=1
guest node --version
guest npm --version
guest git --version
mkdir -p "$ROOTFS_DIR/opt/update-verification/tests" "$ROOTFS_DIR/opt/update-verification/overlays"
cp "$REPO_ROOT/rootfs/tests/test_android_update.py" "$ROOTFS_DIR/opt/update-verification/tests/"
cp "$REPO_ROOT/rootfs/overlays/android_update.py" "$ROOTFS_DIR/opt/update-verification/overlays/"
cp "$REPO_ROOT/rootfs/overlays/android_host.py" "$ROOTFS_DIR/opt/update-verification/overlays/"
guest python3 /opt/update-verification/tests/test_android_update.py
rm -rf "$ROOTFS_DIR/opt/update-verification"
# uv 是静态链接单文件，直接从 runner 复制进 rootfs，无需在 guest 内再安装一遍。
cp -L "$(command -v uv)" "$ROOTFS_DIR/usr/local/bin/uv"
guest uv python install 3.14.6

mkdir -p "$ROOTFS_DIR/opt/azurpilot"
git -C "$ROOTFS_DIR/opt/azurpilot" init -q
git -C "$ROOTFS_DIR/opt/azurpilot" remote add origin "$SOURCE_REPO"
git -C "$ROOTFS_DIR/opt/azurpilot" fetch --depth 1 origin "$SOURCE_REF"
git -C "$ROOTFS_DIR/opt/azurpilot" checkout -q --detach FETCH_HEAD
SOURCE_COMMIT="$(git -C "$ROOTFS_DIR/opt/azurpilot" rev-parse HEAD)"
install -m 0644 "$REPO_ROOT/rootfs/seeds/deploy-azurpilot.yaml" \
    "$ROOTFS_DIR/opt/azurpilot/config/deploy.yaml"
install -m 0755 "$REPO_ROOT/rootfs/seeds/seed_azurpilot.py" \
    "$ROOTFS_DIR/opt/azurpilot/seed_azurpilot.py"
install -m 0755 "$REPO_ROOT/rootfs/overlays/android_host.py" \
    "$ROOTFS_DIR/opt/azurpilot/android_host.py"
install -m 0755 "$REPO_ROOT/rootfs/build/azurpilot-ocr-gate.py" \
    "$ROOTFS_DIR/opt/azurpilot/azurpilot-ocr-gate.py"

guest /bin/sh -c 'cd /opt/azurpilot && uv sync --frozen --no-dev --python 3.14.6'
VENV_SITE_PACKAGES="$(find "$ROOTFS_DIR/opt/azurpilot/.venv/lib" -maxdepth 2 -type d -name site-packages -print -quit)"
[[ -n "$VENV_SITE_PACKAGES" ]] || { echo 'AzurPilot venv site-packages missing' >&2; exit 1; }
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_process_compat.py" "$VENV_SITE_PACKAGES/android_process_compat.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/sitecustomize.py" "$VENV_SITE_PACKAGES/sitecustomize.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_ocr.py" "$VENV_SITE_PACKAGES/android_ocr.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_equipment.py" "$VENV_SITE_PACKAGES/android_equipment.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_capture.py" "$VENV_SITE_PACKAGES/android_capture.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_opsi_event.py" "$VENV_SITE_PACKAGES/android_opsi_event.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_ntp.py" "$VENV_SITE_PACKAGES/android_ntp.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_app_launch.py" "$VENV_SITE_PACKAGES/android_app_launch.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_device.py" "$VENV_SITE_PACKAGES/android_device.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_update.py" "$VENV_SITE_PACKAGES/android_update.py"
install -m 0644 "$REPO_ROOT/rootfs/overlays/android_update.py" "$ROOTFS_DIR/opt/azurpilot/android_update.py"
guest /bin/sh -c 'cd /opt/azurpilot && .venv/bin/python -m module.config.config_updater'

FRONTEND="$ROOTFS_DIR/opt/azurpilot/frontend"
# npm 缓存同样外移宿主目录，随 uv-cache 一起交给 actions/cache 跨构建复用。
guest /bin/sh -c 'cd /opt/azurpilot/frontend && npm ci --cache /opt/npm-cache --no-audit --no-fund && npm run build'
# 复用上游 deploy/frontend.py 自己的指纹逻辑计算前端源码指纹，随 dist 打包，
# 避免在本仓另写一份会与上游漂移的实现。
AZURPILOT_SOURCE="$ROOTFS_DIR/opt/azurpilot" python3 - <<'PY'
import importlib.util, os, pathlib
root = pathlib.Path(os.environ['AZURPILOT_SOURCE'])
spec = importlib.util.spec_from_file_location('azurpilot_frontend', root / 'deploy/frontend.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
(root / 'frontend/dist/.source-fingerprint').write_text(mod.source_fingerprint(root / 'frontend') + '\n')
PY
rm -rf "$FRONTEND/node_modules"

# 第三方 wheel 的调试信息不参与推理；保持符号及加载段页对齐，并在裁剪后真实导入和计算。
python3 "$REPO_ROOT/rootfs/build/compact-runtime.py" "$ROOTFS_DIR" \
    --report "$ROOTFS_DIR/opt/azurpilot/RUNTIME_SIZE_REPORT.json"

guest /bin/sh -c 'cd /opt/azurpilot && AZURPILOT_ANDROID=1 .venv/bin/python -c "import module.config.time_source as clock; assert clock._android_ntp_adapter; print(\"NTP_ADAPTER_OK\")"'

# 冒烟验证：真实导入重依赖，并确认进程枚举与装备码已接到 Android 兼容层。
guest /bin/sh -c 'cd /opt/azurpilot && AZURPILOT_ANDROID=1 .venv/bin/python -c "import module.device.method.azurpilot_android as device; assert device.AzurPilotAndroid._android_launch_adapter; print(\"LAUNCH_ADAPTER_OK\")"'
guest /bin/sh -c 'cd /opt/azurpilot && AZURPILOT_ANDROID=1 .venv/bin/python -c "import module.device.method.azurpilot_android as device; assert device.AzurPilotAndroid._android_capture_adapter; print(\"CAPTURE_ADAPTER_OK\")"'

guest /bin/sh -c 'cd /opt/azurpilot && AZURPILOT_ANDROID=1 .venv/bin/python -c "import module.os.fleet as fleet; import module.os_shop.shop as shop; assert fleet._android_opsi_event_adapter; assert shop._android_opsi_event_adapter; print(\"OPSI_EVENT_ADAPTER_OK\")"'
guest /bin/sh -c 'cd /opt/azurpilot && AZURPILOT_ANDROID=1 .venv/bin/python -c "import cv2,numpy,scipy,onnxruntime,rapidocr,ncnn,psutil,numba,uvloop,av; import module.api.app, module.device.device, module.ocr.al_ocr; import module.equipment.equipment_code as equipment; assert equipment.EquipmentCodeHandler._android_equipment_adapter; import module.device.method.azurpilot_android as device; assert device.AzurPilotAndroid._android_display_adapter; assert numba.njit(lambda x: x + 1)(2) == 3; assert scipy.linalg.norm(numpy.array([3.,4.])) == 5.; loop = uvloop.new_event_loop(); loop.close(); assert psutil.Process.children.__module__ == \"android_process_compat\"; print(\"IMPORTS_OK\")"'
guest /bin/sh -c 'cd /opt/azurpilot && .venv/bin/python azurpilot-ocr-gate.py'
# 原模型只用于上述构建对照；发布镜像用宿主身份文件，字典与配置保留。
python3 "$REPO_ROOT/rootfs/build/compact-ocr-models.py" "$ROOTFS_DIR" \
    "$REPO_ROOT/app/app/src/main/assets/ocr/manifest.json"
rm -f "$ROOTFS_DIR/opt/azurpilot/azurpilot-ocr-gate.py"
mkdir -p "$ROOTFS_DIR/opt/azurpilot/log"

# 生成 BUILD_MANIFEST：记录上游提交、ABI 与关键产物哈希；rootfs_version 由上游提交
# 与 rootfs 输入内容共同决定，供 app 端 RootfsProvisioner 识别运行时版本。
SOURCE_COMMIT="$SOURCE_COMMIT" SOURCE_REPO="$SOURCE_REPO" SOURCE_BRANCH="$SOURCE_BRANCH" \
    NODE_VERSION="$NODE_VERSION" REPO_ROOT="$REPO_ROOT" \
    TARGET_ABI="$TARGET_ABI" \
    ROOTFS_DIR="$ROOTFS_DIR" python3 - <<'PY'
import datetime, hashlib, json, os, pathlib, subprocess
root = pathlib.Path(os.environ['ROOTFS_DIR']) / 'opt/azurpilot'
host_commit = subprocess.check_output(['git', '-C', os.environ['REPO_ROOT'], 'rev-parse', 'HEAD'], text=True).strip()
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
repo_root = pathlib.Path(os.environ['REPO_ROOT'])
inputs = repo_root / 'rootfs'
content_hash = hashlib.sha256()
tracked = subprocess.check_output(
    ['git', 'ls-files', '-z', '--', 'rootfs/build', 'rootfs/overlays', 'rootfs/seeds'],
    cwd=repo_root,
)
for name in filter(None, tracked.split(b'\0')):
    path = repo_root / os.fsdecode(name)
    content_hash.update(str(path.relative_to(inputs)).encode())
    content_hash.update(path.read_bytes())
content_hash.update((repo_root / 'app/app/src/main/assets/ocr/manifest.json').read_bytes())
manifest = {
    'rootfs_version': os.environ['SOURCE_COMMIT'][:12] + '-' + content_hash.hexdigest()[:10],
    'runtime': 'azurpilot-android',
    'android_host_commit': host_commit,
    'azurpilot_repo': os.environ['SOURCE_REPO'],
    'azurpilot_update_repo': ('https://cnb.cool/AzurPilot/AzurPilot.git'
                            if os.environ['SOURCE_REPO'].rstrip('/').removesuffix('.git') ==
                            'https://github.com/wess09/AzurPilot' else os.environ['SOURCE_REPO']),
    'azurpilot_branch': os.environ['SOURCE_BRANCH'],
    'azurpilot_commit': os.environ['SOURCE_COMMIT'],
    'rootfs_arch': os.environ['TARGET_ABI'],
    'android_api_version': 1,
    'ocr_host_model_format': 2,
    'ocr_compaction': json.loads((root / 'OCR_SIZE_REPORT.json').read_text()),
    'uv_lock_sha256': sha(root / 'uv.lock'),
    'frontend_sha256': sha(root / 'frontend/dist/index.html'),
    'python_version': '3.14.6',
    'node_version': os.environ['NODE_VERSION'],
    'hot_update_mode': 'local',
    'runtime_compaction': json.loads((root / 'RUNTIME_SIZE_REPORT.json').read_text()),
    'built_at_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
(root / 'BUILD_MANIFEST').write_text(json.dumps(manifest, indent=2) + '\n')
PY
cp "$ROOTFS_DIR/opt/azurpilot/BUILD_MANIFEST" "$DIST_DIR/BUILD_MANIFEST"
guest /bin/sh -c 'cd /opt/azurpilot && AZURPILOT_ANDROID=1 .venv/bin/python -c "import android_update; import module.api.android_update as updates; from deploy.frontend import ensure_frontend; assert android_update.tools_ready(\".\"); assert updates.AndroidUpdateService.__module__ == \"android_update\"; ensure_frontend(); print(\"LOCAL_UPDATE_OK\")"'

# 旧镜像升级前仍消费预构建前端；兼容资产继续发布，新镜像热更不依赖它。
tar -cJf "$DIST_DIR/frontend-${SOURCE_COMMIT}.tar.xz" -C "$ROOTFS_DIR/opt/azurpilot/frontend" dist
( cd "$DIST_DIR" && sha256sum "frontend-${SOURCE_COMMIT}.tar.xz" > "frontend-${SOURCE_COMMIT}.tar.xz.sha256" )

# Python 依赖位于版本目录外，源码更新时继续复用已验证的锁定环境。
mv "$ROOTFS_DIR/opt/azurpilot/.venv" "$ROOTFS_DIR/opt/azurpilot-venv"
ln -s ../azurpilot-venv "$ROOTFS_DIR/opt/azurpilot/.venv"

cleanup_guest
for d in dev dev/pts proc sys etc/resolv.conf opt/uv-cache opt/npm-cache; do
    if mountpoint -q "$ROOTFS_DIR/$d"; then echo "挂载未清理: $d" >&2; exit 1; fi
done
# 缓存是 root 写的，这里交还 runner 用户：post-step 的 actions/cache 以 runner 身份打包上传
if [[ -n ${SUDO_UID:-} ]]; then
    chown -R "$SUDO_UID:${SUDO_GID:-$SUDO_UID}" \
        "$WORK_DIR/uv-cache" "$WORK_DIR/npm-cache" "$WORK_DIR/ubuntu-base.tar.gz" ||
        echo '缓存目录 chown 失败（不影响构建，只可能影响缓存保存）' >&2
fi
# opt/uv-cache 是宿主缓存挂进来的，此刻已卸挂，下面删掉的只是空挂载点
rm -rf "$ROOTFS_DIR/opt/uv-cache" "$ROOTFS_DIR/opt/npm-cache" "$ROOTFS_DIR/root/.cache" "$ROOTFS_DIR/var/lib/apt/lists"/*
# 浅克隆保留旧提交对象，热更失败或进程被杀时才能离线回滚。
find "$ROOTFS_DIR" -type d -name __pycache__ -prune -exec rm -rf {} +
# -T0 多线程压缩缩短 CI 时长；--one-file-system 防止把残留挂载误打进镜像。
XZ_OPT=-T0 tar --one-file-system -C "$ROOTFS_DIR" -cJf "$DIST_DIR/rootfs.tar.xz" .
sha256sum "$DIST_DIR/rootfs.tar.xz"
