"""将 GitHub 已校验的发布产物分发到 CNB，不重新编译或签名。

Mirrors verified GitHub release artifacts to CNB without rebuilding or re-signing.
"""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, unquote, urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


ABIS = ("arm64-v8a", "x86_64")
CNB_REPOSITORY = "azurpilot/AzurPilot-for-Android"
API_ENDPOINT = "https://api.cnb.cool"
WEB_ENDPOINT = "https://cnb.cool"
RELEASE_TAG = "azurpilot-android-latest"
SHA = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
FRONTEND = re.compile(r"frontend-[0-9a-f]{40}\.tar\.xz")
VERSIONED_APK = re.compile(
    r"AzurPilot-Android-\d+\.\d+\.\d+-((?:(?:arm64-v8a|x86_64)-)?(?:full|update))-[0-9a-f]{16}\.apk"
)
VERSIONED_RUNTIME = re.compile(r"(rootfs-(?:arm64-v8a|x86_64))-[0-9a-f]{16}\.tar\.xz")
LEGACY_APK = re.compile(r"AzurPilot-Android-(\d+\.\d+\.\d+)-.+\.apk")
UPLOAD_WORKERS = 3
UPLOAD_ATTEMPT_SECONDS = 90 * 60
UPLOAD_SOCKET_SECONDS = 120


def digest(path: Path, algorithm: str = "sha256") -> str:
    """流式校验 GB 级产物。

    Checks large artifacts without loading them into memory.
    """
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def asset_entries(manifest: dict) -> list[tuple[dict, str]]:
    """要求正式 APK 与双架构 Runtime 齐全，兼容尚未提供按架构更新包的旧发布。

    Requires signed APKs and both runtime architectures, supporting older releases without
    per-ABI update APKs.
    """
    version = manifest["versionName"]
    if not isinstance(version, str) or not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Invalid release version")
    if type(manifest.get("versionCode")) is not int or manifest["versionCode"] <= 0:
        raise ValueError("Invalid release version code")
    for key in ("appCommit", "androidHostCommit", "azurpilotCommit"):
        if not isinstance(manifest.get(key), str) or not SHA.fullmatch(manifest[key]):
            raise ValueError(f"Invalid release provenance: {key}")
    entries = [({"url": manifest["apkUrl"], "sha256": manifest["apkSha256"],
                 "size": manifest["apkSize"]}, f"AzurPilot-Android-{version}-update.apk")]
    if "updateApks" in manifest and (not isinstance(manifest["updateApks"], dict)
                                     or any(abi not in manifest["updateApks"] for abi in ABIS)):
        raise ValueError("Missing per-ABI update APK metadata")
    for abi in ABIS:
        entries.append((manifest["runtimes"][abi], f"rootfs-{abi}.tar.xz"))
        entries.append((manifest["fullApks"][abi], f"AzurPilot-Android-{version}-{abi}-full.apk"))
        if "updateApks" in manifest:
            entries.append((manifest["updateApks"][abi], f"AzurPilot-Android-{version}-{abi}-update.apk"))
    resolved = []
    for entry, legacy in entries:
        checksum = entry.get("sha256")
        if not isinstance(checksum, str) or not SHA256.fullmatch(checksum):
            raise ValueError(f"Invalid asset checksum: {legacy}")
        suffix = ".apk" if legacy.endswith(".apk") else ".tar.xz"
        immutable = f"{legacy.removesuffix(suffix)}-{checksum[:16]}{suffix}"
        name = unquote(entry["url"].rsplit("/", 1)[-1])
        if name not in (legacy, immutable):
            raise ValueError(f"Unexpected GitHub asset URL: {legacy}")
        resolved.append((entry, name))
    return resolved


