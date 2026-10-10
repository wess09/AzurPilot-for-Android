"""将白名单 ONNX OCR 会话的张量推理交给 APK，硬件错误由宿主回退 LiteRT CPU。

仅在宿主注入地址和口令时安装。按模型内容哈希匹配，热更后的新模型不会误用旧权重；
AP/RapidOCR 的图像预处理、语言字典和 CTC 解码保持原有语义。

Delegates allowlisted ONNX OCR tensor inference to the APK with host-side LiteRT CPU fallback.
Installation requires a host-injected address and token. Content hashes prevent stale weights
after hot updates. AP/RapidOCR retain their preprocessing, dictionaries, and CTC decoding.
"""

import hashlib
import json
import logging
import math
import os
import socket
import threading
import time
from pathlib import Path
from types import SimpleNamespace

MAX_HEADER = 1024 * 1024
MAX_PAYLOAD = 64 * 1024 * 1024
LOGGER = logging.getLogger("android_ocr")


class AndroidOcrInitializationError(SystemExit):
    """终止 OCR 无法初始化的 AP 实例，绕过调度器的通用游戏重启处理。

    Stops an AP instance whose OCR cannot initialize, bypassing generic game restarts.
    """


def model_identity(path):
    """读取无权重的模型身份文件；迁移期间也接受原文件的内容哈希。

    Reads a weight-free identity descriptor, or hashes a source file during migration.
    """
    import re

    path = Path(path)
    if path.stat().st_size <= 4096:
        try:
            descriptor = json.loads(path.read_bytes())
        except (ValueError, UnicodeError):
            descriptor = None
        if isinstance(descriptor, dict) and "android_ocr_model" in descriptor:
            digest = descriptor.get("sha256")
            if descriptor["android_ocr_model"] != 2 or not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest):
                raise ValueError("Invalid Android OCR model descriptor")
            return digest
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def patch_ap_module(module):
    """将 Android 配置接到宿主，且只初始化 RapidOCR 实际启用的组件。

    Routes Android settings to the host and initializes only enabled RapidOCR components.
    Saved configurations and selected model versions remain intact.
    """
    from dataclasses import replace

    prepare_runtime_descriptors(Path(__file__).resolve().parent)
    settings = module.OcrSettings
    if getattr(settings, "_android_host_adapter", False):
        return
    original = settings.from_config

    def from_config(cls, *args, **kwargs):
        value = original(*args, **kwargs)
        return replace(value, backend="onnxruntime", device="cpu", allow_vendor_execution_providers=False)

    settings.from_config = classmethod(from_config)
    settings._android_host_adapter = True
    rapid = module.RapidOCR
    components = getattr(rapid, '_android_ocr_components', rapid._initialize.__globals__)
    rapid._android_ocr_components = components

    def initialize(self, cfg):
        self.text_score = cfg.Global.text_score
        self.min_height = cfg.Global.min_height
        self.width_height_ratio = cfg.Global.width_height_ratio
        for short, key, component in (("det", "Det", "TextDetector"),
                                      ("cls", "Cls", "TextClassifier"),
                                      ("rec", "Rec", "TextRecognizer")):
            enabled = getattr(cfg.Global, "use_" + short)
            setattr(self, "use_" + short, enabled)
            if enabled:
                if short == "cls":
                    raise RuntimeError("Android OCR does not bundle the unused orientation classifier")
                options = getattr(cfg, key)
                options.engine_cfg = cfg.EngineConfig[options.engine_type.value]
                options.model_root_dir = cfg.Global.model_root_dir
                if short == "rec":
                    options.font_path = cfg.Global.font_path
                value = components[component](options)
            else:
                value = None
            setattr(self, "text_" + short, value)
        self.load_img = components["LoadImage"]()
        self.max_side_len = cfg.Global.max_side_len
        self.min_side_len = cfg.Global.min_side_len
        self.cal_rec_boxes = components["CalRecBoxes"]()
        self.return_word_box = cfg.Global.return_word_box
        self.return_single_char_box = cfg.Global.return_single_char_box
        self.cfg = cfg

    rapid._initialize = initialize


