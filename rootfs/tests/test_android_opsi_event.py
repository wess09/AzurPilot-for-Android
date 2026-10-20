"""用当前固定核心的真实方法验证事件过渡，不连接手机或消耗资源。

Tests transitions using methods from the pinned core without accessing a device or account.
"""

import ast
import importlib.util
import math
import os
from pathlib import Path
import sys
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('android_opsi_event',
                                             ROOT / 'rootfs/overlays/android_opsi_event.py')
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)
STORY, SHOP, IN_MAP, SEARCH_OFF = (object() for _ in range(4))


class Timer:
    """由截图帧推进的虚拟时钟。 / Uses a virtual clock advanced by screenshot frames."""

    now = 0

    def __init__(self, limit, count=0):
        self.limit = limit
        self.at = self.now

    def start(self):
        return self.reset()

    def reset(self):
        self.at = self.now
        return self

    def reached(self):
        return self.now - self.at >= self.limit


class Stuck(Exception):
    """替代游戏异常，保留错误传播契约。 / Substitutes the game's propagated error."""


def core_method(relative, class_name, method):
    """仅编译真实方法，依赖通过桩注入，避免启动 OCR 或设备。

    Compiles a real core method with stub dependencies, avoiding OCR and device startup.
    """
    path = Path(os.environ['AZURPILOT_SOURCE']) / relative
    tree = ast.parse(path.read_text(encoding='utf-8'))
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef)
               and node.name == class_name)
    node = next(node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name == method)
    namespace = dict(Timer=Timer, logger=Mock(), IN_MAP=IN_MAP,
                     PORT_SUPPLY_CHECK=SHOP, CLICK_SAFE_AREA=object(),
                     STRATEGIC_SEARCH_MAP_OPTION_OFF=SEARCH_OFF,
                     AUTO_SEARCH_REWARD=object(), MapWalkError=Stuck,
                     effective_task=lambda config: config.task.command,
                     location2node=lambda location: SimpleNamespace(),
                     FARMING_TASKS={'OpsiHazard1Leveling', 'OpsiMeowfficerFarming'},
                     np=SimpleNamespace(subtract=lambda a, b: [x-y for x, y in zip(a, b)],
                                        linalg=SimpleNamespace(norm=lambda a: math.hypot(*a))))
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(path), 'exec'), namespace)
    return namespace[method]


class Harness:
    """按帧提供页面、事件和移动进展。 / Supplies pages, events, and movement per frame."""

    def __init__(self, events=None, story=(), shop=(), moving=False, unknown=False):
        Timer.now = 0
        self.frame = -1
        self.events = events or {}
        self.story = set(story)
        self.shop = set(shop)
        self.moving = moving
        self.unknown = unknown
        self.config = SimpleNamespace(task=SimpleNamespace(command='OpsiHazard1Leveling'))
        self.device = Mock()
        self.fleet_selector = SimpleNamespace(bar_opened=lambda: False)
        self.view = SimpleNamespace(backend=SimpleNamespace(homo_loca=(52, 58)))
        self.is_siren_device_confirmed = False
        self.siren_device_mode = None
        self.map_updates_during_story = []
        self.saved_map_events = []
        for name in ('handle_retirement', 'handle_walk_out_of_step', 'handle_popup_confirm',
                     'handle_manjuu', 'is_in_globe', 'is_in_storage', 'is_in_os_mission',
                     'handle_os_game_tips', 'is_in_map_order', 'combat_appear',
                     'appear_then_click', 'enemy_searching_appear', 'interval_clear',
                     'enemy_searching_color_initial'):
            setattr(self, name, Mock(return_value=False))
        self.match_template_color = Mock(return_value=True)
        self.handle_akashi_supply_buy = Mock()

    # 上游 dev 新增的待办地图事件持久化钩子（module/os/map.py 调用，实现位于
    # module/os 其他模块）：Harness 记录调用，供测试观察交互。
    def _save_pending_map_event(self, kind, grid=None, **kwargs):
        self.saved_map_events.append(('save', kind, grid))

    def _update_pending_map_event(self, **kwargs):
        self.saved_map_events.append(('update', kwargs))

    def _finish_pending_map_event(self, kind, **kwargs):
        self.saved_map_events.append(('finish', kind, kwargs))

    def _open_akashi_store(self, grid, drop=None, attempts=3):
        # 上游 dev 将"点击明石并完成购买"提取为该独立方法（有界点击 +
        # wait_until_walk_stable，购买经 handle_akashi_supply_buy 完成）。
        # 测试关注 shop_entry 包装行为：购买链路自带单次行军命令点击，
        # 桩只触发购买，与上游"点击/购买分离"的新结构对齐。
        self.handle_akashi_supply_buy(grid)
        return True

    def loop(self, **kwargs):
        for self.frame in range(100):
            Timer.now = self.frame * .35
            self.view.backend.homo_loca = (52 + self.frame * 8 if self.moving else 52, 58)
            yield None
        raise AssertionError('state loop exceeded bounded test frames')

    def appear(self, button, **kwargs):
        return (button is STORY and self.frame in self.story
                or button is SHOP and self.frame in self.shop)

    def is_in_map(self):
        return not self.unknown and self.frame not in self.shop

    def handle_map_event(self, **kwargs):
        event = self.events.get(self.frame, '')
        if event == 'story_skip':
            self.is_siren_device_confirmed = True
            self.siren_device_mode = 'resource'
        return event

    def handle_story_skip(self):
        return self.handle_map_event() == 'story_skip'

    def update_os(self):
        if self.frame in self.story:
            self.map_updates_during_story.append(self.frame)