def validate_source_urls(manifest: dict, github_repo: str, github_tag: str) -> None:
    """只接受当前 GitHub Release 的约定资产，阻止路径被清单改写。

    Accepts only expected assets from the specified GitHub release, preventing path substitution.
    """
    base = f"https://github.com/{github_repo}/releases/download/{quote(github_tag, safe='')}/"
    for entry, filename in asset_entries(manifest):
        if unquote(entry["url"]) != unquote(base + quote(filename, safe="")):
            raise ValueError(f"Unexpected GitHub asset URL: {filename}")
        if type(entry.get("size")) is not int or entry["size"] <= 0:
            raise ValueError(f"Invalid asset size: {filename}")


def retained_payload_names(assets: list[dict], current: set[str]) -> set[str]:
    """保留当前下载载荷、每种带哈希附件的最近两份旧文件及最近三版旧式 APK。

    Keeps the current payloads, two recent hash-named predecessors per kind and three legacy APK versions.
    """
    retained = set(current)
    previous_by_kind = {}
    legacy_by_version = {}
    for asset in assets:
        name = asset["name"]
        match = VERSIONED_APK.fullmatch(name) or VERSIONED_RUNTIME.fullmatch(name)
        if match:
            if name not in current:
                previous_by_kind.setdefault(match[1], []).append(asset)
        elif match := LEGACY_APK.fullmatch(name):
            version = tuple(map(int, match[1].split(".")))
            legacy_by_version.setdefault(version, []).append(name)
        elif name in {f"rootfs-{abi}.tar.xz" for abi in ABIS}:
            retained.add(name)
    for older in previous_by_kind.values():
        latest = sorted(older, key=lambda asset: asset.get("created_at") or "", reverse=True)
        retained.update(asset["name"] for asset in latest[:2])
    for version in sorted(legacy_by_version, reverse=True)[:3]:
        retained.update(legacy_by_version[version])
    return retained


def download(source: Path, github_repo: str, github_tag: str) -> None:
    """用 gh 下载已有正式发布，供手动补传；不触发构建。

    Downloads an existing signed release through gh for manual backfills without triggering builds.
    """
    source.mkdir(parents=True, exist_ok=True)
    if any(source.iterdir()):
        raise ValueError("Source download directory must be empty")
    listing = json.loads(subprocess.check_output(
        ["gh", "release", "view", github_tag, "--repo", github_repo, "--json", "assets"], text=True))
    available = {asset["name"] for asset in listing["assets"]}

    def fetch(filename: str) -> None:
        subprocess.run(["gh", "release", "download", github_tag, "--repo", github_repo,
                        "--dir", str(source), "--pattern", filename], check=True)

    fetch("latest.json")
    manifest = json.loads((source / "latest.json").read_text(encoding="utf-8"))
    validate_source_urls(manifest, github_repo, github_tag)
    required = {filename for _, filename in asset_entries(manifest)}
    optional = {"BUILD_MANIFEST", *(f"BUILD_MANIFEST-{abi}.json" for abi in ABIS)}
    optional.update(name for name in available if FRONTEND.fullmatch(name)
                    or (name.endswith(".sha256") and FRONTEND.fullmatch(name[:-7])))
    for filename in sorted(required | (optional & available)):
        fetch(filename)


