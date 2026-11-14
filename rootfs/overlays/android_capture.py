"""通过只读共享内存读取 Android 虚拟屏，控制消息与像素分开传输。

每个连接持有独立映射。服务在响应前复制最新 BGR 帧，客户端在下次请求前生成自有 RGB
数组，避免画面撕裂。旧 APK、非本机地址及不可用的 Unix 通道沿用上游 TCP 截图。

Reads the Android virtual display through read-only shared memory, separating control from pixels.

Each connection owns its mapping. The server copies the latest BGR frame before replying; clients
produce an owned RGB array before requesting again to avoid tearing. Old APKs, nonlocal addresses,
and unavailable Unix channels retain upstream TCP capture.
"""

import array
import importlib.abc
import json
import mmap
import os
import socket
import sys
import threading
import time


TARGET_MODULE = 'module.device.method.azurpilot_android'
SOCKET_NAME = 'com.azurpilot.ghio.frames.v1'
MAX_BYTES = 32 * 1024 * 1024
MAX_LINE = 4096
RETRY_SECONDS = 30


class SharedFrameError(Exception):
    """表示截图通道失败，允许回退 TCP。 / Indicates a capture-channel failure permitting TCP fallback."""


class SharedFrameClient:
    """串行读取独占映射；返回的图像与连接和映射生命周期无关。

    Reads a connection-owned mapping serially; returned images outlive the connection and mapping.
    """

    def __init__(self, name=SOCKET_NAME, timeout=5):
        self.name = name
        self.timeout = timeout
        self.sock = None
        self.mapping = None
        self.capacity = 0
        self.pid = os.getpid()
        self.lock = threading.RLock()
        self.last_metadata = None

    def close(self):
        """关闭 FD 和映射，重复调用无副作用。 / Closes the socket and mapping idempotently."""
        if self.sock is not None:
            self.sock.close()
            self.sock = None
        if self.mapping is not None:
            self.mapping.close()
            self.mapping = None
        self.capacity = 0

    def _response(self, receive_fd=False):
        header = bytearray()
        fds = []
        try:
            while b'\n' not in header:
                if receive_fd:
                    data, ancillary, flags, _ = self.sock.recvmsg(
                        MAX_LINE, socket.CMSG_SPACE(8 * array.array('i').itemsize))
                    for level, kind, payload in ancillary:
                        if level == socket.SOL_SOCKET and kind == socket.SCM_RIGHTS:
                            received = array.array('i')
                            received.frombytes(payload[:len(payload) - len(payload) % received.itemsize])
                            fds.extend(received)
                    if flags & socket.MSG_CTRUNC:
                        raise SharedFrameError('truncated descriptors')
                else:
                    data = self.sock.recv(MAX_LINE)
                if not data:
                    raise SharedFrameError('capture channel closed')
                header.extend(data)
                if len(header) > MAX_LINE:
                    raise SharedFrameError('response line too long')
            # 本通道没有像素负载且只允许一个未完成请求，换行后的字节属于协议错误。
            line, extra = bytes(header).split(b'\n', 1)
            if extra:
                raise SharedFrameError('unexpected response bytes')
            response = json.loads(line)
            if not isinstance(response, dict):
                raise SharedFrameError('invalid response')
            if not response.get('ok'):
                raise SharedFrameError(response.get('error', 'capture failed'))
            if receive_fd:
                if len(fds) != 1 or response.get('version') != 1:
                    raise SharedFrameError('invalid shared-memory handshake')
                capacity = response.get('capacity')
                if type(capacity) is not int or not 0 < capacity <= MAX_BYTES:
                    raise SharedFrameError('invalid shared-memory capacity')
                self.mapping = mmap.mmap(fds[0], capacity, flags=mmap.MAP_SHARED, prot=mmap.PROT_READ)
                self.capacity = capacity
            return response
        finally:
            for fd in fds:
                os.close(fd)

    def _connect(self):
        self.close()
        try:
            # 监听 socket 由 App 创建并经 Binder 交给特权端，避免跨域 connectto 被拒绝。
            self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.sock.settimeout(self.timeout)
            self.sock.connect('\0' + self.name)
            self.sock.sendall(b'{"method":"open","version":1}\n')
            self._response(receive_fd=True)
        except Exception:
            self.close()
            raise

    def capture(self):
        """读取最新帧并返回自有、连续的 RGB ndarray；断连或改尺寸时重开一次。

        Returns the latest frame as an owned contiguous RGB ndarray, reopening once after a
        disconnect or resize. Callers may retain returned images across further captures.
        """
        import numpy as np

        # fork 后不能共享同一 socket 的请求流或父进程可能持有的 Python 锁。
        if self.pid != os.getpid():
            self.close()
            self.pid = os.getpid()
            self.lock = threading.RLock()
        with self.lock:
            for attempt in range(2):
                try:
                    if self.sock is None:
                        self._connect()
                    self.sock.sendall(b'{"method":"screencap"}\n')
                    response = self._response()
                    width, height = response.get('width'), response.get('height')
                    length = response.get('length')
                    if (type(width) is not int or type(height) is not int
                            or width <= 0 or height <= 0 or response.get('channels') != 3
                            or type(length) is not int or length != width * height * 3
                            or length > self.capacity):
                        raise SharedFrameError('invalid frame geometry')
                    self.last_metadata = response
                    # 复制同时交换红蓝通道；返回值不保留可被下一次请求覆盖的 mmap 视图。
                    return np.ndarray((height, width, 3), dtype=np.uint8,
                                      buffer=self.mapping)[..., ::-1].copy()
                except (OSError, ValueError, SharedFrameError):
                    self.close()
                    if attempt:
                        raise