@unittest.skipUnless(os.environ.get('AZURPILOT_SOURCE'), 'set AZURPILOT_SOURCE to the pinned core')
class CoreTransitionTest(unittest.TestCase):
    """测试原循环与各入口，CI 必须提供固定核心源码。

    Tests original loops and entry points; CI supplies the pinned core source.
    """

    def walk(self):
        return adapter.guard_walk(core_method('module/os/fleet.py', 'OSFleet',
                                               'wait_until_walk_stable'), STORY)

    def test_delayed_options_do_not_localize_or_finish_on_visible_map(self):
        handler = Harness(events={11: 'story_skip', 15: 'map_get_items'}, story=range(4, 15))
        result = self.walk()(handler, confirm_timer=Timer(3), allow_event_finish=True)
        self.assertIn('event', result)
        self.assertGreaterEqual(handler.frame, 15)
        self.assertEqual(handler.map_updates_during_story, [])
        handler.device.click.assert_not_called()

    def test_unmodified_core_reproduces_localization_during_option_confirmation(self):
        handler = Harness(events={11: 'story_skip'}, story=range(4, 15))
        original = core_method('module/os/fleet.py', 'OSFleet', 'wait_until_walk_stable')
        original(handler, confirm_timer=Timer(3), allow_event_finish=True)
        self.assertTrue(handler.map_updates_during_story)
        self.assertLess(handler.frame, 11)

    def test_adjacent_akashi_enters_single_command_shop_adapter(self):
        method = core_method('module/os/map.py', 'OSMap', 'map_rescan_current')
        handler = Harness(shop={20})
        grid = SimpleNamespace(is_akashi=True, location=(2, 5))
        handler.view.select = lambda **flags: [grid] if flags.get('is_akashi') else []
        handler._solved_map_event = set()
        handler.convert_radar_to_local = lambda location: SimpleNamespace(distance_to=lambda g: 1)
        original = Mock()
        modules = {'module.base.timer': SimpleNamespace(Timer=Timer),
                   'module.exception': SimpleNamespace(GameStuckError=Stuck, MapDetectionError=Stuck),
                   'module.handler.assets': SimpleNamespace(STORY_SKIP_3=STORY),
                   'module.os_shop.assets': SimpleNamespace(PORT_SUPPLY_CHECK=SHOP)}
        with patch.dict(sys.modules, modules):
            handler.handle_akashi_supply_buy = lambda g: adapter.shop_entry(original)(handler, g)
            self.assertTrue(method(handler))
        handler.device.click.assert_called_once_with(grid)
        original.assert_called_once_with(handler, grid)

    def test_ordinary_movement_retains_upstream_confirmation(self):
        original = core_method('module/os/fleet.py', 'OSFleet', 'wait_until_walk_stable')
        plain = Harness()
        expected = original(plain, confirm_timer=Timer(3))
        guarded = Harness()
        self.assertEqual(self.walk()(guarded, confirm_timer=Timer(3)), expected)
        self.assertEqual(plain.frame, guarded.frame)

    def test_resource_selection_returns_through_original_result(self):
        handler = Harness(events={3: 'story_skip'}, story=range(1, 4))
        result = self.walk()(handler, confirm_timer=Timer(3), allow_event_finish=True)
        self.assertIn('event', result)
        self.assertTrue(handler.is_siren_device_confirmed)
        self.assertEqual(handler.siren_device_mode, 'resource')
        handler.device.click.assert_not_called()

    def test_actual_map_entries_send_one_approach_before_shared_wait(self):
        method = core_method('module/os/map.py', 'OSMap', 'map_rescan_current')
        for kind in ('is_scanning_device', 'is_exploration_container',
                     'is_exploration_reward', 'is_logging_tower', 'is_akashi'):
            with self.subTest(kind=kind):
                handler = Harness(events={11: 'story_skip', 15: 'map_get_items'},
                                  story=range(4, 15))
                grid = SimpleNamespace(location=(2, 5), distance_to=lambda g: 4, **{kind: True})
                handler.view.select = lambda **flags: [grid] if flags.get(kind) else []
                handler._solved_map_event = set()
                handler._is_siren_research_enabled = True
                handler.convert_radar_to_local = lambda location: SimpleNamespace(distance_to=lambda g: 4)
                handler.config.temporary = lambda **kw: unittest.mock.MagicMock()
                method.__globals__['record_siren_research_device'] = Mock()

                class Reached(Exception):
                    pass

                def wait(**kwargs):
                    self.walk()(handler, **kwargs)
                    raise Reached

                handler.wait_until_walk_stable = wait
                with self.assertRaises(Reached):
                    method(handler)
                handler.device.click.assert_called_once_with(grid)
                self.assertEqual(handler.map_updates_during_story, [])

    def test_radar_question_uses_same_wait_without_reclicking(self):
        method = core_method('module/os/map.py', 'OSMap', 'clear_question')
        handler = Harness(events={11: 'story_skip', 15: 'map_get_items'}, story=range(4, 15))
        grid = SimpleNamespace(is_logging_tower=False, is_scanning_device=True, location=(2, 5))
        handler.zone = SimpleNamespace(is_port=False)
        handler.radar = SimpleNamespace(predict_question=lambda *a, **kw: (0, 2))
        handler.view.predict = Mock()
        handler.view.show = Mock()
        handler.view.select = lambda **kw: SimpleNamespace(count=1)
        handler.handle_info_bar = Mock()
        handler.convert_radar_to_local = lambda location: grid
        handler._should_skip_siren_research = lambda g: False
        handler.config.temporary = lambda **kw: unittest.mock.MagicMock()

        class Reached(Exception):
            pass

        def wait(**kwargs):
            self.walk()(handler, **kwargs)
            raise Reached

        handler.wait_until_walk_stable = wait
        with self.assertRaises(Reached):
            method(handler)
        handler.device.click.assert_called_once_with(grid)
        self.assertEqual(handler.map_updates_during_story, [])

    def test_story_frames_cannot_bypass_guard_deadline(self):
        """剧情帧持续出现时，独立 deadline 必须兜底并走既有 MapWalkError 路径。

        Repeated story frames must not bypass the independent guard deadline."""
        with patch.object(adapter, 'GUARD_DEADLINE', 5):
            handler = Harness(story=range(100))
            with self.assertRaises(Stuck):
                self.walk()(handler, confirm_timer=Timer(3), allow_event_finish=True)
            self.assertGreaterEqual(Timer.now, 5)


