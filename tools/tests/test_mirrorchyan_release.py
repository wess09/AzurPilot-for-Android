"""验证滚动 Release 的包选择、完整性检查与分发版本。

Checks rolling-release package selection, completeness checks, and distribution versions.
"""

import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mirrorchyan_release as mirror


class MirrorChyanReleaseTest(unittest.TestCase):
    """防止旧版本、错误架构或未上传完成的 APK 和 rootfs 进入分发。

    Prevents distribution of outdated, mismatched, or incomplete APKs and rootfs archives.
    """

    def setUp(self):
        version = "1.3.238"
        base = f"https://github.com/{mirror.SOURCE_REPO}/releases/download/{mirror.cnb.RELEASE_TAG}/"

        def entry(filename):
            return {"url": base + filename, "sha256": "a" * 64, "size": 100}

        self.manifest = {"versionName": version, "versionCode": 1794733398,
                         "appCommit": "a" * 40, "androidHostCommit": "b" * 40,
                         "azurpilotCommit": "c" * 40, "runtimes": {},
                         "fullApks": {}, "updateApks": {}}
        apk = entry(f"AzurPilot-Android-{version}-update.apk")
        self.manifest.update(apkUrl=apk["url"], apkSha256=apk["sha256"], apkSize=apk["size"])
        for abi in mirror.cnb.ABIS:
            self.manifest["runtimes"][abi] = {"version": "runtime-123", **entry(f"rootfs-{abi}.tar.xz")}
            for kind, field in (("full", "fullApks"), ("update", "updateApks")):
                self.manifest[field][abi] = entry(f"AzurPilot-Android-{version}-{abi}-{kind}.apk")
        self.release = {"tag_name": mirror.cnb.RELEASE_TAG, "draft": False, "body": "更新说明\nRelease notes",
                        "assets": [{"name": filename, "state": "uploaded", "size": item["size"]}
                                   for item, filename in mirror.cnb.asset_entries(self.manifest)]}

    def test_uses_manifest_instead_of_asset_order_or_highest_filename(self):
        self.release["assets"].insert(0, {"name": "AzurPilot-Android-9.9.999-update.apk",
                                            "state": "uploaded", "size": 100})
        result = mirror.resolve(self.manifest, self.release)
        packages = json.loads(result["matrix"])["include"]
        self.assertEqual(len(packages), 7)
        self.assertTrue(all("1.3.238" in package["filename"] for package in packages
                            if package["filename"].endswith(".apk")))
        self.assertEqual({(package["rid"], package["os"], package["arch"]) for package in packages},
                         {("AzurPilotAndroid", "android", "arm64"),
                          ("AzurPilotAndroid", "android", "x86_64"),
                          ("AzurPilotAndroidUpdate", "android", "arm64"),
                          ("AzurPilotAndroidUpdate", "android", "x86_64"),
                          ("AzurPilotAndroidUpdate", "android", ""),
                          ("AzurPilotAndroidRootfs", "", "arm64"),
                          ("AzurPilotAndroidRootfs", "", "x86_64")})

    def test_selects_checksum_suffixed_assets(self):
        for entry, name in mirror.cnb.asset_entries(self.manifest):
            suffix = ".apk" if name.endswith(".apk") else ".tar.xz"
            immutable = f"{name.removesuffix(suffix)}-{entry['sha256'][:16]}{suffix}"
            if entry["url"] == self.manifest["apkUrl"]:
                self.manifest["apkUrl"] = entry["url"].removesuffix(name) + immutable
            else:
                entry["url"] = entry["url"].removesuffix(name) + immutable
            asset = next(item for item in self.release["assets"] if item["name"] == name)
            self.release["assets"].append({**asset, "name": immutable})

        packages = json.loads(mirror.resolve(self.manifest, self.release)["matrix"])["include"]
        self.assertEqual(len(packages), 7)
        for package in packages:
            self.assertIn(f"-{package['sha256'][:16]}.", package["filename"])

    def test_rejects_missing_incomplete_or_wrong_sized_current_assets(self):
        for filename, change in (
            (filename, change)
            for filename in ("AzurPilot-Android-1.3.238-x86_64-update.apk",
                             "rootfs-arm64-v8a.tar.xz", "rootfs-x86_64.tar.xz")
            for change in ("missing", "uploading", "size")
        ):
            with self.subTest(filename=filename, change=change):
                release = copy.deepcopy(self.release)
                asset = next(item for item in release["assets"] if item["name"] == filename)
                if change == "missing":
                    release["assets"].remove(asset)
                elif change == "uploading":
                    asset["state"] = "new"
                else:
                    asset["size"] += 1
                with self.assertRaisesRegex(ValueError, "missing or incomplete"):
                    mirror.resolve(self.manifest, release)

    def test_rejects_historical_or_external_manifest_urls(self):
        for url in (self.manifest["apkUrl"].replace("1.3.238", "1.3.233"),
                    "https://example.com/update.apk"):
            with self.subTest(url=url):
                manifest = copy.deepcopy(self.manifest)
                manifest["apkUrl"] = url
                with self.assertRaisesRegex(ValueError, "Unexpected GitHub asset URL"):
                    mirror.resolve(manifest, self.release)

    def test_requires_both_architectures_for_update(self):
        del self.manifest["updateApks"]["x86_64"]
        with self.assertRaisesRegex(ValueError, "per-ABI update APK metadata"):
            mirror.resolve(self.manifest, self.release)

    def test_preserves_upstream_version_even_when_content_or_notes_change(self):
        self.assertEqual(mirror.resolve(self.manifest, self.release)["version"], "1.3.238")
        self.release["assets"].reverse()
        self.release["body"] = "Edited notes"
        self.manifest["fullApks"]["arm64-v8a"]["sha256"] = "b" * 64
        self.assertEqual(mirror.resolve(self.manifest, self.release)["version"], "1.3.238")

    def test_rootfs_preserves_each_upstream_version_and_leaves_os_empty(self):
        self.manifest["runtimes"]["arm64-v8a"]["version"] = "upstream-arm64-123"
        self.manifest["runtimes"]["x86_64"]["version"] = "upstream-x86_64-456"
        result = mirror.resolve(self.manifest, self.release)
        packages = json.loads(result["matrix"])["include"]
        rootfs = [package for package in packages if package["filename"].endswith(".tar.xz")]
        self.assertEqual({package["arch"]: package["version"] for package in rootfs},
                         {"arm64": "upstream-arm64-123", "x86_64": "upstream-x86_64-456"})
        self.assertTrue(all(package["rid"] == "AzurPilotAndroidRootfs" and package["os"] == ""
                            for package in rootfs))
        self.assertTrue(all(package["version"] == "1.3.238" for package in packages
                            if package["filename"].endswith(".apk")))
        self.assertEqual(result["arm64_rootfs_version"], "upstream-arm64-123")
        self.assertEqual(result["x86_64_rootfs_version"], "upstream-x86_64-456")

    def test_rejects_missing_or_invalid_rootfs_versions(self):
        for version in (None, "", 'invalid"version', "invalid\nversion"):
            with self.subTest(version=version):
                manifest = copy.deepcopy(self.manifest)
                manifest["runtimes"]["arm64-v8a"]["version"] = version
                with self.assertRaisesRegex(ValueError, "Invalid rootfs version"):
                    mirror.resolve(manifest, self.release)

    def test_requires_published_rolling_release_and_supplies_nonempty_notes(self):
        for field, value in (("draft", True), ("tag_name", "v1.3.238")):
            with self.subTest(field=field):
                release = {**self.release, field: value}
                with self.assertRaisesRegex(ValueError, "published rolling release"):
                    mirror.resolve(self.manifest, release)
        self.release["body"] = ""
        self.assertEqual(mirror.resolve(self.manifest, self.release)["release_note"],
                         "AzurPilot for Android 1.3.238")

    def test_outputs_preserve_multiline_notes(self):
        values = mirror.resolve(self.manifest, self.release)
        values["release_note"] = "First line\nversion=unexpected\n最后一行"
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            mirror.write_outputs(values, output)
            lines = iter(output.read_text(encoding="utf-8").splitlines())
            parsed = {}
            for line in lines:
                key, delimiter = line.split("<<", 1)
                body = []
                for content in lines:
                    if content == delimiter:
                        break
                    body.append(content)
                parsed[key] = "\n".join(body)
            self.assertEqual(parsed, values)


if __name__ == "__main__":
    unittest.main()
