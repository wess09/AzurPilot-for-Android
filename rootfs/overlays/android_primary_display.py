"""将 Android 主屏模式路由到本 App 的无障碍服务；保留虚拟屏及远端设备后端。

Routes the Android primary-display mode to the app's accessibility service, preserving
virtual-display and remote device backends.
"""

from functools import cached_property, wraps
import json
import os
import socket
import time


SOCKET_ENV = 'AZURPILOT_PRIMARY_SOCKET'
LOCAL_VIRTUAL_BRIDGE = '127.0.0.1:22301'
WIDTH, HEIGHT, CHANNELS = 1280, 720, 3
MAX_REPLY_LINE = 4096


class PrimaryDisplayError(Exception):
    """保留宿主错误码，便于区分游戏切出和通道故障。

    Preserves the host error code to distinguish lost game focus from transport failure.
    """

    def __init__(self, message, code=None):
        super().__init__(message)
        self.code = code


def call(method, *, timeout=10, **fields):
    """向同 UID 的宿主发起一次有界请求，返回元数据及可选的 BGR 帧。

    Makes one bounded request to the same-UID host, returning metadata and optional BGR pixels.
    """
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(timeout)
        client.connect('\0' + os.environ[SOCKET_ENV])
        client.sendall(json.dumps({'method': method, **fields}).encode('utf-8') + b'\n')
        with client.makefile('rb') as stream:
            line = stream.readline(MAX_REPLY_LINE + 1)
            if len(line) > MAX_REPLY_LINE or not line.endswith(b'\n'):
                raise PrimaryDisplayError('Invalid primary display response header')
            reply = json.loads(line)
            if not isinstance(reply, dict):
                raise PrimaryDisplayError('Invalid primary display response')
            if not reply.get('ok'):
                raise PrimaryDisplayError(reply.get('error', 'Primary display failed'), reply.get('code'))
            frame = None
            if method == 'screencap':
                expected = {'width': WIDTH, 'height': HEIGHT, 'channels': CHANNELS,
                            'length': WIDTH * HEIGHT * CHANNELS}
                if any(reply.get(key) != value for key, value in expected.items()):
                    raise PrimaryDisplayError('Invalid primary display frame size')
                frame = stream.read(expected['length'])
                if len(frame) != expected['length']:
                    raise PrimaryDisplayError('Primary display connection closed mid-frame')
            return reply, frame


def patch_ap_module(module):
    """在既有 Android 适配器外侧路由主屏的截图、触摸与应用生命周期。

    Routes primary-display capture, touch, and app lifecycle outside the existing Android adapters.
    """
    backend = module.AzurPilotAndroid
    if getattr(backend, '_android_primary_adapter', False):
        return

    def device_call(method, **fields):
        from module.exception import GameNotRunningError, ScriptError

        try:
            return call(method, **fields)
        except PrimaryDisplayError as error:
            if error.code == 'game_not_foreground':
                raise GameNotRunningError(str(error)) from error
            raise ScriptError(str(error)) from error
        except (OSError, ValueError) as error:
            raise ScriptError(f'Primary display connection failed: {error}') from error

    @cached_property
    def primary_display(self):
        # 每个设备对象只选择一次，避免换屏后把旧坐标发送到另一块屏。
        if not os.environ.get(SOCKET_ENV) or self.azurpilot_android_addr != LOCAL_VIRTUAL_BRIDGE:
            return False
        return device_call('ping')[0].get('primary') is True

    primary_display.__set_name__(backend, '_android_primary_display')
    backend._android_primary_display = primary_display

    def route(method_name, handle):
        original = getattr(backend, method_name)

        @wraps(original)
        def dispatch(self, *args, **kwargs):
            if self._android_primary_display:
                return handle(self, *args, **kwargs)
            return original(self, *args, **kwargs)

        setattr(backend, method_name, dispatch)

    def screenshot(self):
        import numpy as np

        _, raw = device_call('screencap', package=self.package)
        return np.frombuffer(raw, dtype=np.uint8).reshape(HEIGHT, WIDTH, CHANNELS)[..., ::-1].copy()

    def swipe(self, p1, p2, duration=0.1):
        ms = max(1, int(duration * 1000))
        device_call('swipe', package=self.package, x1=int(p1[0]), y1=int(p1[1]),
                    x2=int(p2[0]), y2=int(p2[1]), duration=ms, timeout=max(10, ms / 1000 + 5))

    def app_start(self, package=None, activity=None, wait=True):
        from module.config.server import DICT_PACKAGE_TO_ACTIVITY
        from module.exception import ScriptError

        target = package or self.package
        activity = activity or DICT_PACKAGE_TO_ACTIVITY.get(target)
        if activity is None:
            raise ScriptError(f'No known activity for package: {target}')
        device_call('app_start', package=target, activity=activity)
        if wait:
            time.sleep(1)

    def app_current(self, timeout=30):
        return device_call('app_current', timeout=timeout)[0].get('package', '')

    route('screenshot_azurpilot_android', screenshot)
    route('click_azurpilot_android',
          lambda self, x, y: device_call('click', package=self.package, x=int(x), y=int(y)))
    route('swipe_azurpilot_android', swipe)
    route('long_click_azurpilot_android',
          lambda self, x, y, duration: swipe(self, (x, y), (x, y), duration))
    route('app_start_azurpilot_android', app_start)
    route('app_stop_azurpilot_android',
          lambda self, package=None: device_call('app_stop', package=package or self.package))
    route('app_current_azurpilot_android', app_current)
    route('azurpilot_android_shell', lambda self, cmd, timeout=30: {
        'ok': False, 'code': 1, 'stdout': '', 'stderr': 'Shell is unavailable in primary display mode',
    })
    backend._android_primary_adapter = True
