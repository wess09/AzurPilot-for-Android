"""验证 Android 自律寻敌两帧确认 overlay：两帧门槛、入口清零、续接 ON 回放、上游让位与分发接线。

Tests the Android two-frame auto-search overlay: the two-frame gate, entry
resets, restart-ON replay, yielding to native upstream state, and APK wiring.
"""

import importlib.util
import sys
import unittest
from pathlib import Path
from types import ModuleType

ROOT = Path(__file__).resolve().parents[2]
OVERLAY = ROOT / 'rootfs/overlays/android_auto_search.py'
spec = importlib.util.spec_from_file_location('android_auto_search', OVERLAY)
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)

# OFF 哨兵用可区分的对象：两张不同对象都命中 OFF，任何非 OFF 值都不命中。
OFF_FRAME_1 = object()
OFF_FRAME_2 = object()
ON_FRAME = object()


def build_fake_hierarchy():
    """按真实继承链构造假模块：AutoSearchCombat → Combat → AutoSearchHandler。

    Builds fake modules mirroring the real inheritance chain so marker
    inheritance regressions can surface.
    """
    handler_module = ModuleType('module.handler.auto_search')

    class AutoSearchHandler:
        _auto_search_offset = (5, 5)

        def __init__(self):
            self.device = ModuleType('device')
            self.device.image = None
            self.clicks = []
            self.pending_at_call = []

        def match_template_color(self, button, offset=None, threshold=None):
            return self.device.image in (OFF_FRAME_1, OFF_FRAME_2)

        def appear_then_click(self, button, interval=None):
            self.clicks.append(button)
            return True

        def handle_auto_search_map_option(self):
            self.pending_at_call.append(
                ('base', self.__dict__.get('_auto_search_off_frame')))
            button = handler_module.AUTO_SEARCH_MAP_OPTION_OFF
            if not self.match_template_color(button,
                                             offset=self._auto_search_offset,
                                             threshold=30):
                self._auto_search_off_frame = None
                return False
            if self._auto_search_off_frame is None:
                self._auto_search_off_frame = self.device.image
                return False
            if self._auto_search_off_frame is self.device.image:
                return False
            self._auto_search_off_frame = None
            return self.appear_then_click(button, interval=2)

    handler_module.AutoSearchHandler = AutoSearchHandler
    handler_module.AUTO_SEARCH_MAP_OPTION_OFF = 'AUTO_SEARCH_MAP_OPTION_OFF'

    combat_module = ModuleType('module.combat.auto_search_combat')

    class Combat(AutoSearchHandler):
        pass

    class AutoSearchCombat(Combat):
        entries = []

        def __init__(self):
            super().__init__()
            self.pending_at_entries = []
            self.running_checks = 0

        def _note(self, name):
            AutoSearchCombat.entries.append(name)
            self.pending_at_entries.append((name,
                                            self.__dict__.get('_auto_search_off_frame')))

        def prepare_auto_search_recovery(self, state):
            self._note('prepare')

        def map_offensive_auto_search(self):
            self._note('offensive')

        def auto_search_moving(self):
            self._note('moving')

        def auto_search_combat_execute(self, emotion_reduce=None, fleet_index=None):
            self._note('execute')

        def auto_search_combat_status(self):
            self._note('status')

        def is_auto_search_running(self):
            self.running_checks += 1
            return self.device.image is ON_FRAME

        def handle_auto_search_map_option(self):
            """复刻上游续接 override 控制流：guard 开启时 ON 提前返回，不走基类。"""
            if not self.__dict__.get('_restart_resume_guard', False):
                return super().handle_auto_search_map_option()
            if self.is_auto_search_running():
                self.pending_at_call.append(('resume-on', 'early-return'))
                return False
            return super().handle_auto_search_map_option()

    combat_module.AutoSearchCombat = AutoSearchCombat
    return handler_module, combat_module