class ShopTest(unittest.TestCase):
    """验证单次接近、过渡重检、进展预算及未知页面不盲点。

    Tests one approach, page rechecks, progress budgets, and no clicks on unknown pages.
    """

    def setUp(self):
        modules = {
            'module.base.timer': SimpleNamespace(Timer=Timer),
            'module.exception': SimpleNamespace(GameStuckError=Stuck, MapDetectionError=Stuck),
            'module.handler.assets': SimpleNamespace(STORY_SKIP_3=STORY),
            'module.os_shop.assets': SimpleNamespace(PORT_SUPPLY_CHECK=SHOP),
        }
        self.modules = patch.dict(sys.modules, modules)
        self.modules.start()
        self.addCleanup(self.modules.stop)
        self.original = Mock(return_value='purchased')
        self.entry = adapter.shop_entry(self.original)

    def test_delayed_shop_after_long_movement_sends_one_command(self):
        handler = Harness(shop={60}, moving=True)
        self.assertEqual(self.entry(handler, 'akashi'), 'purchased')
        handler.device.click.assert_called_once_with('akashi')
        self.original.assert_called_once_with(handler, 'akashi')
        self.assertGreater(Timer.now, 12)

    def test_no_progress_times_out_without_repeated_movement(self):
        handler = Harness()
        with self.assertRaises(Stuck):
            self.entry(handler, 'akashi')
        handler.device.click.assert_called_once()
        self.original.assert_not_called()
        self.assertGreaterEqual(Timer.now, 12)
        self.assertLess(Timer.now, 12.5)

    def test_shop_at_deadline_is_handled_before_timeout(self):
        handler = Harness(shop={35})
        self.assertEqual(self.entry(handler, 'akashi'), 'purchased')

    def test_unknown_page_never_clicked(self):
        handler = Harness(unknown=True)
        with self.assertRaises(Stuck):
            self.entry(handler, 'akashi')
        handler.device.click.assert_not_called()

    def test_options_delay_blocks_clicks_and_localization(self):
        handler = Harness(events={12: 'story_skip'}, story=range(12), shop={14})
        self.entry(handler, 'akashi')
        self.assertEqual(handler.map_updates_during_story, [])
        handler.device.click.assert_called_once()

    def test_story_appearing_during_update_prevents_click(self):
        handler = Harness(shop={3})
        def update():
            handler.story.add(handler.frame)
        handler.update_os = update
        self.entry(handler, 'akashi')
        handler.device.click.assert_not_called()

    def test_open_shop_does_not_send_approach(self):
        handler = Harness(shop={0})
        self.entry(handler, 'akashi')
        handler.device.click.assert_not_called()


