"""验证 GitHub 产物镜像的完整性、上传协议和草稿发布顺序。

Checks mirrored artifact integrity, the upload protocol, and draft-publication ordering.
"""

from contextlib import redirect_stdout
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
from threading import Barrier, Lock
import unittest
from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cnb_release as cnb


class FakeApi:
    """记录预签名上传和确认步骤，模拟 CNB 服务端附件。

    Records presigned uploads and confirmations while simulating server-side CNB assets.
    """

    def __init__(self):
        self.requests = []
        self.files = {}
        self.confirmed = set()
        self.tickets = {}
        self.ticket_lock = Lock()
        self.fail_upload = False
        self.corrupt_remote = False
        self.existing = None
        self.old_releases = []
        self.old_tags = []
        self.confirmation_repo = cnb.CNB_REPOSITORY
        self.null_empty_assets = False

    def open(self, request, timeout):
        method, url = request.get_method(), request.full_url
        self.requests.append((method, url, request))
        if method == "PUT":
            if self.fail_upload:
                raise URLError("temporary storage failure")
            assert request.get_header("Authorization") is None
            assert hasattr(request.data, "read")
            filename = url.rsplit("/", 1)[-1]
            data = request.data.read()
            assert len(data) == int(request.get_header("Content-length"))
            self.files[filename] = data
            return io.BytesIO(b"")
        assert request.get_header("Authorization") == "Bearer test-access-token"
        if method == "GET" and "/tags/" in url:
            if self.existing is None:
                raise HTTPError(url, 404, "missing", {}, None)
            result = {**self.existing, "assets": self.assets() or
                      (None if self.null_empty_assets else [])}
        elif method == "GET" and "?page=" in url:
            query = parse_qs(urlsplit(url).query)
            page, size = int(query["page"][0]), int(query["page_size"][0])
            records = ([self.existing] + self.old_releases if "/-/releases?" in url
                       else [{"name": cnb.RELEASE_TAG}] + self.old_tags)
            result = records[(page - 1) * size:page * size]
        elif method == "POST" and url == cnb.API_ENDPOINT + "/" + cnb.CNB_REPOSITORY + "/-/releases":
            body = json.loads(request.data)
            assert body["draft"] is True and body["make_latest"] == "false"
            result = {"id": "release-1", "draft": True, "tag_name": body["tag_name"]}
            self.existing = result
        elif method == "POST" and url.endswith("/asset-upload-url"):
            body = json.loads(request.data)
            assert body["overwrite"] is True and body["ttl"] == 0
            filename = body["asset_name"]
            with self.ticket_lock:
                ticket = f"ticket-{len(self.tickets)}"
                self.tickets[ticket] = filename
            result = {"upload_url": f"https://storage.example/{filename}",
                      "verify_url": (f"/{self.confirmation_repo}/-/releases/release-1/"
                                     f"asset-upload-confirmation/{ticket}/asset")}
        elif method == "POST" and "/asset-upload-confirmation/" in url:
            ticket = url.split("/asset-upload-confirmation/")[1].split("/")[0]
            filename = self.tickets[ticket]
            assert filename in self.files
            self.confirmed.add(filename)
            result = None
        elif method == "GET" and url.endswith("/release-1"):
            result = {**self.existing, "assets": self.assets()}
        elif method == "PATCH":
            body = json.loads(request.data)
            assert body["draft"] is False and body["make_latest"] == "true"
            assert body["name"].startswith("AzurPilot for Android ") and not body["prerelease"]
            assert self.confirmed == set(self.files)
            self.existing.update(body)
            result = None
        elif method == "DELETE" and "/assets/" in url:
            name = next(asset["name"] for asset in self.assets() if asset["id"] == url.rsplit('/', 1)[-1])
            self.files.pop(name)
            self.confirmed.discard(name)
            result = None
        elif method == "DELETE" and "/-/git/tags/" in url:
            self.old_tags = [item for item in self.old_tags if item["name"] != url.rsplit('/', 1)[-1]]
            result = None
        elif method == "DELETE":
            self.old_releases = [item for item in self.old_releases if item["id"] != url.rsplit('/', 1)[-1]]
            result = None
        else:
            raise AssertionError((method, url))
        return io.BytesIO(json.dumps(result).encode() if result is not None else b"")

    def assets(self):
        return [{"id": hashlib.sha256(name.encode()).hexdigest(),
                 "name": name, "size": len(data), "hash_algo": "SHA-256",
                 "hash_value": "0" * 64 if self.corrupt_remote else hashlib.sha256(data).hexdigest()}
                for name, data in self.files.items() if name in self.confirmed]