def patch_ap_module(module):
    """幂等接入本机截图，保留上游输入控制和远程 TCP 地址。

    Installs local shared-memory capture idempotently, retaining upstream controls and remote TCP.
    """
    backend = module.AzurPilotAndroid
    if getattr(backend, '_android_capture_adapter', False):
        return
    original = backend.screenshot_azurpilot_android

    def screenshot(self):
        """优先内存截图；不可用时回退，30 秒后重试协商。

        Prefers memory capture, falling back on failure and retrying negotiation after 30 seconds.
        """
        if (getattr(self, 'azurpilot_android_addr', '') == '127.0.0.1:22301'
                and hasattr(socket, 'SCM_RIGHTS')
                and os.environ.get('AZURPILOT_ANDROID_CAPTURE', 'auto') != 'tcp'):
            retry_at = getattr(self, '_android_capture_retry_at', 0)
            if time.monotonic() >= retry_at:
                try:
                    client = getattr(self, '_android_frame_client', None)
                    if client is None:
                        client = self._android_frame_client = SharedFrameClient()
                    image = client.capture()
                    self.android_capture_transport = 'shared_memory'
                    return image
                except (OSError, ValueError, SharedFrameError) as error:
                    self._android_capture_retry_at = time.monotonic() + RETRY_SECONDS
                    module.logger.warning(f'Android 共享内存截图暂不可用，回退 TCP: {error}')
        self.android_capture_transport = 'tcp'
        return original(self)

    backend.screenshot_azurpilot_android = screenshot
    backend._android_capture_adapter = True


class _Loader(importlib.abc.Loader):
    """在原加载器及其它设备适配器之后接线。 / Connects after original loading and other device adapters."""

    def __init__(self, original):
        self.original = original

    def create_module(self, spec):
        return self.original.create_module(spec)

    def exec_module(self, module):
        self.original.exec_module(module)
        patch_ap_module(module)
        _patch_primary_display(module)


class _Finder(importlib.abc.MetaPathFinder):
    """委托后续 finder，兼容虚拟屏前台检测的同模块钩子。

    Delegates to subsequent finders, composing with app-detection hooks on the same module.
    """

    _android_capture_adapter = True

    def find_spec(self, fullname, path, target=None):
        if fullname != TARGET_MODULE:
            return None
        remaining = sys.meta_path[sys.meta_path.index(self) + 1:]
        for finder in remaining:
            spec = finder.find_spec(fullname, path, target)
            if spec is not None:
                if spec.loader:
                    spec.loader = _Loader(spec.loader)
                return spec
        return None


def _patch_primary_display(module):
    if os.environ.get('AZURPILOT_PRIMARY_SOCKET'):
        from android_primary_display import patch_ap_module as patch_primary
        patch_primary(module)


def install():
    """延迟安装截图通道；已导入模块立即接线，重复安装只注册一次。

    Installs capture lazily, immediately patches loaded modules, and registers only once.
    """
    module = sys.modules.get(TARGET_MODULE)
    if module is not None:
        patch_ap_module(module)
        _patch_primary_display(module)
    if not any(getattr(finder, '_android_capture_adapter', False) for finder in sys.meta_path):
        sys.meta_path.insert(0, _Finder())
