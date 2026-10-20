"""验证 Android 装备码后端的能力协商、故障保护及延迟接线。

Checks Android equipment-code negotiation, failure handling, and lazy integration.
"""

import importlib.util
from pathlib import Path
import re
import sys
from types import SimpleNamespace as Namespace
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location(
    'android_equipment', Path(__file__).parents[1] / 'overlays/android_equipment.py'
)
adapter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adapter)
CODE = 'MC8wLzAvMC8wXDA='


class EquipmentAdapterTest(unittest.TestCase):
    """覆盖桥接失败和非 Android 后端的隔离。 / Covers failures and other-backend isolation."""

    def fixture(self, method='azurpilot_android'):
        """提供只暴露上游接线契约的处理器。 / Provides the upstream integration contract."""
        class Handler:
            @staticmethod
            def _is_equipment_code(code):
                if not isinstance(code, str):
                    return False
                code = code.strip().strip('\'"')
                return len(code) >= 16 and len(code) % 4 == 0 and bool(
                    re.fullmatch(r'[A-Za-z0-9+/]+={0,2}', code)
                )

        names = ('equipment_code_supported', 'set_fastinput_ime', '_code_input_adb',
                 '_code_input_uiautomator2', '_clipboard_get')
        originals = {name: Mock(return_value=name) for name in names}
        for name, original in originals.items():
            setattr(Handler, name, original)
        module = Namespace(EquipmentCodeHandler=Handler, logger=Mock(),
                           EmulatorNotRunningError=type('EmulatorNotRunningError', (Exception,), {}),
                           RequestHumanTakeover=type('RequestHumanTakeover', (Exception,), {}))
        adapter.patch_ap_module(module)
        handler = Handler()
        handler.config = Namespace(Emulator_ControlMethod=method)
        handler.device = Namespace(_azurpilot_android_call_ok=Mock())
        return module, handler, originals

    def test_android_requires_explicit_host_capability(self):
        for response, expected in (({'capabilities': {'equipmentCode': True}}, True),
                                   ({'displayId': 4}, False),
                                   ({'capabilities': {'equipmentCode': False}}, False)):
            with self.subTest(response=response):
                _, handler, originals = self.fixture()
                handler.device._azurpilot_android_call_ok.return_value = response
                self.assertEqual(handler.equipment_code_supported(), expected)
                handler.device._azurpilot_android_call_ok.assert_called_once_with({'method': 'ping'})
                originals['equipment_code_supported'].assert_not_called()

    def test_base64_special_characters_are_sent_unchanged(self):
        _, handler, originals = self.fixture()
        code = 'AbCdEf012345+/=='
        self.assertTrue(handler._code_input_adb(' "' + code + '" '))
        handler.device._azurpilot_android_call_ok.assert_called_once_with(
            {'method': 'equipment_code_input', 'code': code}
        )
        originals['_code_input_adb'].assert_not_called()

    def test_invalid_code_never_reaches_input_bridge(self):
        _, handler, _ = self.fixture()
        for code in ('', None, CODE + ';reboot', CODE + '\nreboot', 'not base64'):
            self.assertFalse(handler._code_input_adb(code))
        handler.device._azurpilot_android_call_ok.assert_not_called()

    def test_export_accepts_only_valid_code(self):
        _, handler, originals = self.fixture()
        for code, expected in ((CODE, CODE), (None, None), ('unrelated text', None)):
            handler.device._azurpilot_android_call_ok.return_value = {'code': code}
            self.assertEqual(handler._clipboard_get(), expected)
        self.assertEqual(handler.device._azurpilot_android_call_ok.call_args.args,
                         ({'method': 'equipment_code_read'},))
        originals['_clipboard_get'].assert_not_called()

    def test_android_never_enables_fastinput_or_falls_back_to_u2(self):
        _, handler, originals = self.fixture()
        self.assertIsNone(handler.set_fastinput_ime())
        self.assertFalse(handler._code_input_uiautomator2(CODE))
        handler.device._azurpilot_android_call_ok.assert_not_called()
        originals['set_fastinput_ime'].assert_not_called()
        originals['_code_input_uiautomator2'].assert_not_called()

    def test_bridge_errors_stop_input_and_export(self):
        _, handler, _ = self.fixture()
        handler.device._azurpilot_android_call_ok.side_effect = RuntimeError('injection denied')
        self.assertFalse(handler.equipment_code_supported())
        self.assertFalse(handler._code_input_adb(CODE))
        self.assertIsNone(handler._clipboard_get())

    def test_takeover_errors_keep_scheduler_semantics(self):
        module, handler, _ = self.fixture()
        for error in (module.EmulatorNotRunningError, module.RequestHumanTakeover):
            handler.device._azurpilot_android_call_ok.side_effect = error
            for operation in (handler.equipment_code_supported,
                              lambda: handler._code_input_adb(CODE), handler._clipboard_get):
                with self.assertRaises(error):
                    operation()

    def test_other_backends_keep_all_original_methods(self):
        for method in ('uiautomator2', 'minitouch', 'MaaTouch', 'ADB'):
            module, handler, originals = self.fixture(method)
            adapter.patch_ap_module(module)
            for name, original in originals.items():
                args = (CODE,) if name.startswith('_code_input') else ()
                self.assertEqual(getattr(handler, name)(*args), name)
                original.assert_called_once_with(handler, *args)
            handler.device._azurpilot_android_call_ok.assert_not_called()

    def test_install_patches_loaded_module_idempotently(self):
        module, handler, _ = self.fixture()
        original = type(handler)._code_input_adb
        with patch.dict(sys.modules, {'module.equipment.equipment_code': module}), \
                patch.object(sys, 'meta_path', list(sys.meta_path)):
            adapter.install()
            adapter.install()
            self.assertIs(type(handler)._code_input_adb, original)
            self.assertEqual(sum(getattr(f, '_android_equipment_adapter', False)
                                 for f in sys.meta_path), 1)

    def test_lazy_loader_patches_after_import_without_loading_other_modules(self):
        module, _, _ = self.fixture()
        loader = Mock()
        loader.create_module.return_value = None
        finder = adapter._Finder()
        with patch.object(adapter.importlib.machinery.PathFinder, 'find_spec') as find:
            self.assertIsNone(finder.find_spec('module.device.device', None))
            find.assert_not_called()
            spec = Namespace(loader=loader)
            find.return_value = spec
            found = finder.find_spec('module.equipment.equipment_code', ['runtime'])
            self.assertIsNone(found.loader.create_module(found))
            with patch.object(adapter, 'patch_ap_module') as connect:
                found.loader.exec_module(module)
                loader.exec_module.assert_called_once_with(module)
                connect.assert_called_once_with(module)

    def test_sitecustomize_gates_equipment_install_to_android_runtime(self):
        site = Path(__file__).parents[1] / 'overlays/sitecustomize.py'
        equipment = Mock()
        process = Mock()
        update = Mock()
        device = Mock()
        launch = Mock()
        capture = Mock()
        opsi_event = Mock()
        ntp = Mock()
        with patch.dict(sys.modules, android_equipment=equipment, android_process_compat=process,
                        android_update=update, android_device=device, android_app_launch=launch,
                        android_capture=capture, android_opsi_event=opsi_event, android_ntp=ntp):
            for enabled in ('0', '1'):
                with patch.dict('os.environ', {'AZURPILOT_ANDROID': enabled,
                                              'AZURPILOT_OCR_ADDRESS': ''}):
                    exec(compile(site.read_text(encoding='utf-8'), str(site), 'exec'), {})
                self.assertEqual(equipment.install.call_count, int(enabled))
                self.assertEqual(update.install.call_count, int(enabled))
                self.assertEqual(device.install.call_count, int(enabled))
                self.assertEqual(launch.install.call_count, int(enabled))
                self.assertEqual(capture.install.call_count, int(enabled))
                self.assertEqual(opsi_event.install.call_count, int(enabled))
                self.assertEqual(ntp.install.call_count, int(enabled))


if __name__ == '__main__':
    unittest.main()
