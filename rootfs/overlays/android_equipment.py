"""将装备码的输入和导出接到 Android 特权桥，保留上游确认及换船保护。

仅在 Android 运行时安装延迟导入钩子；不提前加载 AP，不改写热更源码。
非 Android 控制方式继续使用上游实现。

Routes equipment-code input and export through the Android privileged bridge.

Installs a lazy import hook only in the Android runtime without loading AP early or
rewriting updated sources. Other control methods retain their upstream implementation,
including preview confirmation and equipment-transfer safeguards.
"""

import functools
import importlib.abc
import importlib.machinery
import sys


def patch_ap_module(module):
    """为装备码处理器接入有能力协商的 Android 后端；重复调用无副作用。

    Connects the equipment handler to the negotiated Android backend idempotently.
    """
    handler = module.EquipmentCodeHandler
    if getattr(handler, '_android_equipment_adapter', False):
        return

    def android_only(original, android):
        @functools.wraps(original)
        def dispatch(self, *args, **kwargs):
            if self.config.Emulator_ControlMethod == 'azurpilot_android':
                return android(self, *args, **kwargs)
            return original(self, *args, **kwargs)
        return dispatch

    def call(self, payload, default):
        try:
            return self.device._azurpilot_android_call_ok(payload)
        except (module.EmulatorNotRunningError, module.RequestHumanTakeover):
            raise
        except Exception as error:
            module.logger.warning(f'Android 装备码操作失败: {error}')
            return default

    def supported(self):
        response = call(self, {'method': 'ping'}, {})
        if response.get('capabilities', {}).get('equipmentCode') is True:
            return True
        module.logger.warning('当前 Android 桥不支持装备码，请更新 APK 并重启应用')
        return False

    def input_code(self, code):
        if not self._is_equipment_code(code):
            return False
        code = code.strip().strip('\'"')
        response = call(self, {'method': 'equipment_code_input', 'code': code}, None)
        return response is not None

    def read_code(self):
        response = call(self, {'method': 'equipment_code_read'}, {})
        code = response.get('code')
        if self._is_equipment_code(code):
            return code
        module.logger.warning('未从 Android 剪贴板读取到有效装备码')
        return None

    def input_with_preview(self, code):
        # 新版的备用 IME 走主屏设备接口；虚拟屏只使用 Android 桥。
        for _ in range(2):
            click_timer = module.Timer(1, count=3)
            textbox_clicked = False
            for _ in self.loop(timeout=5):
                if textbox_clicked and self._code_input_adb(code):
                    break
                if click_timer.reached_and_reset():
                    self.device.click(module.EQUIPMENT_CODE_TEXTBOX)
                    textbox_clicked = True
            else:
                continue
            if self._code_wait_preview_loaded():
                return True
        module.logger.warning('装备码加载失败')
        return False

    def export_code(self):
        # 上游在信息栏确认本次复制后才能读取，避免导出上次留在剪贴板的码。
        return read_code(self) if self._code_copy() else None

    replacements = {
        'equipment_code_supported': supported,
        '_code_input_adb': input_code,
    }
    if hasattr(handler, '_code_copy'):
        replacements.update(_code_input=input_with_preview, _code_export=export_code)
    else:
        replacements.update(set_fastinput_ime=lambda self: None,
                            _code_input_uiautomator2=lambda self, code: False,
                            _clipboard_get=read_code)
    for name, replacement in replacements.items():
        original = getattr(handler, name, None)
        if original is not None:
            setattr(handler, name, android_only(original, replacement))
    handler._android_equipment_adapter = True


class _Loader(importlib.abc.Loader):
    """在上游模块完成初始化后接线。 / Connects after upstream module initialization."""

    def __init__(self, original):
        self.original = original

    def create_module(self, spec):
        return self.original.create_module(spec)

    def exec_module(self, module):
        self.original.exec_module(module)
        patch_ap_module(module)


class _Finder(importlib.abc.MetaPathFinder):
    """只拦截装备码模块。 / Intercepts only the equipment-code module."""

    _android_equipment_adapter = True

    def find_spec(self, fullname, path, target=None):
        if fullname != 'module.equipment.equipment_code':
            return None
        spec = importlib.machinery.PathFinder.find_spec(fullname, path)
        if spec and spec.loader:
            spec.loader = _Loader(spec.loader)
        return spec


def install():
    """延迟接入 AP 装备码；已导入时立即接线，重复安装无副作用。

    Lazily connects AP equipment codes, patching already imported modules idempotently.
    """
    module = sys.modules.get('module.equipment.equipment_code')
    if module is not None:
        patch_ap_module(module)
    if not any(getattr(finder, '_android_equipment_adapter', False) for finder in sys.meta_path):
        sys.meta_path.insert(0, _Finder())