def prepare_runtime_descriptors(install, rootfs=Path('/')):
    """热更源码恢复官方权重后再次回收，只删除内容哈希匹配的文件。

    Reclaims official weights restored by source updates, deleting only matching content hashes.
    Validates all active identities first and leaves changed/custom files intact on failure.
    """
    metadata = install / 'ocr-host-models.json'
    retired = install / 'ocr-retired-models.json'
    if not metadata.is_file() or not retired.is_file():
        return
    if _read_host_json('status')['status'].get('model_format_version', 0) < 2:
        raise RuntimeError('This weight-free runtime requires an updated LiteRT CPU APK')
    manifest = json.loads(metadata.read_bytes())
    if manifest.get('version') != 2:
        raise ValueError('Invalid APK OCR model format')
    active = []
    for spec in manifest['models']:
        relative = Path(spec['asset']).relative_to('models')
        path = install / 'bin/ocr_models' / relative
        if not path.resolve().is_relative_to(install.resolve()) or any(p.is_symlink() for p in [path, *path.parents]):
            raise ValueError('OCR descriptor path contains a link or escapes AP')
        if path.exists() and model_identity(path) != spec['sha256']:
            raise RuntimeError(f'AP model changed; update the APK: {path.name}')
        active.append((path, spec['sha256']))
    removed = 0
    for spec in json.loads(retired.read_bytes()):
        path = rootfs / spec['path']
        if (not path.resolve().is_relative_to(rootfs.resolve()) or
                any(p.is_symlink() for p in [path, *path.parents]) or not path.is_file()):
            continue
        if path.stat().st_size == spec['size'] and model_identity(path) == spec['sha256']:
            removed += path.stat().st_size
            path.unlink()
    for path, digest in active:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(path.suffix + '.host.tmp')
        if temporary.is_symlink():
            raise ValueError('OCR temporary descriptor is a link')
        temporary.write_text(json.dumps({'android_ocr_model': 2, 'sha256': digest}, separators=(',', ':')) + '\n')
        temporary.replace(path)
    if removed:
        LOGGER.info('Retired official OCR source weights: %s bytes', removed)


def install_ap_adapter():
    """延迟到 AP OCR 模块导入后适配，避免 sitecustomize 提前启动 AP。

    Adapts AP after its OCR module is imported without starting AP from sitecustomize.
    """
    import importlib.abc
    import importlib.machinery
    import sys

    name = "module.ocr.al_ocr"
    if name in sys.modules:
        patch_ap_module(sys.modules[name])
        return
    if any(getattr(finder, "_android_ocr_adapter", False) for finder in sys.meta_path):
        return

    class Loader(importlib.abc.Loader):
        """在上游模块初始化完成后接线。 / Connects after upstream module initialization."""

        def __init__(self, original):
            self.original = original

        def create_module(self, spec):
            return self.original.create_module(spec)

        def exec_module(self, module):
            self.original.exec_module(module)
            patch_ap_module(module)

    class Finder(importlib.abc.MetaPathFinder):
        """只拦截 AP OCR 模块。 / Intercepts only the AP OCR module."""
        _android_ocr_adapter = True

        def find_spec(self, fullname, path, target=None):
            if fullname == name:
                spec = importlib.machinery.PathFinder.find_spec(fullname, path)
                if spec and spec.loader:
                    spec.loader = Loader(spec.loader)
                return spec
            return None

    sys.meta_path.insert(0, Finder())


