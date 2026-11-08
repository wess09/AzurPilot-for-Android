"""验证配置兼容层的描述符转发、patch 参数定位与导入 sanitize 接线。

Verifies the config compat layer's descriptor forwarding, patch argument
locating, and import sanitize wiring.
"""

import importlib.util
import inspect
import json
from pathlib import Path
import re
from types import SimpleNamespace
import unittest


OVERLAYS = Path(__file__).resolve().parents[1] / 'overlays'
spec = importlib.util.spec_from_file_location(
    'android_config_compat', OVERLAYS / 'android_config_compat.py')
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)


def change_of(change, key):
    """dict 与 pydantic 条目统一的字段读取。 / Uniform field read across dict and pydantic entries."""
    return change[key] if isinstance(change, dict) else getattr(change, key)


class ConfigCompatTest(unittest.TestCase):
    """钉住包装按上游真实签名转发，不再把静态方法当实例方法调用。

    Pins wrapper forwarding per the upstream signatures instead of calling
    static methods as instance methods.
    """

    MICROSECOND = '2026-11-08 12:00:00.123456'
    WHOLE_SECOND = '2026-11-08 12:00:00'

    def make_service(self, sync_kind):
        """提供按上游调用惯例行事的假 ConfigService。 / Supplies a fake ConfigService mirroring upstream conventions."""
        service = self

        class FakeConfigService:
            def validate(self, path, value):
                # 复刻上游整秒日期校验：微秒未截断会在这里炸出来
                if isinstance(value, str) and re.fullmatch(
                        r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}', value) is None:
                    raise ValueError(f'bad datetime: {path}')
                return path.split('.')

            if sync_kind == 'static':
                @staticmethod
                def _sync_record_time(fields, arg, now=None):
                    if arg.endswith('Enable'):
                        fields['EnableRecord'] = service.MICROSECOND
            else:
                def _sync_record_time(self, fields, arg, now=None):
                    if arg.endswith('Enable'):
                        fields['EnableRecord'] = service.MICROSECOND

            def patch(self, name, revision, changes, *, require_revision=False, dry_run=False):
                # 复刻上游 (name, revision, changes) 位置调用与 _sync_record_time 循环
                data = {}
                edited = {}
                for change in changes:
                    task, group, arg = self.validate(change_of(change, 'path'),
                                                     change_of(change, 'value'))
                    data.setdefault(task, {}).setdefault(group, {})[arg] = \
                        change_of(change, 'value')
                    edited.setdefault((task, group), set()).add(arg)
                for (task, group), args in edited.items():
                    fields = data[task][group]
                    for arg in args:
                        self._sync_record_time(fields, arg, None)
                return {'instance': name, 'revision': revision, 'values': data}

            def save_import(self, name, content):
                self.imported = (name, content)
                return {'instance': name}

        module = SimpleNamespace(ConfigService=FakeConfigService)
        compat.patch_ap_module(module)
        return FakeConfigService(), FakeConfigService

    def test_patch_with_staticmethod_sync_does_not_raise(self):
        """上游 staticmethod 签名下 patch 正常保存且时间戳被截断。

        Patching survives the upstream staticmethod signature and truncates dates.
        """
        svc, cls = self.make_service('static')
        result = svc.patch('ap', None, [{'path': 'Main.Scheduler.Enable', 'value': True}])
        self.assertIs(result['values']['Main']['Scheduler']['Enable'], True)
        self.assertEqual(result['values']['Main']['Scheduler']['EnableRecord'], self.WHOLE_SECOND)
        # 描述符形态必须保持 staticmethod，否则别的调用方会撞上绑定差异
        self.assertIsInstance(
            inspect.getattr_static(cls, '_sync_record_time'), staticmethod)

    def test_patch_with_instance_method_sync_still_works(self):
        """实例方法形态同样按原样转发。 / The instance-method form is forwarded untouched too."""
        svc, _ = self.make_service('instance')
        result = svc.patch('ap', None, [{'path': 'Main.Scheduler.Enable', 'value': True}])
        self.assertEqual(result['values']['Main']['Scheduler']['EnableRecord'], self.WHOLE_SECOND)

    def test_patch_sanitizes_changes_positional_and_object(self):
        """(name, revision, changes) 位置的 dict 与对象条目都被规整。

        Dict and object change entries are sanitized at the real positional slot.
        """
        svc, _ = self.make_service('static')
        changes = [
            {'path': 'Main.Campaign.Record', 'value': '2026-01-01T00:00:00.999999'},
            SimpleNamespace(path='Main.Emotion.Fleet1Value', value=-5),
        ]
        result = svc.patch('ap', None, changes)
        values = result['values']['Main']
        self.assertEqual(values['Campaign']['Record'], '2026-01-01 00:00:00')
        self.assertEqual(values['Emotion']['Fleet1Value'], 0)

    def test_patch_sanitizes_keyword_changes(self):
        """关键字传参的 changes 同样被规整。 / Keyword-passed changes are sanitized as well."""
        svc, _ = self.make_service('static')
        result = svc.patch('ap', None, changes=[
            {'path': 'Main.Campaign.Record', 'value': '2026-01-01T00:00:00.999999'}])
        self.assertEqual(result['values']['Main']['Campaign']['Record'], '2026-01-01 00:00:00')

    def test_save_import_sanitizes_content(self):
        """导入 sanitize 接到上游 save_import 上而非不存在的 import_config。

        Import sanitize wires onto upstream save_import, not the absent import_config.
        """
        svc, _ = self.make_service('static')
        content = json.dumps({'Main': {'Campaign': {'Record': '2026-01-01T00:00:00.5'}}})
        svc.save_import('ap', content)
        self.assertIn('2026-01-01 00:00:00', svc.imported[1])
        self.assertNotIn('.5', svc.imported[1])

    def test_install_is_idempotent(self):
        """重复安装直接短路，不会叠加包装。 / Repeated installs short-circuit without stacking wrappers."""
        module = SimpleNamespace(ConfigService=self.make_service('static')[1])
        compat.patch_ap_module(module)
        self.assertTrue(module._android_config_compat_installed)


if __name__ == '__main__':
    unittest.main()