class TwoFrameGateTests(unittest.TestCase):
    def setUp(self):
        self.handler, self.combat = build_fake_hierarchy()
        adapter.patch_handler(self.handler)
        adapter.patch_combat(self.combat)
        self.cls = self.handler.AutoSearchHandler
        self.obj = self.cls()

    def mark(self, image):
        """设置当前截图。 / Sets the current screenshot."""
        self.obj.device.image = image

    def test_first_off_frame_only_records_without_clicking(self):
        self.mark(OFF_FRAME_1)
        self.assertFalse(self.obj.handle_auto_search_map_option())
        self.assertEqual(self.obj.clicks, [])
        self.assertIs(self.obj._auto_search_off_frame, OFF_FRAME_1)

    def test_same_frame_repeat_never_clicks(self):
        self.mark(OFF_FRAME_1)
        self.obj.handle_auto_search_map_option()
        for _ in range(3):
            self.assertFalse(self.obj.handle_auto_search_map_option())
        self.assertEqual(self.obj.clicks, [])

    def test_distinct_second_frame_clicks_once(self):
        self.mark(OFF_FRAME_1)
        self.obj.handle_auto_search_map_option()
        self.mark(OFF_FRAME_2)
        self.assertTrue(self.obj.handle_auto_search_map_option())
        self.assertEqual(self.obj.clicks, ['AUTO_SEARCH_MAP_OPTION_OFF'])
        self.assertIsNone(self.obj._auto_search_off_frame)

    def test_non_off_frame_resets_pending_confirmation(self):
        self.mark(OFF_FRAME_1)
        self.obj.handle_auto_search_map_option()
        self.mark(ON_FRAME)
        self.assertFalse(self.obj.handle_auto_search_map_option())
        self.assertIsNone(self.obj._auto_search_off_frame)
        self.mark(OFF_FRAME_2)
        self.assertFalse(self.obj.handle_auto_search_map_option())
        self.assertEqual(self.obj.clicks, [])
        self.mark(OFF_FRAME_1)
        self.assertTrue(self.obj.handle_auto_search_map_option())
        self.assertEqual(self.obj.clicks, ['AUTO_SEARCH_MAP_OPTION_OFF'])

    def test_combat_inherits_handler_patch_and_entries_reset_before_body(self):
        """继承链下 combat 必须完成 wrap，且入口方法体内看到的 pending 已清零。"""
        combat_cls = self.combat.AutoSearchCombat
        for name in adapter.ENTRY_RESETS:
            self.assertTrue(
                getattr(combat_cls.__dict__[name], '_android_auto_search_reset', False),
                name)
        obj2 = combat_cls()
        for call in (lambda: obj2.prepare_auto_search_recovery('map'),
                     obj2.map_offensive_auto_search,
                     obj2.auto_search_moving,
                     lambda: obj2.auto_search_combat_execute(),
                     obj2.auto_search_combat_status):
            obj2._auto_search_off_frame = 'stale-frame'
            call()
        for name, pending in obj2.pending_at_entries:
            self.assertIsNone(pending, f'{name} 方法体内 pending 未清零')

    def test_restart_resume_on_resets_pending_before_later_off(self):
        """guard 开启时 OFF₁→ON→OFF₂ 必须重新累计，OFF₂ 不得立即点击。"""
        obj = self.combat.AutoSearchCombat()
        obj.__dict__['_restart_resume_guard'] = True
        obj.device.image = OFF_FRAME_1
        obj.handle_auto_search_map_option()
        obj.device.image = ON_FRAME
        self.assertFalse(obj.handle_auto_search_map_option())
        obj.device.image = OFF_FRAME_2
        self.assertFalse(obj.handle_auto_search_map_option())
        self.assertEqual(obj.clicks, [])
        # OFF₂ 重新累计为新帧，而非沿用 OFF₁ 的残留确认。
        self.assertIs(obj._auto_search_off_frame, OFF_FRAME_2)
        obj.device.image = OFF_FRAME_1
        self.assertTrue(obj.handle_auto_search_map_option())
        self.assertEqual(obj.clicks, ['AUTO_SEARCH_MAP_OPTION_OFF'])

    def test_resume_on_reset_only_applies_when_guard_enabled(self):
        """guard 未开启（正常流程）时 override 直接走基类，不额外求值 ON。"""
        obj = self.combat.AutoSearchCombat()
        obj.device.image = ON_FRAME
        self.assertFalse(obj.handle_auto_search_map_option())
        self.assertEqual(
            [tag for tag, _ in obj.pending_at_call if tag == 'resume-on'], [])
        self.assertEqual(obj.running_checks, 0)

    def test_install_with_both_modules_loaded_wraps_combat(self):
        """时序一：handler、combat 均已加载后 install，combat 入口必须完成 wrap。

        必须用全新的未 patch 模块：复用 setUp 已 patch 的类会因 marker 跳过而假绿。
        """
        handler, combat = build_fake_hierarchy()
        sys.modules[adapter.TARGET_HANDLER] = handler
        sys.modules[adapter.TARGET_COMBAT] = combat
        try:
            adapter.install()
        finally:
            for name in (adapter.TARGET_HANDLER, adapter.TARGET_COMBAT):
                sys.modules.pop(name, None)
        self.assertFalse(
            handler.AutoSearchHandler.__dict__.get(adapter.NATIVE_MARKER, False))
        combat_cls = combat.AutoSearchCombat
        for name in adapter.ENTRY_RESETS:
            self.assertTrue(
                getattr(combat_cls.__dict__[name],
                        '_android_auto_search_reset', False), name)
        self.assertTrue(
            getattr(combat_cls.__dict__.get('handle_auto_search_map_option'),
                    '_android_auto_search_reset', False))

    def test_import_order_handler_then_combat_wraps_combat(self):
        """时序二：install 后经 Finder 依次导入 handler、combat，combat 必须完成 wrap。"""
        adapter.install()
        try:
            fresh_handler, fresh_combat = build_fake_hierarchy()
            sys.modules[adapter.TARGET_HANDLER] = fresh_handler
            adapter.patch_module(fresh_handler)
            sys.modules[adapter.TARGET_COMBAT] = fresh_combat
            adapter.patch_module(fresh_combat)
        finally:
            for name in (adapter.TARGET_HANDLER, adapter.TARGET_COMBAT):
                sys.modules.pop(name, None)
            sys.meta_path[:] = [f for f in sys.meta_path if type(f) is not adapter._Finder]
        combat_cls = fresh_combat.AutoSearchCombat
        for name in adapter.ENTRY_RESETS:
            self.assertTrue(
                getattr(combat_cls.__dict__[name],
                        '_android_auto_search_reset', False), name)

    def test_native_upstream_keeps_method_identity_in_both_orders(self):
        """上游原生时两种时序下 combat 方法身份均不变（整体让位）。"""
        for loaded_first in (True, False):
            handler, combat = build_fake_hierarchy()
            setattr(handler.AutoSearchHandler, '_auto_search_off_frame', 'native')
            original = combat.AutoSearchCombat.auto_search_moving
            if loaded_first:
                sys.modules[adapter.TARGET_HANDLER] = handler
                sys.modules[adapter.TARGET_COMBAT] = combat
                try:
                    adapter.install()
                finally:
                    for name in (adapter.TARGET_HANDLER, adapter.TARGET_COMBAT):
                        sys.modules.pop(name, None)
            else:
                sys.modules[adapter.TARGET_HANDLER] = handler
                try:
                    adapter.patch_handler(handler)
                    adapter.patch_combat(combat)
                finally:
                    sys.modules.pop(adapter.TARGET_HANDLER, None)
            self.assertIs(combat.AutoSearchCombat.auto_search_moving, original,
                          f'loaded_first={loaded_first}')
            self.assertTrue(vars(handler.AutoSearchHandler).get(adapter.NATIVE_MARKER))

    def test_handler_patch_identity_changes_once_and_is_idempotent(self):
        """patch 前后方法对象身份必须变化，且重复 patch 不再变化。"""
        fresh_handler, _ = build_fake_hierarchy()
        original = fresh_handler.AutoSearchHandler.handle_auto_search_map_option
        adapter.patch_handler(fresh_handler)
        patched = fresh_handler.AutoSearchHandler.handle_auto_search_map_option
        self.assertIsNot(original, patched)
        adapter.patch_handler(fresh_handler)
        self.assertIs(patched, fresh_handler.AutoSearchHandler.handle_auto_search_map_option)

    def test_upstream_native_state_is_left_untouched(self):
        """上游本类显式定义确认状态时整体让位：handler 不覆盖，combat 不包裹。"""
        handler, combat = build_fake_hierarchy()
        setattr(handler.AutoSearchHandler, '_auto_search_off_frame', 'native')
        adapter.patch_handler(handler)
        self.assertEqual(handler.AutoSearchHandler._auto_search_off_frame, 'native')
        self.assertTrue(
            handler.AutoSearchHandler.__dict__.get(adapter.HANDLER_MARKER))
        # 真实时序里 combat import handler，加载时 handler 必已在 sys.modules。
        sys.modules[adapter.TARGET_HANDLER] = handler
        try:
            adapter.patch_combat(combat)
        finally:
            sys.modules.pop(adapter.TARGET_HANDLER, None)
        combat_cls = combat.AutoSearchCombat
        for name in adapter.ENTRY_RESETS:
            self.assertFalse(
                getattr(combat_cls.__dict__.get(name),
                        '_android_auto_search_reset', False), name)
        self.assertTrue(combat_cls.__dict__.get(adapter.COMBAT_MARKER))

    def test_marker_inheritance_does_not_skip_combat_patch(self):
        """handler marker 不得被 combat 继承；继承链下入口仍须全部 wrap。"""
        combat_cls = self.combat.AutoSearchCombat
        self.assertIsNone(vars(combat_cls).get(adapter.HANDLER_MARKER))
        self.assertTrue(vars(combat_cls).get(adapter.COMBAT_MARKER))
        for name in adapter.ENTRY_RESETS:
            self.assertTrue(
                getattr(combat_cls.__dict__[name],
                        '_android_auto_search_reset', False), name)


