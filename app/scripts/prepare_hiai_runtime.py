#!/usr/bin/env python3
"""准备 ARM64 HiAI 客户端和 MNN 源码；固定来源，不运行 SDK 内的脚本。

华为官方示例采用 Apache 2.0 许可，提取 NPU 最小库集和头文件。MNN 在 APK 构建时
编译；本 SDK 不导出且 ARM64 代码未引用的四个旧 ABI 符号从分发表删除，链接器仍会
拒绝任何实际代码引用的缺失符号。

Prepares ARM64 HiAI clients and pinned MNN sources without running SDK scripts.
Extracts the official Apache-2.0 demo's minimal NPU libraries and headers. MNN builds with
the APK. Four unused legacy ABI entries absent from this SDK are removed for ARM64; the
linker still rejects any missing symbol actually referenced by compiled code.
"""

import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path, PurePosixPath

from fetch_ocr_runtime import OUTPUT, download

MNN_COMMIT = "024a946b0b8fcf87c8a418229fadd4cd7858ffba"
MNN_ARCHIVE_SHA = "b198889b75fde9c8231e472ea3e1228f773bc2e32ab8b2f5287a3105beb934ce"
HIAI_COMMIT = "220e2070f1fad9005d6ed06d33520e559cdac7ec"
HIAI_URL = "https://gitee.com/huawei-hiai-foundation/HiAIDemo.git"
HIAI_LIBRARIES = ["libhiai.so", "libhiai_ir.so", "libhiai_ir_build.so",
                  "libhiai_model_compatible.so", "libhiai_enhance.so"]
LEGACY_ABI_ENTRIES = ["_ZN2ge5ShapeC1ENSt6__ndk16vectorIxNS1_9allocatorIxEEEE",
                    "_ZN2ge6Tensor7SetDataEPKhj",
                    "_ZN2ge9AttrValue10CreateFromERKNSt6__ndk16vectorIxNS1_9allocatorIxEEEE",
                    "_ZN2ge9AttrValue10CreateFromEx"]
TRACE_PATCH = Path(__file__).resolve().with_name("patches") / "mnn-hiai-stage-trace.patch"


def git(repo, *arguments):
    """读取固定 Git 对象，不调用 checkout hooks。 / Reads pinned Git objects without checkout hooks."""
    return subprocess.check_output(["git", "--git-dir", str(repo), *arguments], timeout=180)