class CnbReleaseTest(unittest.TestCase):
    """保证附件字节不变，失败发布不会取代 Latest。

    Preserves artifact bytes and prevents failed publications from replacing Latest.
    """

    def setUp(self):
        self.enterContext(redirect_stdout(io.StringIO()))
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.source = Path(self.tmp.name) / "source"
        self.output = Path(self.tmp.name) / "release"
        self.source.mkdir()
        self.repo = "wess09/AzurPilot-for-Android"
        self.tag = cnb.RELEASE_TAG
        self.base = f"https://github.com/{self.repo}/releases/download/azurpilot-android-latest/"
        self.manifest = {"versionCode": 1900000000, "versionName": "1.3.0",
                         "appCommit": "a" * 40, "androidHostCommit": "b" * 40,
                         "azurpilotCommit": "c" * 40, "runtimes": {}, "fullApks": {}}

        def asset(filename):
            data = f"verified GitHub artifact: {filename}".encode()
            (self.source / filename).write_bytes(data)
            return {"url": self.base + filename, "sha256": hashlib.sha256(data).hexdigest(),
                    "size": len(data)}

        apk = asset("AzurPilot-Android-1.3.0-update.apk")
        self.manifest.update(apkUrl=apk["url"], apkSha256=apk["sha256"], apkSize=apk["size"])
        for abi in cnb.ABIS:
            self.manifest["runtimes"][abi] = {"version": "runtime-123", **asset(f"rootfs-{abi}.tar.xz")}
            self.manifest["fullApks"][abi] = asset(f"AzurPilot-Android-1.3.0-{abi}-full.apk")
            (self.source / f"BUILD_MANIFEST-{abi}.json").write_text(json.dumps({
                "rootfs_arch": abi, "rootfs_version": "runtime-123"}), encoding="utf-8")
        filename = f"frontend-{'c' * 40}.tar.xz"
        asset(filename)
        (self.source / f"{filename}.sha256").write_text(
            f"{cnb.digest(self.source / filename)}  {filename}\n", encoding="utf-8")
        self.write_manifest()

    def write_manifest(self):
        (self.source / "latest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def name_payloads_by_checksum(self):
        """模拟带哈希附件的 GitHub 发布。 / Simulates hash-named GitHub release assets."""
        for index, (entry, name) in enumerate(cnb.asset_entries(self.manifest)):
            suffix = ".apk" if name.endswith(".apk") else ".tar.xz"
            immutable = f"{name.removesuffix(suffix)}-{entry['sha256'][:16]}{suffix}"
            (self.source / name).rename(self.source / immutable)
            if index == 0:
                self.manifest["apkUrl"] = self.base + immutable
            else:
                entry["url"] = self.base + immutable
        self.write_manifest()

    def prepare(self):
        return cnb.prepare(self.source, self.output, self.repo, "azurpilot-android-latest",
                           cnb.CNB_REPOSITORY, self.tag, "b" * 40)

    def test_same_bytes_and_cnb_urls_with_legacy_compatibility(self):
        original = (self.source / "latest.json").read_bytes()
        mirror = self.prepare()
        self.assertEqual((self.source / "latest.json").read_bytes(), original)
        for entry, filename in cnb.asset_entries(mirror):
            self.assertTrue(entry["url"].startswith(f"https://cnb.cool/{cnb.CNB_REPOSITORY}/"))
            self.assertEqual((self.output / filename).read_bytes(), (self.source / filename).read_bytes())
        self.assertEqual(mirror["rootfsUrl"], mirror["runtimes"]["arm64-v8a"]["url"])
        self.assertEqual(mirror["rootfsSha256"], mirror["runtimes"]["arm64-v8a"]["sha256"])
        for line in (self.output / "SHA256SUMS").read_text().splitlines():
            checksum, filename = line.split("  ", 1)
            self.assertEqual(checksum, cnb.digest(self.output / filename))

    def test_corrupt_or_missing_artifact_fails_before_preparing_release(self):
        path = self.source / "AzurPilot-Android-1.3.0-update.apk"
        original = path.read_bytes()
        path.write_bytes(b"x" * len(original))
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.prepare()
        self.assertFalse(self.output.exists())
        path.unlink()
        with self.assertRaisesRegex(ValueError, "Missing"):
            self.prepare()

    def add_update_apks(self):
        """准备按架构的更新包夹具。 / Prepares per-ABI update APK fixtures."""
        self.manifest["updateApks"] = {}
        for abi in cnb.ABIS:
            filename = f"AzurPilot-Android-1.3.0-{abi}-update.apk"
            data = f"native update {abi}".encode()
            (self.source / filename).write_bytes(data)
            self.manifest["updateApks"][abi] = {"url": self.base + filename,
                "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
        self.write_manifest()

    def test_per_abi_updates_are_verified_and_mirrored(self):
        self.add_update_apks()
        mirror = self.prepare()
        for abi in cnb.ABIS:
            filename = f"AzurPilot-Android-1.3.0-{abi}-update.apk"
            self.assertEqual(mirror["updateApks"][abi]["url"],
                             f"https://cnb.cool/{cnb.CNB_REPOSITORY}/-/releases/download/{self.tag}/{filename}")
            self.assertEqual((self.output / filename).read_bytes(), (self.source / filename).read_bytes())
        api = FakeApi()
        cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api).publish(self.output, mirror, self.tag)
        for abi in cnb.ABIS:
            self.assertIn(f"AzurPilot-Android-1.3.0-{abi}-update.apk", api.confirmed)

    def test_missing_or_corrupt_per_abi_update_fails_before_upload(self):
        self.add_update_apks()
        path = self.source / "AzurPilot-Android-1.3.0-x86_64-update.apk"
        path.write_bytes(b"x" * path.stat().st_size)
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.prepare()
        self.assertFalse(self.output.exists())
        del self.manifest["updateApks"]["x86_64"]
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "per-ABI update APK metadata"):
            self.prepare()

    def test_wrong_source_url_or_build_commit_is_rejected(self):
        self.manifest["apkUrl"] = "https://example.com/credentials.txt"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Unexpected GitHub"):
            self.prepare()
        self.manifest["apkUrl"] = self.base + "AzurPilot-Android-1.3.0-update.apk"
        self.manifest["androidHostCommit"] = "d" * 40
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "this GitHub build"):
            self.prepare()

    def test_optional_frontend_checksum_and_runtime_manifest_are_verified(self):
        checksum = next(self.source.glob("frontend-*.sha256"))
        original = checksum.read_text()
        checksum.write_text("0" * 64)
        with self.assertRaisesRegex(ValueError, "Frontend"):
            self.prepare()
        checksum.write_text(original)
        path = self.source / "BUILD_MANIFEST-x86_64.json"
        path.write_text('{"rootfs_arch":"arm64-v8a","rootfs_version":"runtime-123"}')
        with self.assertRaisesRegex(ValueError, "manifest mismatch"):
            self.prepare()

    def test_existing_github_release_without_new_optional_metadata_can_be_backfilled(self):
        for path in list(self.source.glob("BUILD_MANIFEST-*.json")) + list(self.source.glob("frontend-*")):
            path.unlink()
        self.prepare()
        self.assertEqual(len(list(self.output.iterdir())), 7)

    def test_backfill_downloads_only_current_artifacts_and_available_metadata(self):
        target = Path(self.tmp.name) / "download"
        names = {path.name for path in self.source.iterdir()}
        names.update({"AzurPilot-Android-1.2.168-update.apk", "unexpected.txt"})
        fetched = []

        def fetch(args, check):
            self.assertTrue(check)
            filename = args[args.index("--pattern") + 1]
            fetched.append(filename)
            (target / filename).write_bytes((self.source / filename).read_bytes())

        listing = json.dumps({"assets": [{"name": name} for name in names]})
        with patch.object(cnb.subprocess, "check_output", return_value=listing), \
                patch.object(cnb.subprocess, "run", side_effect=fetch):
            cnb.download(target, self.repo, "azurpilot-android-latest")
        self.assertEqual(fetched[0], "latest.json")
        self.assertEqual(set(fetched), {path.name for path in self.source.iterdir()})
        with self.assertRaisesRegex(ValueError, "must be empty"):
            cnb.download(target, self.repo, "azurpilot-android-latest")

    def test_uploads_are_streamed_confirmed_and_checked_before_latest(self):
        mirror = self.prepare()
        api = FakeApi()
        client = cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api)
        client.publish(self.output, mirror, self.tag)
        methods = [method for method, _, _ in api.requests]
        self.assertIn("PATCH", methods)
        self.assertEqual(api.confirmed, {path.name for path in self.output.iterdir()})
        self.assertEqual(api.files["latest.json"], (self.output / "latest.json").read_bytes())

    def test_large_assets_upload_in_parallel_before_index(self):
        mirror = self.prepare()
        api = FakeApi()
        paths = sorted(self.output.iterdir(), key=lambda path: path.stat().st_size, reverse=True)
        parallel = {path.name for path in [path for path in paths if path.name != "latest.json"][:2]}
        barrier = Barrier(2)
        original_open = api.open

        def open_request(request, timeout):
            if request.get_method() == "PUT":
                name = request.full_url.rsplit("/", 1)[-1]
                if name in parallel:
                    barrier.wait(timeout=5)
                if name == "latest.json":
                    self.assertEqual(api.confirmed, {path.name for path in paths} - {name})
            return original_open(request, timeout)

        with patch.object(api, "open", side_effect=open_request):
            cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api).publish(
                self.output, mirror, self.tag)
        self.assertIn("latest.json", api.confirmed)

    def test_expired_transfer_retries_without_publishing_it(self):
        api = FakeApi()
        path = self.source / "latest.json"
        client = cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api)
        with patch.object(cnb, "UPLOAD_ATTEMPT_SECONDS", 0), patch.object(cnb.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "failed after 3 attempts"):
                client.upload("release-1", path)
        self.assertEqual(len(api.tickets), 3)
        self.assertFalse(api.confirmed)

    def test_failed_upload_or_remote_checksum_leaves_a_draft(self):
        mirror = self.prepare()
        for failure in ("upload", "checksum"):
            with self.subTest(failure=failure):
                api = FakeApi()
                api.fail_upload = failure == "upload"
                api.corrupt_remote = failure == "checksum"
                client = cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api)
                with patch.object(cnb.time, "sleep"), self.assertRaises((RuntimeError, ValueError)):
                    client.publish(self.output, mirror, self.tag)
                self.assertFalse(any(method == "PATCH" for method, _, _ in api.requests))

    def test_cnb_canonical_repository_casing_is_accepted_for_confirmations(self):
        mirror = self.prepare()
        api = FakeApi()
        api.confirmation_repo = "AzurPilot/AzurPilot-for-Android"
        cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api).publish(
            self.output, mirror, self.tag)
        self.assertEqual(api.confirmed, {path.name for path in self.output.iterdir()})
        self.assertTrue(any(method == "PATCH" for method, _, _ in api.requests))

    def test_fixed_release_rerun_reuses_assets_and_refreshes_metadata(self):
        mirror = self.prepare()
        api = FakeApi()
        api.files = {path.name: path.read_bytes() for path in self.output.iterdir()}
        api.confirmed = set(api.files)
        api.existing = {"id": "release-1", "tag_name": self.tag, "draft": False}
        cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api).publish(self.output, mirror, self.tag)
        self.assertFalse(any(method == "PUT" for method, _, _ in api.requests))
        self.assertEqual(api.existing["name"], "AzurPilot for Android 1.3.0")

    def test_public_fixed_release_can_replace_old_assets_and_commits_index_last(self):
        mirror = self.prepare()
        api = FakeApi()
        api.files = {path.name: path.read_bytes() for path in self.output.iterdir()}
        api.files["AzurPilot-Android-1.3.0-update.apk"] = b"previous APK"
        api.files["latest.json"] = b"{}"
        api.confirmed = set(api.files)
        api.existing = {"id": "release-1", "tag_name": self.tag, "draft": False}
        cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api).publish(
            self.output, mirror, self.tag)
        uploads = [url.rsplit('/', 1)[-1] for method, url, _ in api.requests if method == "PUT"]
        self.assertEqual(uploads, ["AzurPilot-Android-1.3.0-update.apk", "latest.json"])
        self.assertEqual(api.existing["id"], "release-1")
        self.assertEqual(api.files["latest.json"], (self.output / "latest.json").read_bytes())

    def test_interrupted_publish_keeps_the_previous_index_and_its_payloads(self):
        old_payloads = {name: (self.source / name).read_bytes()
                        for _, name in cnb.asset_entries(self.manifest)}
        old_index = (self.source / "latest.json").read_bytes()
        self.name_payloads_by_checksum()
        mirror = self.prepare()
        api = FakeApi()
        api.files = {**old_payloads, "latest.json": old_index}
        api.confirmed = set(api.files)
        api.existing = {"id": "release-1", "tag_name": self.tag, "draft": False}
        original_open = api.open

        def interrupted(request, timeout):
            if request.get_method() == "PUT" and "/rootfs-x86_64-" in request.full_url:
                raise URLError("upload interrupted")
            return original_open(request, timeout)

        client = cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api)
        with patch.object(api, "open", side_effect=interrupted), patch.object(cnb.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "CNB upload failed"):
                client.publish(self.output, mirror, self.tag)
        self.assertEqual(api.files["latest.json"], old_index)
        self.assertEqual({name: api.files[name] for name in old_payloads}, old_payloads)
        for (entry, name), (_, cnb_name) in zip(cnb.asset_entries(self.manifest), cnb.asset_entries(mirror)):
            self.assertEqual(name, cnb_name)
            self.assertIn(entry["sha256"][:16], name)
        client.publish(self.output, mirror, self.tag)
        self.assertEqual(api.files["latest.json"], (self.output / "latest.json").read_bytes())

    def test_checksum_named_asset_must_match_the_manifest(self):
        self.name_payloads_by_checksum()
        entry = self.manifest["runtimes"]["arm64-v8a"]
        entry["url"] = entry["url"].replace(entry["sha256"][:16], "0" * 16)
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "Unexpected GitHub asset URL"):
            self.prepare()

    def test_retention_keeps_current_and_recent_payloads_per_kind(self):
        runtimes = [f"rootfs-arm64-v8a-{i:016x}.tar.xz" for i in range(4)]
        apks = [f"AzurPilot-Android-1.3.0-arm64-v8a-update-{i:016x}.apk" for i in range(4)]
        assets = [{"name": name, "created_at": f"2026-11-{i + 1:02d}T00:00:00Z"}
                  for names in (runtimes, apks) for i, name in enumerate(names)]
        keep = cnb.retained_payload_names(assets, {runtimes[0], apks[3]})
        self.assertEqual(keep, {runtimes[0], *runtimes[2:], *apks[1:]})

    def test_cleanup_removes_other_releases_tags_and_preserves_three_apk_versions(self):
        mirror = self.prepare()
        api = FakeApi()
        api.old_releases = [{"id": f"old-{i}", "tag_name": f"github-android-{i}-1"}
                            for i in range(105)]
        api.old_tags = [{"name": item["tag_name"]} for item in api.old_releases] + [{"name": "v0.1.0"}]
        api.files = {f"AzurPilot-Android-1.2.{v}-update.apk": b"historical signed APK"
                     for v in (1, 2, 3, 4)}
        api.files["obsolete-frontend.tar.xz"] = b"old frontend"
        api.confirmed = set(api.files)
        cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api).publish(
            self.output, mirror, self.tag)
        self.assertFalse(api.old_releases)
        self.assertFalse(api.old_tags)
        self.assertIn("AzurPilot-Android-1.2.4-update.apk", api.files)
        self.assertIn("AzurPilot-Android-1.2.3-update.apk", api.files)
        self.assertNotIn("AzurPilot-Android-1.2.2-update.apk", api.files)
        self.assertNotIn("obsolete-frontend.tar.xz", api.files)

    def test_failed_replacement_does_not_commit_index_or_delete_old_releases(self):
        mirror = self.prepare()
        api = FakeApi()
        api.old_releases = [{"id": "old", "tag_name": "github-android-123-1"}]
        api.corrupt_remote = True
        with self.assertRaises(ValueError):
            cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api).publish(
                self.output, mirror, self.tag)
        self.assertNotIn("latest.json", api.files)
        self.assertFalse(any(method == "DELETE" for method, _, _ in api.requests))

    def test_only_fixed_tag_is_accepted(self):
        with self.assertRaisesRegex(ValueError, "fixed latest tag"):
            cnb.prepare(self.source, self.output, self.repo, self.tag,
                        cnb.CNB_REPOSITORY, "github-android-123-1")

    def test_cnb_null_assets_on_empty_public_release_are_supported(self):
        mirror = self.prepare()
        api = FakeApi()
        api.null_empty_assets = True
        api.existing = {"id": "release-1", "tag_name": self.tag, "draft": False}
        cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, api).publish(
            self.output, mirror, self.tag)
        self.assertEqual(api.confirmed, {path.name for path in self.output.iterdir()})

    def test_cnb_null_optional_hashes_do_not_crash_verification(self):
        self.prepare()
        release = {"assets": [{"name": path.name, "size": path.stat().st_size,
                               "hash_algo": None, "hash_value": None}
                              for path in self.output.iterdir()]}
        cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, FakeApi()).verify(release, self.output)

    def test_release_tag_sync_uploads_commit_without_credentials_in_arguments(self):
        with patch.object(cnb.subprocess, "run") as run:
            cnb.sync_release_tag("test-access-token", cnb.CNB_REPOSITORY, "b" * 40)
        self.assertEqual(run.call_count, 2)
        args = run.call_args.args[0]
        self.assertEqual(args[-1], f"{'b' * 40}:refs/tags/{cnb.RELEASE_TAG}")
        self.assertNotIn("test-access-token", str(args))
        environment = run.call_args.kwargs["env"]
        header = environment[f"GIT_CONFIG_VALUE_{int(environment['GIT_CONFIG_COUNT']) - 1}"]
        self.assertIn("Authorization: Basic ", header)

    def test_token_is_never_sent_to_foreign_api_urls(self):
        client = cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, FakeApi())
        for url in ("https://storage.example/confirm", client.base + "-other/confirm",
                    client.base.replace("azurpilot/", "other/"),
                    client.base.replace("api.cnb.cool", "api.cnb.cool.evil.example")):
            with self.subTest(url=url), self.assertRaisesRegex(ValueError, "Unexpected CNB"):
                client.request("POST", url)
        self.assertIsNone(cnb._NoRedirect().redirect_request(None, None, 302, "redirect", {},
                                                            "https://storage.example"))

    def test_missing_token_and_permission_denial_are_explicit(self):
        with self.assertRaisesRegex(ValueError, "repo-release:rw"):
            cnb.CnbClient("", cnb.CNB_REPOSITORY)
        client = cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, FakeApi())
        with patch.object(client.opener, "open", side_effect=HTTPError("url", 403, "denied", {}, None)):
            with self.assertRaisesRegex(RuntimeError, "repo-release:rw"):
                client.request("POST", client.base, {})

    def test_api_error_body_does_not_count_as_success(self):
        client = cnb.CnbClient("test-access-token", cnb.CNB_REPOSITORY, FakeApi())
        response = io.BytesIO(b'{"errcode":403,"errmsg":"permission denied"}')
        with patch.object(client.opener, "open", return_value=response):
            with self.assertRaisesRegex(RuntimeError, "check token permissions"):
                client.request("POST", client.base, {})


if __name__ == "__main__":
    unittest.main()
