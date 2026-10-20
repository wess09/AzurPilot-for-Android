"""验证 Android 后台校时；复用原六项回归并测试宿主接线与进程恢复。

Tests Android background NTP, reusing six original regressions and adding host/fork coverage.
"""

import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
OVERLAY = ROOT / 'rootfs/overlays/android_ntp.py'
spec = importlib.util.spec_from_file_location('android_ntp', OVERLAY)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)

import os
import threading
import time
import unittest
from unittest.mock import Mock, patch

source = Path(os.environ['AZURPILOT_SOURCE']) / 'module/config/time_source.py'
spec = importlib.util.spec_from_file_location('ntp_core_fixture', source)
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)
adapter.patch_module(core)
NetworkTimeSource = core.NetworkTimeSource



class TestAsyncNtp(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(
            os.environ,
            {"AZURPILOT_NTP_DISABLE": "0", "AZURPILOT_NTP_SERVERS": "test.invalid"},
        )
        self.env.start()
        self.addCleanup(self.env.stop)
        self.source = NetworkTimeSource()
        self.source._log_info = Mock()
        self.source._log_warning = Mock()

    def finish(self):
        self.source._refresh_thread.join(2)
        self.assertFalse(self.source._refresh_thread.is_alive())

    def test_slow_dns_does_not_block_any_time_reader_or_duplicate_query(self):
        entered = threading.Event()
        release = threading.Event()

        def query(host):
            entered.set()
            release.wait(3)
            raise OSError("DNS 不可用")

        self.source._query_server = Mock(side_effect=query)
        try:
            results = []

            def read():
                results.extend(
                    [self.source.timestamp(), self.source.now(), self.source.status()]
                )

            reader = threading.Thread(target=read, daemon=True)
            reader.start()
            reader.join(1)
            self.assertFalse(reader.is_alive(), "读取时间被 DNS 阻塞")
            self.assertTrue(entered.wait(1))
            self.assertEqual(len(results), 3)
            self.assertLess(abs(results[0] - time.time()), 2)
            threads = [
                threading.Thread(
                    target=lambda: self.source.refresh(force=True), daemon=True
                )
                for _ in range(10)
            ]
            for t in threads:
                t.start()
            for t in threads:
                t.join(1)
                self.assertFalse(t.is_alive())
            self.source._query_server.assert_called_once()
            self.assertTrue(self.source._refresh_thread.daemon)
        finally:
            release.set()
            self.finish()

    def test_success_publishes_cached_time(self):
        self.source._query_server = Mock(return_value=12.5)
        self.source.refresh()
        self.finish()
        self.assertTrue(self.source.synced)
        self.assertAlmostEqual(self.source.timestamp() - time.time(), 12.5, delta=0.1)
        self.assertEqual(self.source.status()["server"], "test.invalid")
        self.source._query_server.assert_called_once()

    def test_failed_refresh_keeps_last_success(self):
        self.source._query_server = Mock(return_value=4)
        self.source.refresh()
        self.finish()
        baseline = (self.source.base_timestamp, self.source.base_monotonic)
        self.source._query_server = Mock(side_effect=OSError("DNS 不可用"))
        self.source.refresh(force=True)
        self.finish()
        self.assertTrue(self.source.synced)
        self.assertEqual(
            baseline, (self.source.base_timestamp, self.source.base_monotonic)
        )
        self.assertAlmostEqual(self.source.timestamp() - time.time(), 4, delta=0.1)

    def test_failure_backoff_prevents_query_on_every_screenshot(self):
        self.source._query_server = Mock(side_effect=OSError("DNS 不可用"))
        self.source.refresh()
        self.finish()
        for _ in range(20):
            self.source.timestamp()
            self.source.status()
        self.source._query_server.assert_called_once()
        self.assertGreater(self.source.retry_after_monotonic, time.monotonic())
        self.assertFalse(self.source._refreshing)
        self.source.refresh(force=True)
        self.finish()
        self.assertEqual(self.source._query_server.call_count, 2)

    def test_disabled_never_starts_thread(self):
        with patch.dict(os.environ, {"AZURPILOT_NTP_DISABLE": "1"}):
            self.source._query_server = Mock()
            self.assertAlmostEqual(self.source.timestamp(), time.time(), delta=0.1)
            self.assertIsNone(self.source._refresh_thread)
            self.source._query_server.assert_not_called()

    def test_expired_success_refreshes_in_background(self):
        self.source._query_server = Mock(return_value=1)
        self.source.refresh()
        self.finish()
        self.source.last_sync_monotonic -= self.source.refresh_interval + 1
        self.source.refresh()
        self.finish()
        self.assertEqual(self.source._query_server.call_count, 2)


class TestAndroidNtpIntegration(unittest.TestCase):
    """测试加载链路及原接口兼容，不请求真实服务器。

    Tests loading and public API compatibility without querying real servers.
    """

    def test_gate_only_enables_ntp_on_android(self):
        names = ('android_process_compat', 'android_equipment', 'android_device',
                 'android_app_launch', 'android_capture', 'android_opsi_event',
                 'android_update', 'android_ntp')
        modules = {name: SimpleNamespace(install=Mock()) for name in names}
        script = (ROOT / 'rootfs/overlays/sitecustomize.py').read_text(encoding='utf-8')
        with patch.dict(sys.modules, modules), patch.dict(os.environ, {}, clear=True):
            exec(script, {})
            modules['android_ntp'].install.assert_not_called()
            modules['android_opsi_event'].install.assert_not_called()
            os.environ['AZURPILOT_ANDROID'] = '1'
            exec(script, {})
            modules['android_ntp'].install.assert_called_once()
            modules['android_opsi_event'].install.assert_called_once()

    def test_packaging_sync_and_build_smoke(self):
        for rel in ('rootfs/build/build-azurpilot.sh', 'app/app/build.gradle.kts',
                    'app/app/src/main/kotlin/com/azurpilot/ghio/proot/ProotHost.kt'):
            self.assertIn('android_ntp.py', (ROOT / rel).read_text(encoding='utf-8'))
        self.assertIn('NTP_ADAPTER_OK', (ROOT / 'rootfs/build/build-azurpilot.sh').read_text(encoding='utf-8'))

    def test_install_is_idempotent_and_does_not_query(self):
        with patch.object(sys, 'meta_path', list(sys.meta_path)), patch.dict(sys.modules, {adapter.TARGET_MODULE: core}):
            method = core.NetworkTimeSource.refresh
            with patch.object(core.network_time, '_query_server') as query:
                adapter.install()
                adapter.install()
                query.assert_not_called()
            self.assertIs(core.NetworkTimeSource.refresh, method)
            self.assertEqual(sum(isinstance(item, adapter._Finder) for item in sys.meta_path), 1)

    def test_process_change_releases_inherited_inflight_flag_and_lock(self):
        source = NetworkTimeSource()
        previous_lock = source._lock
        source._refreshing = True
        source._refresh_thread = object()
        source._query_server = Mock(return_value=0)
        source._log_info = Mock()
        with patch.dict(os.environ, {'AZURPILOT_NTP_DISABLE': '0', 'AZURPILOT_NTP_SERVERS': 'test.invalid'}), \
                patch.object(adapter.os, 'getpid', return_value=source._ntp_pid + 1):
            source.refresh(force=True)
            source._refresh_thread.join(2)
        self.assertIsNot(source._lock, previous_lock)
        self.assertFalse(source._refreshing)
        source._query_server.assert_called_once()

    def test_thread_start_failure_does_not_leave_busy_flag(self):
        source = NetworkTimeSource()
        with patch.dict(os.environ, {'AZURPILOT_NTP_DISABLE': '0'}), \
                patch.object(adapter.threading.Thread, 'start', side_effect=RuntimeError('test start failure')):
            with self.assertRaises(RuntimeError):
                source.refresh()
        self.assertFalse(source._refreshing)

    def test_upstream_async_implementation_is_not_overridden(self):
        class Upstream:
            _refresh_in_background = Mock()
            refresh = Mock()
        module = SimpleNamespace(NetworkTimeSource=Upstream)
        method = Upstream.refresh
        adapter.patch_module(module)
        self.assertIs(Upstream.refresh, method)
        self.assertTrue(module._android_ntp_adapter)

    def test_public_functions_keep_existing_singleton_and_timezone(self):
        original = core.network_time
        with patch.dict(os.environ, {'AZURPILOT_NTP_DISABLE': '1'}):
            self.assertIs(core.network_time, original)
            self.assertIs(core.now().tzinfo, None)
            self.assertIsNotNone(core.utcnow().tzinfo)
            self.assertIsInstance(core.timestamp(), float)
            self.assertFalse(core.refresh_time(force=True))
            self.assertIn('synced', core.status())


if __name__ == '__main__':
    unittest.main()