def prepare(source: Path, output: Path, github_repo: str, github_tag: str,
            cnb_repo: str, tag: str, expected_host: str = "") -> dict:
    """校验原始字节，只重写 CNB 下载清单；使用硬链接避免再复制多 GB 文件。

    Verifies original bytes and rewrites only the CNB download index, using hard links to avoid
    duplicating multi-GB files.
    """
    if not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+", cnb_repo):
        raise ValueError("Invalid CNB repository")
    if tag != RELEASE_TAG:
        raise ValueError("CNB publication must use the fixed latest tag")
    manifest = json.loads((source / "latest.json").read_text(encoding="utf-8"))
    validate_source_urls(manifest, github_repo, github_tag)
    if expected_host and manifest["androidHostCommit"] != expected_host:
        raise ValueError("Release does not belong to this GitHub build")
    files = {}
    for entry, filename in asset_entries(manifest):
        path = source / filename
        if path.is_symlink() or not path.is_file() or path.stat().st_size != entry["size"]:
            raise ValueError(f"Missing or incorrect artifact size: {filename}")
        if digest(path) != entry["sha256"]:
            raise ValueError(f"Artifact checksum mismatch: {filename}")
        files[filename] = path
    for abi in ABIS:
        filename = f"BUILD_MANIFEST-{abi}.json"
        path = source / filename
        if path.is_file():
            runtime = json.loads(path.read_text(encoding="utf-8"))
            if (runtime.get("rootfs_arch") != abi or
                    runtime.get("rootfs_version") != manifest["runtimes"][abi]["version"]):
                raise ValueError(f"Runtime manifest mismatch: {abi}")
            files[filename] = path
    # 旧 GitHub 发布只有一份 BUILD_MANIFEST，手动补传时保留它。
    if (source / "BUILD_MANIFEST").is_file():
        files["BUILD_MANIFEST"] = source / "BUILD_MANIFEST"
    for path in source.glob("frontend-*.tar.xz"):
        if not FRONTEND.fullmatch(path.name):
            continue
        checksum = source / f"{path.name}.sha256"
        words = checksum.read_text(encoding="utf-8").split()
        if not words or not SHA256.fullmatch(words[0]) or digest(path) != words[0]:
            raise ValueError(f"Frontend checksum mismatch: {path.name}")
        files[path.name], files[checksum.name] = path, checksum
    if output.exists() and any(output.iterdir()):
        raise ValueError("CNB release directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    for filename, path in files.items():
        target = output / filename
        try:
            os.link(path, target)
        except OSError:
            shutil.copyfile(path, target)
    cnb = copy.deepcopy(manifest)
    base = f"{WEB_ENDPOINT}/{cnb_repo}/-/releases/download/{tag}/"
    (_, apk_name), *nested = asset_entries(cnb)
    cnb["apkUrl"] = base + apk_name
    for entry, name in nested:
        entry["url"] = base + name
    arm64 = cnb["runtimes"]["arm64-v8a"]
    cnb.update(rootfsVersion=arm64["version"], rootfsUrl=arm64["url"],
               rootfsSha256=arm64["sha256"], rootfsSize=arm64["size"])
    (output / "latest.json").write_text(json.dumps(cnb, ensure_ascii=False, indent=2) + "\n",
                                       encoding="utf-8")
    (output / "SHA256SUMS").write_text(
        "".join(f"{digest(path)}  {path.name}\n" for path in sorted(output.iterdir())), encoding="utf-8")
    return cnb


class _NoRedirect(HTTPRedirectHandler):
    """不允许带访问令牌的 API 请求重定向。

    Prevents redirects for API requests carrying an access token.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class _UploadStream:
    """限制单次上传耗时，避免慢连接一直绕过 socket 超时。

    Bounds an upload attempt even when a slow connection keeps the socket active.
    """

    def __init__(self, stream):
        self.stream = stream
        self.deadline = time.monotonic() + UPLOAD_ATTEMPT_SECONDS

    def read(self, size=-1):
        if time.monotonic() >= self.deadline:
            raise TimeoutError("CNB upload exceeded its time budget")
        return self.stream.read(size)


class CnbClient:
    """按官方 Release API 流式上传，在全部校验后发布草稿。

    Streams uploads through the official release API and publishes drafts only after verification.
    """

    def __init__(self, token: str, repo: str, opener=None):
        if not token.strip():
            raise ValueError("Missing CNB_TOKEN; configure a token with repo-release:rw")
        self.token = token.strip()
        self.repo_base = f"{API_ENDPOINT}/{repo}/-"
        self.base = f"{API_ENDPOINT}/{repo}/-/releases"
        self.tags_base = f"{self.repo_base}/git/tags"
        self.opener = opener or build_opener(_NoRedirect())

    def _api_path(self, url: str) -> str:
        """接受 CNB 返回的仓库大小写规范化地址，仍限制主机、仓库和路径边界。

        Accepts CNB's canonical repository casing while enforcing host, repo, and path boundaries.
        """
        parsed, base = urlsplit(url), urlsplit(self.repo_base)
        suffix = parsed.path[len(base.path):]
        if (parsed.scheme != base.scheme or parsed.netloc.lower() != base.netloc.lower() or
                parsed.path[:len(base.path)].lower() != base.path.lower() or
                not any(suffix == path or suffix.startswith(path + "/")
                        for path in ("/releases", "/git/tags"))):
            raise ValueError("Unexpected CNB API URL")
        return suffix

    def request(self, method: str, url: str, data=None, missing_ok: bool = False):
        """API 令牌只发给同一仓库的 CNB API，错误日志不包含令牌或签名 URL。

        Sends the token only to this repository's CNB API; errors omit tokens and signed URLs.
        """
        self._api_path(url)
        payload = json.dumps(data).encode() if data is not None else None
        request = Request(url, data=payload, method=method, headers={
            "Authorization": f"Bearer {self.token}", "Accept": "application/json",
            "Content-Type": "application/json",
        })
        try:
            with self.opener.open(request, timeout=90) as response:
                body = response.read()
                result = json.loads(body) if body else None
                if isinstance(result, dict) and result.get("errcode", 0):
                    raise RuntimeError("CNB API rejected the request; check token permissions")
                return result
        except HTTPError as error:
            error.close()
            if missing_ok and error.code == 404:
                return None
            if error.code in (401, 403):
                raise RuntimeError("CNB access denied; CNB_TOKEN requires repo-release:rw") from None
            raise RuntimeError(f"CNB API request failed (HTTP {error.code})") from None
        except URLError:
            raise RuntimeError("CNB API connection failed") from None

    def upload(self, release_id: str, path: Path) -> None:
        """每次重试重新取得签名 URL，上传文件流后确认附件永久保留。

        Obtains a fresh signed URL per retry, streams the file, and confirms permanent retention.
        """
        for attempt in range(3):
            ticket = self.request("POST", f"{self.base}/{quote(release_id, safe='')}/asset-upload-url",
                                  {"asset_name": path.name, "size": path.stat().st_size,
                                   "overwrite": True, "ttl": 0})
            upload_url, verify_url = ticket["upload_url"], ticket["verify_url"]
            if urlsplit(upload_url).scheme != "https":
                raise ValueError("CNB upload URL must use HTTPS")
            verify_url = urljoin(API_ENDPOINT + "/", verify_url)
            if not self._api_path(verify_url).startswith(
                    f"/releases/{quote(release_id, safe='')}/asset-upload-confirmation/"):
                raise ValueError("Unexpected CNB upload confirmation URL")
            try:
                with path.open("rb") as stream:
                    # 签名地址自带授权，不能把 CNB_TOKEN 转发到对象存储。
                    request = Request(upload_url, data=_UploadStream(stream), method="PUT", headers={
                        "Content-Type": "application/octet-stream",
                        "Content-Length": str(path.stat().st_size),
                    })
                    with self.opener.open(request, timeout=UPLOAD_SOCKET_SECONDS) as response:
                        response.read(1024)
                self.request("POST", verify_url)
                return
            except (HTTPError, URLError, TimeoutError, ConnectionError, RuntimeError) as error:
                if isinstance(error, HTTPError):
                    error.close()
                if attempt == 2:
                    raise RuntimeError(f"CNB upload failed after 3 attempts: {path.name}") from None
                time.sleep(2 ** (attempt + 1))

    def verify(self, release: dict, output: Path, include_index: bool = True) -> None:
        """核对服务端附件大小及可用的哈希，不接受缺失或不完整的发布。

        Checks server-side asset sizes and available hashes, rejecting missing or incomplete releases.
        """
        assets = {asset["name"]: asset for asset in (release.get("assets") or [])}
        for path in output.iterdir():
            if not include_index and path.name == "latest.json":
                continue
            asset = assets.get(path.name)
            if not asset or asset.get("size") != path.stat().st_size:
                raise ValueError(f"CNB asset missing or incomplete: {path.name}")
            algorithm = (asset.get("hash_algo") or "").strip().lower().replace("-", "")
            if algorithm and asset.get("hash_value"):
                if algorithm not in {"sha256", "sha512", "sha1", "md5"}:
                    raise ValueError(f"Unsupported CNB checksum algorithm: {algorithm}")
                if digest(path, algorithm) != asset["hash_value"].lower():
                    raise ValueError(f"CNB asset checksum mismatch: {path.name}")

    def _list(self, base: str) -> list[dict]:
        """删除前获取完整分页快照，避免删除改变分页位置。

        Reads every page before deletion so mutations cannot shift pagination.
        """
        records = []
        page = 1
        while True:
            batch = self.request("GET", f"{base}?page={page}&page_size=100")
            if not isinstance(batch, list):
                raise ValueError("Invalid CNB list response")
            records.extend(batch)
            if len(batch) < 100:
                return records
            page += 1

    def prune(self, release: dict, output: Path, tag: str) -> None:
        """成功发布后只保留固定 Release/tag，附件保留当前产物及最近三版 APK。

        Keeps only the fixed release/tag after successful publication, retaining current assets
        and the three most recent APK versions.
        """
        releases = self._list(self.base)
        tags = self._list(self.tags_base)
        if not any(item.get("id") == release["id"] and item.get("tag_name") == tag
                   and not item.get("draft", False) for item in releases):
            raise ValueError("Published fixed CNB release is missing; refusing cleanup")
        for item in releases:
            if item["id"] != release["id"]:
                self.request("DELETE", f"{self.base}/{quote(item['id'], safe='')}", missing_ok=True)
                print(f"Removed obsolete CNB release: {item.get('tag_name', item['id'])}")
        for item in tags:
            if item["name"] != tag:
                self.request("DELETE", f"{self.tags_base}/{quote(item['name'], safe='')}", missing_ok=True)
                print(f"Removed obsolete CNB tag: {item['name']}")
        assets = release.get("assets") or []
        retained_files = retained_payload_names(assets, {path.name for path in output.iterdir()})
        for asset in assets:
            if asset["name"] in retained_files:
                continue
            self.request("DELETE", f"{self.base}/{quote(release['id'], safe='')}/assets/"
                         f"{quote(asset['id'], safe='')}", missing_ok=True)
            print(f"Removed obsolete CNB asset: {asset['name']}")

    def publish(self, output: Path, manifest: dict, tag: str) -> None:
        """更新唯一固定 Release，附件校验后最后提交清单，再清理旧 Release/tag。

        Updates the single fixed release, commits the index after verifying assets, and then
        removes obsolete releases and tags.
        """
        if tag != RELEASE_TAG:
            raise ValueError("CNB publication must use the fixed latest tag")
        metadata = {
            "name": f"AzurPilot for Android {manifest['versionName']}",
            "body": "GitHub Actions 构建并校验，同一批产物同步到 CNB。\n\n"
                    "Built and verified by GitHub Actions; the same artifacts are mirrored to CNB.\n\n"
                    f"- Android: `{manifest['androidHostCommit']}`\n"
                    f"- AzurPilot: `{manifest['azurpilotCommit']}`\n"
                    "- SHA-256: `SHA256SUMS`\n",
            "prerelease": False,
        }
        release = self.request("GET", f"{self.base}/tags/{quote(tag, safe='')}", missing_ok=True)
        if release is None:
            release = self.request("POST", self.base, {
                "tag_name": tag, "target_commitish": manifest["androidHostCommit"],
                **metadata, "draft": True, "make_latest": "false",
            })
        release_id = release["id"]
        existing = {asset["name"]: asset for asset in (release.get("assets") or [])}

        def upload_if_changed(path):
            asset = existing.get(path.name)
            if (asset and asset.get("size") == path.stat().st_size
                    and (asset.get("hash_algo") or "").lower().replace("-", "") == "sha256"
                    and (asset.get("hash_value") or "").lower() == digest(path)):
                return
            print(f"Upload {path.name} ({path.stat().st_size} bytes)", flush=True)
            self.upload(release_id, path)

        # 独立附件并发上传，避免单个 GB 级安装包拖住所有附件；清单仍最后提交。
        index = output / "latest.json"
        assets = sorted((path for path in output.iterdir() if path != index),
                        key=lambda path: path.stat().st_size, reverse=True)
        with ThreadPoolExecutor(max_workers=UPLOAD_WORKERS) as pool:
            for _ in pool.map(upload_if_changed, assets):
                pass
        release_url = f"{self.base}/{quote(release_id, safe='')}"
        self.verify(self.request("GET", release_url), output, include_index=False)
        upload_if_changed(index)
        complete = self.request("GET", release_url)
        self.verify(complete, output)
        self.request("PATCH", release_url,
                     {**metadata, "draft": False, "make_latest": "true"})
        published = self.request("GET", release_url)
        self.verify(published, output)
        self.prune(published, output, tag)


def sync_release_tag(token: str, repo: str, commit: str) -> None:
    """通过 Git 上传目标提交并更新固定 tag，避免源码同步被 skip-ci 或并发顺序漏掉。

    Uploads the target commit through Git and updates the fixed tag, independent of skip-ci
    markers and source-sync scheduling.
    """
    environment = os.environ.copy()
    count = int(environment.get("GIT_CONFIG_COUNT", "0"))
    credential = base64.b64encode(f"cnb:{token}".encode()).decode()
    environment.update({
        "GIT_TERMINAL_PROMPT": "0", "GIT_CONFIG_COUNT": str(count + 1),
        f"GIT_CONFIG_KEY_{count}": f"http.{WEB_ENDPOINT}/.extraheader",
        f"GIT_CONFIG_VALUE_{count}": f"Authorization: Basic {credential}",
    })
    subprocess.run(["git", "cat-file", "-e", f"{commit}^{{commit}}"], check=True)
    subprocess.run(["git", "push", "--force", f"{WEB_ENDPOINT}/{repo}.git",
                    f"{commit}:refs/tags/{RELEASE_TAG}"], env=environment, check=True)


def main() -> None:
    """执行自动发布或已有 GitHub Release 的补传。

    Runs automatic publication or backfills an existing GitHub release.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("download", "publish"))
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path(".tmp/cnb-release"))
    parser.add_argument("--github-repo", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--github-tag", default="azurpilot-android-latest")
    parser.add_argument("--repo", default=CNB_REPOSITORY)
    parser.add_argument("--tag", default=RELEASE_TAG, choices=(RELEASE_TAG,))
    parser.add_argument("--expected-host-commit", default="")
    args = parser.parse_args()
    if not args.github_repo:
        parser.error("--github-repo is required")
    if args.action == "download":
        download(args.source_dir, args.github_repo, args.github_tag)
        return
    client = CnbClient(os.environ.get("CNB_TOKEN", ""), args.repo)
    manifest = prepare(args.source_dir, args.output_dir, args.github_repo, args.github_tag,
                       args.repo, args.tag, args.expected_host_commit)
    sync_release_tag(client.token, args.repo, manifest["androidHostCommit"])
    client.publish(args.output_dir, manifest, args.tag)
    print(f"Published {WEB_ENDPOINT}/{args.repo}/-/releases/tag/{args.tag}")


if __name__ == "__main__":
    main()
