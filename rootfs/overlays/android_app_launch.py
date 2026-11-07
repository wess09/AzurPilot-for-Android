"""在 AP 启动游戏前尽力关闭兼容缩放，启动后请求虚拟屏窗口焦点。

Best-effort disables compatibility scaling before AP launches a game and requests virtual-window
focus afterward.
"""

import importlib.abc
import sys


TARGET_MODULE = 'module.device.method.azurpilot_android'


def patch_ap_module(module):
    """保留上游启动参数和等待行为，在支持的桥上尽力准备原生渲染并追加聚焦。

    Retains upstream launch arguments and wait behavior, best-effort preparing native rendering and
    adding focus on supporting bridges.
    """
    backend = module.AzurPilotAndroid
    if getattr(backend, '_android_launch_adapter', False):
        return
    original = backend.app_start_azurpilot_android

    def app_start(self, package=None, activity=None, wait=True):
        """尽力关闭兼容缩放后启动并聚焦指定包；准备与聚焦失败均只记录警告。

        Best-effort disables compatibility scaling, then launches and focuses the package. Both
        preparation and focus failures only warn.
        """
        target = package or self.package
        ping = self._azurpilot_android_call({'method': 'ping'})
        if ping.get('capabilities', {}).get('nativeGameResolution'):
            # 关兼容缩放只是尽力而为的优化：鸿蒙等系统的 APS 会拒绝该请求（status=-3），但游戏仍能在
            # 虚拟屏上正常渲染。若在此致命失败会连累后面的 app_focus——focus 缺失才会真正黑屏；
            # 最坏情况只是缩放没关掉，上游本就有针对缩放画面的重试逻辑，属可恢复软失败。
            try:
                response = self._azurpilot_android_call({
                    'method': 'app_prepare', 'package': target, 'displayId': ping['displayId'],
                })
                if not response.get('ok'):
                    module.logger.warning(
                        f'Android 原生分辨率准备失败，已跳过: {response.get("error", "unknown")}')
            except Exception as error:
                module.logger.warning(f'Android 原生分辨率准备暂不可用，已跳过: {error}')
        result = original(self, package=package, activity=activity, wait=wait)
        try:
            ping = self._azurpilot_android_call({'method': 'ping'})
            if not ping.get('capabilities', {}).get('appFocus'):
                module.logger.warning('Android 桥不支持启动聚焦，请更新 APK 并重启应用')
                return result
            response = self._azurpilot_android_call({
                'method': 'app_focus',
                'package': target,
                'displayId': ping['displayId'],
            })
            if not response.get('ok'):
                module.logger.warning(f'Android 启动聚焦失败: {response.get("error", "unknown")}')
        except Exception as error:
            # 聚焦是启动后的兼容步骤，不能把已成功的启动改判为失败。
            module.logger.warning(f'Android 启动聚焦暂不可用: {error}')
        return result

    backend.app_start_azurpilot_android = app_start
    backend._android_launch_adapter = True


class _Loader(importlib.abc.Loader):
    """在原模块和其它设备适配器初始化后接线。

    Connects after the original module and other device adapters initialize.
    """

    def __init__(self, original):
        self.original = original

    def create_module(self, spec):
        return self.original.create_module(spec)

    def exec_module(self, module):
        self.original.exec_module(module)
        patch_ap_module(module)


class _Finder(importlib.abc.MetaPathFinder):
    """委托后续 finder，保留同模块的截图与前台检测钩子。

    Delegates to subsequent finders, preserving capture and app-detection hooks on the module.
    """

    _android_launch_adapter = True

    def find_spec(self, fullname, path, target=None):
        if fullname != TARGET_MODULE:
            return None
        for finder in sys.meta_path[sys.meta_path.index(self) + 1:]:
            spec = finder.find_spec(fullname, path, target)
            if spec is not None:
                if spec.loader:
                    spec.loader = _Loader(spec.loader)
                return spec
        return None


def install():
    """延迟安装启动适配器；已导入模块立即接线，重复安装无副作用。

    Installs the launch adapter lazily, immediately patching loaded modules idempotently.
    """
    module = sys.modules.get(TARGET_MODULE)
    if module is not None:
        patch_ap_module(module)
    if not any(getattr(finder, '_android_launch_adapter', False) for finder in sys.meta_path):
        sys.meta_path.insert(0, _Finder())