class WiringTest(unittest.TestCase):
    """检查 APK、rootfs 和热更后的启动路径及幂等性。

    Checks APK/rootfs/startup wiring and idempotent installation after updates.
    """

    def test_overlay_is_packaged_synced_and_installed(self):
        for name in ('app/app/build.gradle.kts',
                     'app/app/src/main/kotlin/com/azurpilot/ghio/proot/ProotHost.kt',
                     'rootfs/build/build-azurpilot.sh'):
            self.assertIn('android_opsi_event.py', (ROOT / name).read_text(encoding='utf-8'))
        self.assertIn('OPSI_EVENT_ADAPTER_OK',
                      (ROOT / 'rootfs/build/build-azurpilot.sh').read_text(encoding='utf-8'))

    def test_sitecustomize_only_installs_when_android_enabled(self):
        source = (ROOT / 'rootfs/overlays/sitecustomize.py').read_text(encoding='utf-8')
        names = ('android_process_compat', 'android_equipment', 'android_device',
                 'android_app_launch', 'android_capture', 'android_update', 'android_opsi_event')
        modules = {name: SimpleNamespace(install=Mock()) for name in names}
        with patch.dict(sys.modules, modules), patch.dict(os.environ, {}, clear=True):
            exec(source, {})
            modules['android_opsi_event'].install.assert_not_called()
            os.environ['AZURPILOT_ANDROID'] = '1'
            exec(source, {})
            modules['android_opsi_event'].install.assert_called_once()

    def test_install_is_lazy_and_idempotent(self):
        with patch.object(sys, 'meta_path', list(sys.meta_path)):
            adapter.install()
            adapter.install()
            self.assertEqual(sum(isinstance(item, adapter._Finder) for item in sys.meta_path), 1)

    def test_loader_calls_original_then_patches_and_does_not_stack(self):
        class Shop:
            handle_akashi_supply_buy = Mock()
        module = ModuleType(adapter.TARGETS[1])
        original = SimpleNamespace(create_module=lambda spec: None,
                                   exec_module=lambda mod: setattr(mod, 'OSShop', Shop))
        adapter._Loader(original).exec_module(module)
        method = Shop.handle_akashi_supply_buy
        adapter.patch_module(module)
        self.assertIs(Shop.handle_akashi_supply_buy, method)
        self.assertTrue(module._android_opsi_event_adapter)


if __name__ == '__main__':
    unittest.main()