class AndroidSession:
    """兼容 RapidOCR 使用的 ONNX 会话方法；每个会话串行访问连接。

    Implements the ONNX session methods used by RapidOCR, serializing each connection.
    """

    def __init__(self, original, args, kwargs, model_hash, address, token):
        self._original = original
        self._args, self._kwargs = args, kwargs
        self._hash, self._address, self._token = model_hash, address, token
        self._socket = self._reader = None
        self._lock = threading.Lock()
        self._metadata = self._request("describe")[0]["model"]
        self._failed = False
        self._last_backend = "uninitialized"
        self._last_shape = None
        self._host_runs = 0

    def _connect(self):
        if self._socket is None:
            host, port = self._address.rsplit(":", 1)
            self._socket = socket.create_connection((host, int(port)), timeout=3)
            self._socket.settimeout(90)
            self._reader = self._socket.makefile("rb")

    def _close(self):
        reader = self.__dict__.get("_reader")
        connection = self.__dict__.get("_socket")
        if reader is not None:
            reader.close()
        if connection is not None:
            connection.close()
        self._socket = self._reader = None

    def _read_exact(self, count):
        if not 0 <= count <= MAX_PAYLOAD:
            raise ValueError("OCR response payload is too large")
        output = bytearray(count)
        offset = 0
        while offset < count:
            chunk = self._reader.read(count - offset)
            if not chunk:
                raise ConnectionError("Truncated OCR tensor")
            output[offset:offset + len(chunk)] = chunk
            offset += len(chunk)
        return output

    def _request(self, method, payload=b"", **fields):
        # 每次请求释放连接槽，防止多个 OCR 实例的闲置会话占满宿主；网络中断重试一次。
        for attempt in range(2):
            try:
                return self._request_once(method, payload, **fields)
            except (OSError, ConnectionError):
                if attempt:
                    raise
                # 原生工作进程退出后，留出宿主记录失败模型并重新绑定 CPU 会话的时间。
                time.sleep(0.5)
            finally:
                self._close()

    def _request_once(self, method, payload=b"", **fields):
        self._connect()
        request = {"method": method, "model_sha256": self._hash,
                   "token": self._token, **fields}
        header = json.dumps(request, separators=(",", ":")).encode() + b"\n"
        if len(header) > MAX_HEADER or len(payload) > MAX_PAYLOAD:
            raise ValueError("OCR request exceeds the protocol limit")
        self._socket.sendall(header)
        if payload:
            self._socket.sendall(payload)
        line = self._reader.readline(MAX_HEADER + 1)
        if not line:
            raise ConnectionError("OCR server closed the connection")
        if not line.endswith(b"\n") or len(line) > MAX_HEADER:
            raise ValueError("Invalid OCR response header")
        reply = json.loads(line)
        if not reply.get("ok"):
            if reply.get("error_code") == "cpu_initialization_failed" and os.environ.get("AZURPILOT_OCR_TEST") != "1":
                message = "OCR 初始化失败，任务已停止。请重启应用重试，并导出 OCR 日志。"
                LOGGER.error("%s %s", message, reply.get("error", ""))
                raise AndroidOcrInitializationError(message)
            raise RuntimeError(reply.get("error", "Android OCR failed"))
        data = self._read_exact(reply.get("length", 0))
        return reply, data

    def get_inputs(self):
        """返回原 ONNX 输入元数据。 / Returns original ONNX input metadata."""
        return [SimpleNamespace(**item) for item in self._metadata["inputs"]]

    def get_outputs(self):
        """返回原 ONNX 输出元数据。 / Returns original ONNX output metadata."""
        return [SimpleNamespace(**item) for item in self._metadata["outputs"]]

    def get_modelmeta(self):
        """保留 RapidOCR 从模型读取字典的能力。 / Preserves RapidOCR's model dictionary lookup."""
        return SimpleNamespace(custom_metadata_map=self._metadata.get("custom_metadata_map", {}))

    def get_providers(self):
        """返回 ORT 兼容名称；实际后端由宿主状态接口给出。

        Returns an ORT-compatible name; the host status endpoint reports the actual backend.
        """
        return ["CPUExecutionProvider"]

    def run(self, output_names, input_feed, run_options=None):
        """推理单个 FP32 输入，返回原 ONNX 顺序的数组；宿主错误直接报告。

        Runs one FP32 input and returns arrays in ONNX order; host errors are reported.
        """
        import numpy as np

        with self._lock:
            try:
                if run_options is not None:
                    raise ValueError("Android OCR does not support ORT RunOptions")
                names = [item["name"] for item in self._metadata["inputs"]]
                if list(input_feed) != names or len(names) != 1:
                    raise ValueError("Android OCR expects one named input")
                values = np.asarray(input_feed[names[0]])
                if values.dtype != np.float32 or not np.isfinite(values).all():
                    raise ValueError("Android OCR expects finite float32 tensors")
                values = np.ascontiguousarray(values, dtype="<f4")
                reply, payload = self._request("run", values.tobytes(), shape=list(values.shape),
                                               input_name=names[0], length=values.nbytes,
                                               source="diagnostic" if os.environ.get("AZURPILOT_OCR_TEST") == "1" else "ap")
                outputs = {}
                offset = 0
                for item in reply["outputs"]:
                    shape = tuple(item["shape"])
                    if not shape or len(shape) > 4 or any(type(d) is not int or d <= 0 for d in shape):
                        raise ValueError("Invalid OCR output shape")
                    size = math.prod(shape) * 4
                    if not 0 < size <= len(payload) - offset or item["name"] in outputs:
                        raise ValueError("Invalid OCR output shape")
                    result = np.frombuffer(payload, dtype="<f4", count=size // 4, offset=offset).reshape(shape)
                    if not np.isfinite(result).all():
                        raise ValueError("Non-finite Android OCR output")
                    outputs[item["name"]] = result
                    offset += size
                if offset != len(payload):
                    raise ValueError("Trailing OCR tensor bytes")
                selected = output_names or [item["name"] for item in self._metadata["outputs"]]
                self._failed = False
                self._last_backend = reply.get("backend", "unknown")
                self._last_shape = list(values.shape)
                self._host_runs += 1
                return [outputs[name] for name in selected]
            except Exception as error:
                self._close()
                self._failed = True
                self._last_backend = "host_error"
                raise RuntimeError(f"Android LiteRT OCR failed: {error}") from error

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        raise AttributeError(name)

    def __del__(self):
        self._close()


def install():
    """安装 OCR 专用会话工厂；非 OCR 和内存模型使用原工厂；未知 OCR 版本必须升级 APK。

    Installs an OCR-only session factory; other models and memory models keep the original factory. Unknown OCR identities fail
    explicitly because source weights are no longer shipped.
    """
    address = os.environ.get("AZURPILOT_OCR_ADDRESS")
    token = os.environ.get("AZURPILOT_ANDROID_TOKEN")
    if not address or not token:
        return
    import onnxruntime as ort

    original = ort.InferenceSession
    if getattr(original, "_android_ocr_factory", False):
        return

    def create_session(*args, **kwargs):
        model = args[0] if args else kwargs.get("path_or_bytes")
        if isinstance(model, (str, Path)) and "ocr_models" in Path(model).parts:
            return AndroidSession(original, args, kwargs, model_identity(model), address, token)
        return original(*args, **kwargs)

    class SessionMeta(type(original)):
        """保留上游自定义会话的 isinstance 校验。 / Preserves upstream custom-session type checks."""

        def __instancecheck__(cls, instance):
            return isinstance(instance, (original, AndroidSession))

    class SessionFactory(original, metaclass=SessionMeta):
        """以类形式提供会话工厂，避免破坏类型判断。 / Keeps the session factory a class."""

        _android_ocr_factory = True

        def __new__(cls, *args, **kwargs):
            return create_session(*args, **kwargs)

    ort.InferenceSession = SessionFactory
    install_ap_adapter()


def _read_host_json(method, **fields):
    address = os.environ["AZURPILOT_OCR_ADDRESS"]
    host, port = address.rsplit(":", 1)
    with socket.create_connection((host, int(port)), timeout=5) as connection:
        request = {"method": method, "token": os.environ["AZURPILOT_ANDROID_TOKEN"], **fields}
        connection.sendall(json.dumps(request).encode() + b"\n")
        with connection.makefile("rb") as reader:
            line = reader.readline(MAX_HEADER + 1)
            if not line.endswith(b"\n") or len(line) > MAX_HEADER:
                raise ValueError("Invalid OCR status response")
            reply = json.loads(line)
            if not reply.get("ok"):
                raise RuntimeError(reply.get("error", "Android OCR status failed"))
            return reply


def read_host_status():
    """读取宿主状态，不输出认证口令。

    Reads host status without exposing the authentication token.
    """
    return _read_host_json("status")["status"]


def self_test(model_hash, sample_path="android_ocr_sample.png"):
    """通过 AP 的识别器工厂、预处理和解码验证真实宿主调用，不改实例配置。

    Uses AP's recognizer factory, preprocessing, and decoding to verify host calls without
    changing instance settings. Missing or changed weights and original-runtime fallback fail
    the integration test rather than falsely reporting a connected host.
    """
    from module.ocr.al_ocr import OcrSettings, _create_ocr, _get_onnx_model_params

    spec = _read_host_json("describe", model_sha256=model_hash)["model"]
    versions = {
        "PP-OCRv6_tiny_rec.onnx": ("ppocr_v6", "lite"),
        "PP-OCRv6_small_rec.onnx": ("ppocr_v6", "standard"),
        "PP-OCRv6_medium_rec.onnx": ("ppocr_v6", "pro"),
        "alocr-en-us-v2.6.nvc.onnx": ("azur_lane", "alocr_en_v2_6"),
        "alocr-zh-cn-v3.dtk.onnx": ("cn", "alocr_cn_v3"),
    }
    name, version = versions[Path(spec["asset"]).name]
    settings = OcrSettings(backend="onnxruntime", device="cpu",
                           allow_vendor_execution_providers=False, model_version=version)
    model_path = Path(_get_onnx_model_params(name, settings)[0])
    if model_identity(model_path) != model_hash:
        raise RuntimeError("AP model differs from the APK; update the APK before using this model")
    previous_flag = os.environ.get("AZURPILOT_OCR_TEST")
    os.environ["AZURPILOT_OCR_TEST"] = "1"
    try:
        started = time.perf_counter()
        recognizer = _create_ocr(name, settings)
        session = recognizer.text_rec.session.session
        if not isinstance(session, AndroidSession):
            raise RuntimeError("AP OCR did not use the Android session factory")
        result = recognizer(str(sample_path), use_det=False, use_cls=False, use_rec=True)
        elapsed_ms = (time.perf_counter() - started) * 1000
        if session._failed or session._host_runs == 0:
            raise RuntimeError("AP OCR fell back to its original runtime; host integration failed")
        texts = list(result.txts or [])
        return {"ok": True, "model_sha256": model_hash, "backend": session._last_backend,
                "elapsed_ms": elapsed_ms, "texts": texts, "expected_text": "12345",
                "sample_text_matches": "".join(texts).strip() == "12345",
                "host_runs": session._host_runs, "instance_settings_changed": False}
    finally:
        if previous_flag is None:
            os.environ.pop("AZURPILOT_OCR_TEST", None)
        else:
            os.environ["AZURPILOT_OCR_TEST"] = previous_flag


def configured_test(config_name, sample_path="android_ocr_sample.png"):
    """按实例保存的配置调用正常 AlOcr 入口；不强制后端或模型，不启动游戏任务。

    Calls normal AlOcr with the instance's saved settings, without forcing a backend or model
    or starting game tasks. Configuration migration runs in memory; diagnostic requests remain
    separate from business requests.
    """
    import copy
    import re
    from dataclasses import asdict
    from module.config.config import AzurLaneConfig
    from module.ocr.al_ocr import AlOcr, _get_onnx_model_params

    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", config_name):
        raise ValueError("Invalid instance name")
    path = Path("config") / (config_name + ".json")
    snapshot = path.read_bytes()
    data = json.loads(snapshot)

    class ReadOnlyConfig(AzurLaneConfig):
        """只读配置快照，阻止构造器自动保存。 / Read-only snapshot blocks constructor saves."""

        def read_file(self, name, is_template=False):
            return self.config_update(copy.deepcopy(data), is_template=is_template)

        def save(self, *args, **kwargs):
            pass

        @staticmethod
        def write_file(*args, **kwargs):
            raise RuntimeError("Diagnostics cannot write instance settings")

    config = ReadOnlyConfig(config_name)
    config.auto_update = False
    previous_flag = os.environ.get("AZURPILOT_OCR_TEST")
    os.environ["AZURPILOT_OCR_TEST"] = "1"
    try:
        results = []
        for name in ("azur_lane", "cn", "jp", "tw"):
            recognizer = AlOcr(config=config, name=name)
            # 固定测试图不进入用户的游戏 OCR 截图目录，避免触发上游截图清理。
            recognizer._save_debug_image = lambda *args: None
            settings = recognizer._get_settings()
            model_hash = None
            model_name = None
            if settings.backend == "onnxruntime":
                model_path = Path(_get_onnx_model_params(name, settings)[0])
                model_name = model_path.name
                model_hash = model_identity(model_path)
            started = time.perf_counter()
            text = recognizer.ocr(str(sample_path))
            elapsed_ms = (time.perf_counter() - started) * 1000
            rec = getattr(recognizer.model, "text_rec", None)
            session = getattr(getattr(rec, "session", None), "session", None)
            host_session = isinstance(session, AndroidSession)
            host_runs = session._host_runs if host_session else 0
            status = read_host_status()
            state = next((item for item in status["model_status"]
                          if item["model_sha256"] == model_hash), {})
            results.append({
                "logical_model": name, "effective_settings": asdict(settings),
                "model_name": model_name, "model_sha256": model_hash,
                "android_session": host_session, "host_runs": host_runs,
                "backend": session._last_backend if host_session else "original_runtime_cpu",
                "shape": session._last_shape if host_session else None,
                "host_call": state.get("last_diagnostic_call") if host_runs else None,
                "reason": ("ncnn_bypasses_onnx_factory" if settings.backend == "ncnn" else
                           "model_not_bundled_or_host_unavailable" if not host_session else
                           "host_connection_failed" if session._failed else
                           state.get("last_diagnostic_call", {}).get("cpu_reason")),
                "elapsed_ms": elapsed_ms, "texts": [text],
                "sample_text_matches": text.strip() == "12345",
            })
        return {"ok": True, "test_mode": "saved_instance_configuration", "config_name": config_name,
                "results": results, "instance_settings_changed": path.read_bytes() != snapshot,
                "game_task_started": False, "business_call_proof": False}
    finally:
        if previous_flag is None:
            os.environ.pop("AZURPILOT_OCR_TEST", None)
        else:
            os.environ["AZURPILOT_OCR_TEST"] = previous_flag


def main():
    """输出状态或带固定标记的 AP 测试结果，不输出认证口令。

    Prints status or a marked AP integration result without exposing authentication tokens.
    """
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--model-sha256")
    parser.add_argument("--configured-test", action="store_true")
    parser.add_argument("--config")
    args = parser.parse_args()
    if args.self_test or args.configured_test:
        try:
            if args.configured_test:
                result = configured_test(args.config)
            elif not args.model_sha256:
                raise ValueError("Select a bundled recognition model")
            else:
                result = self_test(args.model_sha256)
        except Exception as error:
            result = {"ok": False, "error": str(error)[:500]}
        print("ANDROID_OCR_TEST=" + json.dumps(result, ensure_ascii=False))
    else:
        print(json.dumps(read_host_status(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    # sitecustomize 已导入规范模块，复用它避免 -m 的第二份类身份破坏 isinstance。
    import android_ocr

    android_ocr.main()
