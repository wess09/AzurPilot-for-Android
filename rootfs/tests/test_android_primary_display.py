"""验证主屏桥的像素契约和与虚拟屏的隔离。

Verifies the primary-display bridge pixel contract and isolation from virtual displays.
"""

from io import BytesIO
from pathlib import Path
import json
import os
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, Mock, patch

import numpy as np


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'overlays'))
import android_primary_display as adapter


class PrimaryDisplayTest(unittest.TestCase):
    """验证运行时收到的图像、手势路由及可恢复错误。

    Verifies runtime image layout, gesture routing, and recoverable errors.
    """

    def setUp(self):
        self.game = 'com.bilibili.azurlane'
        self.errors = SimpleNamespace(
            GameNotRunningError=type('GameNotRunningError', (Exception,), {}),
            ScriptError=type('ScriptError', (Exception,), {}),
        )
        patches = [
            patch.dict(sys.modules, {
                'module.exception': self.errors,
                'module.config.server': SimpleNamespace(DICT_PACKAGE_TO_ACTIVITY={self.game: 'game.Main'}),
            }),
            patch.dict(os.environ, {adapter.SOCKET_ENV: 'test.primary.v1'}),
        ]
        for override in patches:
            override.start()
            self.addCleanup(override.stop)

    def _backend(self, address=adapter.LOCAL_VIRTUAL_BRIDGE):
        methods = [
            'screenshot_azurpilot_android', 'click_azurpilot_android', 'swipe_azurpilot_android',
            'long_click_azurpilot_android', 'app_start_azurpilot_android',
            'app_stop_azurpilot_android', 'app_current_azurpilot_android', 'azurpilot_android_shell',
        ]
        original = Mock(return_value='original bridge')
        backend = type('AzurPilotAndroid', (), dict.fromkeys(methods, original))
        adapter.patch_ap_module(SimpleNamespace(AzurPilotAndroid=backend))
        device = backend()
        device.azurpilot_android_addr = address
        device.package = self.game
        return device, original

    def test_primary_frames_are_rgb_and_controls_bypass_virtual_device(self):
        device, original = self._backend()
        raw = bytes((9, 25, 210)) * (1280 * 720)
        with patch.object(adapter, 'call', side_effect=[
            ({'primary': True}, None), ({}, raw), ({}, None), ({}, None), ({}, None), ({}, None),
        ]) as host:
            frame = device.screenshot_azurpilot_android()
            device.click_azurpilot_android(1279, 719)
            device.long_click_azurpilot_android(0, 0, 0.5)
            device.app_start_azurpilot_android(wait=False)
            device.app_stop_azurpilot_android()
            self.assertFalse(device.azurpilot_android_shell('unavailable')['ok'])
        self.assertEqual(frame.shape, (720, 1280, 3))
        self.assertTrue(frame.flags.c_contiguous)
        np.testing.assert_array_equal(frame[-1, -1], [210, 25, 9])
        self.assertEqual([c.args[0] for c in host.call_args_list],
                         ['ping', 'screencap', 'click', 'swipe', 'app_start', 'app_stop'])
        self.assertEqual(host.call_args_list[3].kwargs['duration'], 500)
        self.assertEqual(host.call_args_list[4].kwargs, {'package': self.game, 'activity': 'game.Main'})
        original.assert_not_called()

    def test_wrong_focus_is_recoverable_without_switching_to_virtual(self):
        device, original = self._backend()
        with patch.object(adapter, 'call', side_effect=[
            ({'primary': True}, None), adapter.PrimaryDisplayError('game not focused', 'game_not_foreground'),
        ]):
            with self.assertRaises(self.errors.GameNotRunningError):
                device.click_azurpilot_android(10, 10)
        original.assert_not_called()

    def test_virtual_and_remote_backends_keep_original_controls(self):
        local, _ = self._backend()
        remote, _ = self._backend('192.0.2.1:22301')
        with patch.object(adapter, 'call', return_value=({'primary': False}, None)) as host:
            self.assertEqual(local.click_azurpilot_android(9, 9), 'original bridge')
            self.assertEqual(local.screenshot_azurpilot_android(), 'original bridge')
            self.assertEqual(remote.screenshot_azurpilot_android(), 'original bridge')
            host.assert_called_once_with('ping')

    def test_invalid_dimensions_or_truncated_frame_fail_before_reshape(self):
        sock = MagicMock()
        sock.__enter__.return_value = sock
        base = {'ok': True, 'width': 1280, 'height': 720, 'channels': 3, 'length': 1280 * 720 * 3}
        for reply in (dict(base, width=720, height=1280), base):
            with self.subTest(reply=reply):
                sock.makefile.return_value = BytesIO(json.dumps(reply).encode() + b'\nshort')
                with (patch.object(adapter.socket, 'AF_UNIX', 1, create=True),
                      patch.object(adapter.socket, 'socket', return_value=sock)):
                    with self.assertRaises(adapter.PrimaryDisplayError):
                        adapter.call('screencap', package=self.game)


if __name__ == '__main__':
    unittest.main()
