"""从滚动 Release 清单选择当前 APK，避免混入历史版本。

Selects the current APKs from the rolling release manifest, excluding historical versions.
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import uuid

import cnb_release as cnb


SOURCE_REPO = "wess09/AzurPilot-for-Android"


def resolve(manifest: dict, release: dict) -> dict:
    """校验五个当前 APK，沿用清单中的上游版本号。

    Validates the five current APKs and preserves the upstream manifest version.
    """
    if release["tag_name"] != cnb.RELEASE_TAG or release.get("draft"):
        raise ValueError("MirrorChyan must use the published rolling release")
    cnb.validate_source_urls(manifest, SOURCE_REPO, cnb.RELEASE_TAG)
    if not isinstance(manifest.get("updateApks"), dict):
        raise ValueError("Missing per-ABI update APK metadata")

    packages = []
    for kind, rid, field in (
        ("full", "AzurPilotAndroid", "fullApks"),
        ("update", "AzurPilotAndroidUpdate", "updateApks"),
    ):
        for abi in cnb.ABIS:
            entry = manifest[field][abi]
            packages.append({"rid": rid, "os": "android",
                             "arch": "arm64" if abi == "arm64-v8a" else abi,
                             "filename": f"AzurPilot-Android-{manifest['versionName']}-{abi}-{kind}.apk",
                             "sha256": entry["sha256"], "size": entry["size"]})
    packages.append({"rid": "AzurPilotAndroidUpdate", "os": "android", "arch": "",
                     "filename": f"AzurPilot-Android-{manifest['versionName']}-update.apk",
                     "sha256": manifest["apkSha256"], "size": manifest["apkSize"]})

    available = {asset["name"]: asset for asset in release["assets"]}
    for package in packages:
        asset = available.get(package["filename"])
        if not asset or asset["state"] != "uploaded" or asset["size"] != package["size"]:
            raise ValueError(f"Current APK is missing or incomplete: {package['filename']}")

    return {"version": manifest["versionName"], "matrix": json.dumps({"include": packages}),
            "release_note": release.get("body") or f"AzurPilot for Android {manifest['versionName']}"}


def write_outputs(values: dict, output: Path) -> None:
    """安全写入 Actions 输出，包括可能包含多行文本的发布说明。

    Writes Actions outputs safely, including release notes containing multiple lines.
    """
    with output.open("a", encoding="utf-8") as stream:
        for key, value in values.items():
            delimiter = uuid.uuid4().hex
            while delimiter in value.splitlines():
                delimiter = uuid.uuid4().hex
            stream.write(f"{key}<<{delimiter}\n{value}\n{delimiter}\n")


def main() -> None:
    """读取上游已发布清单，不下载历史 APK 或重新构建。

    Reads the published upstream manifest without downloading historical APKs or rebuilding.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default=cnb.RELEASE_TAG)
    parser.add_argument("--output", default=os.environ.get("GITHUB_OUTPUT"), type=Path)
    args = parser.parse_args()
    if args.tag not in ("", cnb.RELEASE_TAG):
        parser.error(f"Only {cnb.RELEASE_TAG} is supported")
    if args.output is None:
        parser.error("--output or GITHUB_OUTPUT is required")

    release = json.loads(subprocess.check_output(
        ["gh", "api", f"repos/{SOURCE_REPO}/releases/tags/{cnb.RELEASE_TAG}"],
        encoding="utf-8"))
    directory = Path(".tmp/mirrorchyan")
    directory.mkdir(parents=True, exist_ok=True)
    subprocess.run(["gh", "release", "download", cnb.RELEASE_TAG, "--repo", SOURCE_REPO,
                    "--pattern", "latest.json", "--dir", str(directory), "--clobber"], check=True)
    manifest = json.loads((directory / "latest.json").read_text(encoding="utf-8"))
    outputs = resolve(manifest, release)
    write_outputs(outputs, args.output)
    print(f"MirrorChyan version: {outputs['version']}")
    for package in json.loads(outputs["matrix"])["include"]:
        print(f"{package['filename']} -> {package['rid']} / {package['os']} / {package['arch']}")


if __name__ == "__main__":
    main()