def prepare():
    """准备源码、库与来源清单。 / Prepares sources, libraries, and their provenance manifest."""
    source = OUTPUT / "sources/mnn"
    marker = source / ".ocr-hiai-version"
    patch_checksum = hashlib.sha256(TRACE_PATCH.read_bytes()).hexdigest()
    version = f"{MNN_COMMIT}:{HIAI_COMMIT}:arm64-abi-v3:{patch_checksum}"
    runtime_manifest = OUTPUT / "assets/ocr/hiai-runtime.json"
    if marker.is_file() and marker.read_text() == version and runtime_manifest.is_file():
        cached = json.loads(runtime_manifest.read_text())
        if all((OUTPUT / "jni/arm64-v8a" / name).is_file() and
               hashlib.sha256((OUTPUT / "jni/arm64-v8a" / name).read_bytes()).hexdigest() == checksum
               for name, checksum in cached["libraries"].items()) and all(
                   (OUTPUT / "assets/ocr/licenses" / name).is_file()
                   for name in ["hiai-LICENSE.txt", "mnn-LICENSE.txt",
                                "mnn-flatbuffers-LICENSE.txt", "mnn-half-LICENSE.txt"]):
            return
    mnn = download("mnn-source.zip", f"https://codeload.github.com/alibaba/MNN/zip/{MNN_COMMIT}",
                   MNN_ARCHIVE_SHA)
    # SDK 示例含有其他大模型；裸仓库避免将无关资源写入工作目录。
    repo = OUTPUT / "downloads/hiai-demo.git"
    if not repo.is_dir():
        subprocess.run(["git", "clone", "--bare", "--depth", "1", HIAI_URL, str(repo)],
                       check=True, timeout=300)
    if git(repo, "rev-parse", "HEAD").decode().strip() != HIAI_COMMIT:
        subprocess.run(["git", "--git-dir", str(repo), "fetch", "--depth", "1", "origin", HIAI_COMMIT],
                       check=True, timeout=300)
    git(repo, "cat-file", "-e", f"{HIAI_COMMIT}^{{commit}}")
    source.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(mnn) as archive:
        prefix = f"MNN-{MNN_COMMIT}/"
        for name in archive.namelist():
            if not name.startswith(prefix) or name.endswith("/"):
                continue
            relative = PurePosixPath(name[len(prefix):])
            if ".." in relative.parts or relative.is_absolute():
                raise ValueError("Unsafe source archive path")
            if relative.parts[0] not in {"CMakeLists.txt", "cmake", "include", "source", "schema",
                                         "3rd_party", "express", "tools", "LICENSE.txt"}:
                continue
            target = source / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    repo_root = Path(__file__).resolve().parents[2]
    # 源码来自上面已校验哈希的归档，补丁只保留改动行以避开上游行尾空白。
    subprocess.run(["git", "apply", "--unidiff-zero",
                    f"--directory={source.relative_to(repo_root).as_posix()}",
                    str(TRACE_PATCH)], cwd=repo_root, check=True)
    prefix = "HiAICLS/app/src/main/jni/"
    names = git(repo, "ls-tree", "-r", "--name-only", HIAI_COMMIT, prefix).decode().splitlines()
    for name in names:
        if not name.endswith(".h"):
            continue
        relative = PurePosixPath(name[len(prefix):])
        if ".." in relative.parts or relative.is_absolute():
            raise ValueError("Unsafe SDK header path")
        target = source / "source/backend/hiai/3rdParty/include" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git(repo, "show", f"{HIAI_COMMIT}:{name}"))
    symbols = source / "source/backend/hiai/backend/HiAIDynamicSymbols.inc"
    original = symbols.read_text()
    for entry in LEGACY_ABI_ENTRIES:
        line = f"MNN_HIAI_FUNCTION({entry})\n"
        if original.count(line) != 1:
            raise ValueError("Unexpected MNN HiAI ABI source")
        original = original.replace(line, "")
    symbols.write_text("// AzurPilot modification: omit four unused legacy ABI entries on ARM64.\n" + original)
    libraries = OUTPUT / "jni/arm64-v8a"
    notices = OUTPUT / "assets/ocr/licenses"
    libraries.mkdir(parents=True, exist_ok=True)
    notices.mkdir(parents=True, exist_ok=True)
    checksums = {}
    for name in HIAI_LIBRARIES:
        data = git(repo, "show", f"{HIAI_COMMIT}:HiAICLS/app/libs_all/arm64-v8a/{name}")
        (libraries / name).write_bytes(data)
        checksums[name] = hashlib.sha256(data).hexdigest()
    (notices / "hiai-LICENSE.txt").write_bytes(git(repo, "show", f"{HIAI_COMMIT}:LICENSE"))
    shutil.copyfile(source / "LICENSE.txt", notices / "mnn-LICENSE.txt")
    for dependency in ["flatbuffers", "half"]:
        shutil.copyfile(source / f"3rd_party/{dependency}/LICENSE.txt",
                        notices / f"mnn-{dependency}-LICENSE.txt")
    (OUTPUT / "assets/ocr/hiai-runtime.json").write_text(json.dumps({
        "mnn_source_commit": MNN_COMMIT, "mnn_converter": "3.6.1",
        "mnn_source_archive_sha256": MNN_ARCHIVE_SHA,
        "hiai_source_commit": HIAI_COMMIT, "hiai_source_url": HIAI_URL,
        "mode": "NPU-only V320 client API", "libraries": checksums,
        "mnn_arm64_abi_removed": LEGACY_ABI_ENTRIES,
    }, indent=2) + "\n", encoding="utf-8")
    marker.write_text(version)
    print("Prepared HiAI NPU clients and matching MNN ARM64 sources", flush=True)


if __name__ == "__main__":
    prepare()
