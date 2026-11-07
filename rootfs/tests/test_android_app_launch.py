"""验证原生分辨率准备、启动聚焦、桥能力协商、失败处理及导入钩子组合。

Verifies native-resolution preparation, launch focus, negotiation, failure handling, and import hooks.
"""

import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


OVERLAYS = Path(__file__).resolve().parents[1] / 'overlays'
spec = importlib.util.spec_from_file_location('android_app_launch', OVERLAYS / 'android_app_launch.py')
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)
GAME = 'com.bilibili.azurlane'


class LaunchAdapterTest(unittest.TestCase):
    """钉住聚焦仅在启动成功后追加，并保留上游启动契约。

    Pins focus after successful launch while preserving the upstream launch contract.
    """

    def fixture(self):
        """提供可观测调用顺序的设备契约。 / Supplies a device contract with observable call ordering."""
        calls = Mock()

        class Backend:
            app_start_azurpilot_android = calls.launch

        module = SimpleNamespace(AzurPilotAndroid=Backend, logger=Mock())
        adapter.patch_ap_module(module)
        device = Backend()
        device.package = GAME
        device._azurpilot_android_call = calls.bridge
        calls.launch.return_value = 'upstream result'
        ping = {'ok': True, 'displayId': 7,
                'capabilities': {'appFocus': True, 'nativeGameResolution': True}}
        calls.bridge.side_effect = [ping, {'ok': True}, ping, {'ok': True}]
        return module, device, calls

    def test_explicit_launch_arguments_and_order_are_preserved(self):
        _, device, calls = self.fixture()
        self.assertEqual(device.app_start_azurpilot_android('com.other', '.Main', False),
                         'upstream result')
        self.assertEqual([call[0] for call in calls.mock_calls],
                         ['bridge', 'bridge', 'launch', 'bridge', 'bridge'])
        self.assertEqual(calls.bridge.call_args_list[1].args[0], {
            'method': 'app_prepare', 'package': 'com.other', 'displayId': 7,
        })
        calls.launch.assert_called_once_with(device, package='com.other', activity='.Main', wait=False)
        self.assertEqual(calls.bridge.call_args.args[0], {
            'method': 'app_focus', 'package': 'com.other', 'displayId': 7,
        })

    def test_default_package_comes_from_configuration(self):
        _, device, calls = self.fixture()
        device.app_start_azurpilot_android()
        calls.launch.assert_called_once_with(device, package=None, activity=None, wait=True)
        self.assertEqual(calls.bridge.call_args.args[0]['package'], GAME)

    def test_original_failure_does_not_request_focus(self):
        _, device, calls = self.fixture()
        calls.launch.side_effect = RuntimeError('launch failed')
        with self.assertRaisesRegex(RuntimeError, 'launch failed'):
            device.app_start_azurpilot_android()
        self.assertEqual([call.args[0]['method'] for call in calls.bridge.call_args_list],
                         ['ping', 'app_prepare'])

    def test_old_bridge_keeps_original_launch_without_unknown_endpoint(self):
        module, device, calls = self.fixture()
        calls.bridge.side_effect = [{'ok': True, 'displayId': 7}] * 2
        self.assertEqual(device.app_start_azurpilot_android(), 'upstream result')
        self.assertEqual([call.args[0] for call in calls.bridge.call_args_list],
                         [{'method': 'ping'}] * 2)
        module.logger.warning.assert_called_once()

    def test_focus_rejection_keeps_launch_and_warns(self):
        module, device, calls = self.fixture()
        calls.bridge.side_effect = [
            {'displayId': 7},
            {'displayId': 7, 'capabilities': {'appFocus': True}},
            {'ok': False, 'error': 'no ready target window'},
        ]
        self.assertEqual(device.app_start_azurpilot_android(), 'upstream result')
        self.assertIn('no ready target window', module.logger.warning.call_args.args[0])

    def test_bridge_disconnect_keeps_launch_and_warns(self):
        module, device, calls = self.fixture()
        calls.bridge.side_effect = [{'displayId': 7}, OSError('disconnected')]
        self.assertEqual(device.app_start_azurpilot_android(), 'upstream result')
        module.logger.warning.assert_called_once()

    def test_display_recreation_is_not_cached(self):
        _, device, calls = self.fixture()
        calls.bridge.side_effect = [
            {'displayId': 7},
            {'displayId': 7, 'capabilities': {'appFocus': True}}, {'ok': True},
            {'displayId': 9},
            {'displayId': 9, 'capabilities': {'appFocus': True}}, {'ok': True},
        ]
        device.app_start_azurpilot_android()
        device.app_start_azurpilot_android()
        requests = [call.args[0] for call in calls.bridge.call_args_list]
        self.assertEqual([r['displayId'] for r in requests if r['method'] == 'app_focus'], [7, 9])

    def test_prepare_denial_warns_and_still_launches_and_focuses(self):
        module, device, calls = self.fixture()
        calls.bridge.side_effect = [
            {'displayId': 7, 'capabilities': {'nativeGameResolution': True}},
            {'ok': False, 'error': 'APS refused native resolution (status=-3)'},
            {'displayId': 7, 'capabilities': {'appFocus': True}},
            {'ok': True},
        ]
        self.assertEqual(device.app_start_azurpilot_android(), 'upstream result')
        calls.launch.assert_called_once()
        self.assertIn('APS refused native resolution (status=-3)',
                      module.logger.warning.call_args.args[0])
        self.assertEqual([call.args[0]['method'] for call in calls.bridge.call_args_list],
                         ['ping', 'app_prepare', 'ping', 'app_focus'])

    def test_prepare_disconnect_warns_and_still_launches(self):
        module, device, calls = self.fixture()
        calls.bridge.side_effect = [
            {'displayId': 7, 'capabilities': {'nativeGameResolution': True}},
            OSError('disconnected'),
            {'displayId': 7, 'capabilities': {'appFocus': True}},
            {'ok': True},
        ]
        self.assertEqual(device.app_start_azurpilot_android(), 'upstream result')
        calls.launch.assert_called_once()
        module.logger.warning.assert_called_once()

    def test_prepare_missing_display_id_warns_and_still_launches(self):
        module, device, calls = self.fixture()
        calls.bridge.side_effect = [
            {'capabilities': {'nativeGameResolution': True}},
            {'displayId': 7, 'capabilities': {'appFocus': True}},
            {'ok': True},
        ]
        self.assertEqual(device.app_start_azurpilot_android(), 'upstream result')
        calls.launch.assert_called_once()
        module.logger.warning.assert_called_once()

    def test_focus_only_bridge_still_launches_without_prepare(self):
        _, device, calls = self.fixture()
        ping = {'displayId': 7, 'capabilities': {'appFocus': True}}
        calls.bridge.side_effect = [ping, ping, {'ok': True}]
        device.app_start_azurpilot_android()
        self.assertEqual([call.args[0]['method'] for call in calls.bridge.call_args_list],
                         ['ping', 'ping', 'app_focus'])

    def test_each_restart_prepares_resolution_again(self):
        _, device, calls = self.fixture()
        pings = [{'displayId': display_id, 'capabilities': {'nativeGameResolution': True,
                                                          'appFocus': True}}
                 for display_id in (7, 9)]
        calls.bridge.side_effect = [pings[0], {'ok': True}, pings[0], {'ok': True},
                                   pings[1], {'ok': True}, pings[1], {'ok': True}]
        device.app_start_azurpilot_android()
        device.app_start_azurpilot_android()
        self.assertEqual([call.args[0]['displayId'] for call in calls.bridge.call_args_list
                          if call.args[0]['method'] == 'app_prepare'], [7, 9])

    def test_install_is_idempotent_for_loaded_modules(self):
        module, device, calls = self.fixture()
        current = type(device).app_start_azurpilot_android
        with patch.dict(sys.modules, {adapter.TARGET_MODULE: module}), \
                patch.object(sys, 'meta_path', list(sys.meta_path)):
            adapter.install()
            adapter.install()
            self.assertIs(type(device).app_start_azurpilot_android, current)
            self.assertEqual(sum(getattr(finder, '_android_launch_adapter', False)
                                 for finder in sys.meta_path), 1)
        device.app_start_azurpilot_android()
        self.assertEqual(calls.launch.call_count, 1)

    def test_lazy_finder_delegates_to_other_device_adapters(self):
        finder = adapter._Finder()
        next_finder = Mock()
        original_loader = Mock()
        original_loader.create_module.return_value = None
        spec = SimpleNamespace(loader=original_loader)
        next_finder.find_spec.return_value = spec
        with patch.object(sys, 'meta_path', [finder, next_finder]):
            self.assertIsNone(finder.find_spec('module.device.device', None))
            next_finder.find_spec.assert_not_called()
            found = finder.find_spec(adapter.TARGET_MODULE, ['runtime'])
            next_finder.find_spec.assert_called_once_with(adapter.TARGET_MODULE, ['runtime'], None)
            self.assertIsNone(found.loader.create_module(found))
            module = SimpleNamespace()
            with patch.object(adapter, 'patch_ap_module') as connect:
                found.loader.exec_module(module)
                original_loader.exec_module.assert_called_once_with(module)
                connect.assert_called_once_with(module)


if __name__ == '__main__':
    unittest.main()