class InstallTests(unittest.TestCase):
    def setUp(self):
        self._meta_path = list(sys.meta_path)
        self._modules = {name: sys.modules.get(name)
                         for name in (adapter.TARGET_HANDLER, adapter.TARGET_COMBAT)}
        for name in (adapter.TARGET_HANDLER, adapter.TARGET_COMBAT):
            sys.modules.pop(name, None)

    def tearDown(self):
        sys.meta_path[:] = self._meta_path
        for name, module in self._modules.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module

    def test_install_registers_finder_once_and_patches_loaded_modules(self):
        handler, _ = build_fake_hierarchy()
        sys.modules[adapter.TARGET_HANDLER] = handler
        adapter.install()
        adapter.install()
        self.assertTrue(
            handler.AutoSearchHandler.__dict__.get(adapter.HANDLER_MARKER))
        finders = [f for f in sys.meta_path if type(f) is adapter._Finder]
        self.assertEqual(len(finders), 1)

    def test_finder_delegates_and_wraps_loader(self):
        import importlib.machinery
        import importlib.util

        class DummyLoader:
            """模拟真实模块体：exec_module 时才创建目标类，供顺序断言。"""

            def __init__(self):
                self.executed = False

            def create_module(self, spec):
                return None

            def exec_module(self, module):
                self.executed = True
                module.AutoSearchHandler = build_fake_hierarchy()[0].AutoSearchHandler

        class BareLoader:
            """只实现 exec_module 的加载器：create_module 应降级为默认。"""

            def exec_module(self, module):
                pass

        class LaterFinder:
            def __init__(self, loader):
                self.loader = loader

            def find_spec(self, fullname, path, target=None):
                return importlib.machinery.ModuleSpec(fullname, self.loader)

        finder = adapter._Finder()
        loader = DummyLoader()
        sys.meta_path.insert(0, LaterFinder(loader))
        sys.meta_path.insert(0, finder)
        spec = finder.find_spec(adapter.TARGET_HANDLER, None)
        self.assertIsInstance(spec.loader, adapter._Loader)
        self.assertIsInstance(spec.loader.original, DummyLoader)
        self.assertIsNone(finder.find_spec('module.other', None))
        # 走真实 module_from_spec → exec_module 链：模块体先执行、补丁随后生效。
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertTrue(loader.executed, '原模块体未被执行')
        self.assertTrue(module.AutoSearchHandler.__dict__.get(adapter.HANDLER_MARKER))
        # BareLoader 路径：create_module 降级默认创建后，exec_module 正常走完。
        bare_finder = adapter._Finder()
        bare_loader = BareLoader()
        sys.meta_path.insert(0, bare_finder)
        sys.meta_path.insert(1, LaterFinder(bare_loader))
        bare_spec = bare_finder.find_spec(adapter.TARGET_COMBAT, None)
        bare_module = importlib.util.module_from_spec(bare_spec)
        bare_spec.loader.exec_module(bare_module)
        self.assertIsNone(bare_spec.loader.create_module(bare_spec))


class DistributionWiringTests(unittest.TestCase):
    """APK 分发链两处显式列表必须包含新 overlay，否则升级用户永远收不到。

    The APK asset pack list and the startup copy list must both include the
    new overlay, or existing rootfs users never receive it.
    """

    def test_gradle_assets_list_contains_overlay(self):
        text = (ROOT / 'app/app/build.gradle.kts').read_text(encoding='utf-8')
        self.assertIn('"android_auto_search.py"', text)

    def test_proot_host_copy_list_contains_overlay(self):
        text = (ROOT / 'app/app/src/main/kotlin/com/azurpilot/ghio/proot/ProotHost.kt') \
            .read_text(encoding='utf-8')
        self.assertIn('"android_auto_search.py"', text)


if __name__ == '__main__':
    unittest.main()
