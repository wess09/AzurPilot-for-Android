"""阻止大世界事件过渡期间继续定位或重复行军，不改变事件扫描策略。

Guards Operation Siren event transitions without changing event scanning policy.
"""

import ast
import importlib.abc
import inspect
import sys
import textwrap


TARGETS = ('module.os.fleet', 'module.os_shop.shop')

# 事件过渡独立兜底超时（秒）：正常过渡远小于该值；异常时避免剧情帧无限重置
# 确认计时器导致移动永不结束，超时走既有 MapWalkError 路径。
GUARD_DEADLINE = 180


def guard_walk(original, story_button):
    """在原移动循环中保留剧情确认帧，复用原战斗、奖励和超时处理。

    Preserves story-confirmation frames in the original walk loop, retaining its handlers.
    """
    tree = ast.parse(textwrap.dedent(inspect.getsource(original)))
    function = tree.body[0]
    loops = [node for node in function.body if isinstance(node, ast.For)]
    if len(loops) != 1 or original.__code__.co_freevars:
        raise ValueError('unsupported OS walk loop')
    loop = loops[0]
    anchors = [i for i, node in enumerate(loop.body)
               if isinstance(node, ast.If) and isinstance(node.test, ast.Call)
               and isinstance(node.test.func, ast.Attribute)
               and node.test.func.attr == 'handle_retirement']
    if len(anchors) != 1 or 'allow_event_finish' not in inspect.signature(original).parameters:
        raise ValueError('unsupported OS event handler ordering')
    # 剧情处理器在确认选项或点击冷却时会返回 False，不能把透出的地图当作可定位页面。
    # 剧情帧每帧重置 confirm_timer 后 continue，稳定性检查可能永远不到达，且上层
    # loop 无总超时；因此加一个不被 guard 重置的独立 deadline，超时走既有
    # MapWalkError 路径（与 walk_out_of_step 同一套上层处理）。Timer 与
    # MapWalkError 取自被包装方法的全局命名空间，缺任一即按不兼容保留原实现。
    deadline = original.__globals__['Timer'](GUARD_DEADLINE).start()
    guard = ast.parse('''
if allow_event_finish and self.appear(_android_opsi_story_button, offset=(20, 20)):
    completed_event = False
    confirm_timer.reset()
    if _android_opsi_deadline.reached():
        raise _android_opsi_deadline_error('Android 大世界事件过渡超时')
    continue
''').body[0]
    loop.body.insert(anchors[0], guard)
    namespace = dict(original.__globals__, _android_opsi_story_button=story_button,
                     _android_opsi_deadline=deadline,
                     _android_opsi_deadline_error=original.__globals__['MapWalkError'])
    exec(compile(ast.fix_missing_locations(tree), inspect.getsourcefile(original), 'exec'), namespace)
    guarded = namespace[original.__name__]
    guarded.__module__ = original.__module__
    guarded.__qualname__ = original.__qualname__
    guarded.__defaults__ = original.__defaults__
    guarded.__kwdefaults__ = original.__kwdefaults__
    return guarded


def shop_entry(original):
    """明石入口只发一次行军，持续识别商店；失败交回已有异常处理。

    Sends one Akashi approach command, recognizing the shop or raising the existing exception.
    """
    def handle_akashi_supply_buy(self, grid):
        from module.base.timer import Timer
        from module.exception import GameStuckError
        from module.exception import MapDetectionError
        from module.handler.assets import STORY_SKIP_3
        from module.os_shop.assets import PORT_SUPPLY_CHECK

        no_progress = Timer(12).start()
        clicked = False
        position = None
        for _ in self.loop():
            if self.appear(PORT_SUPPLY_CHECK, offset=(20, 20)):
                return original(self, grid)
            if self.handle_story_skip():
                no_progress.reset()
                continue
            story = self.appear(STORY_SKIP_3, offset=(20, 20))
            in_map = self.is_in_map()
            if no_progress.reached():
                raise GameStuckError('Android 明石入口连续十二秒无进展')
            if in_map and not story:
                try:
                    self.update_os()
                except MapDetectionError:
                    continue
                # 定位可能取得新截图，再检查一次页面，不能用旧地图判断穿过刚出现的选项。
                if self.appear(PORT_SUPPLY_CHECK, offset=(20, 20)):
                    return original(self, grid)
                if self.appear(STORY_SKIP_3, offset=(20, 20)) or not self.is_in_map():
                    continue
                current = self.view.backend.homo_loca
                if current is not None:
                    current = tuple(current)
                    if position is None:
                        position = current
                    elif sum((a - b) ** 2
                             for a, b in zip(current, position)) > 5.5 ** 2:
                        # 以稳定基准累计位移：持续慢速移动也能刷新进度，
                        # 只有长时间无位移才判定卡住。
                        no_progress.reset()
                        position = current
            # 商店已出现后才调用原购买流程；移动中、选项确认中和未知页面均不补点。
            if (not clicked and in_map and not story
                    and not self.fleet_selector.bar_opened()):
                self.device.click(grid)
                clicked = True
                no_progress.reset()
    return handle_akashi_supply_buy


def patch_module(module):
    """仅适配现有接口，版本不兼容时明确记录并保留原实现。

    Adapts existing interfaces, logging incompatibilities while retaining upstream behavior.
    """
    if getattr(module, '_android_opsi_event_adapter', False):
        return
    if module.__name__ == TARGETS[0]:
        try:
            from module.handler.assets import STORY_SKIP_3

            method = guard_walk(module.OSFleet.wait_until_walk_stable, STORY_SKIP_3)
        except Exception as error:
            # 属性缺失（AttributeError）、资源挪位（ImportError）及接口变化一律按
            # 不兼容处理：记录后保留上游实现，绝不让工作进程导入失败。
            module.logger.warning(f'[Android-大世界事件] 当前移动接口不兼容，保留原实现: {error}')
            return
        module.OSFleet.wait_until_walk_stable = method
    else:
        try:
            original = module.OSShop.handle_akashi_supply_buy
        except AttributeError as error:
            module.logger.warning(f'[Android-大世界事件] 当前商店接口不兼容，保留原实现: {error}')
            return
        try:
            module.OSShop.handle_akashi_supply_buy = shop_entry(original)
        except Exception as error:
            module.logger.warning(f'[Android-大世界事件] 商店入口适配失败，保留原实现: {error}')
            return
    module._android_opsi_event_adapter = True


class _Loader(importlib.abc.Loader):
    """上游模块加载完毕后接入，不修改源码文件。

    Installs after upstream loading without modifying source files.
    """

    def __init__(self, original):
        self.original = original

    def create_module(self, spec):
        return self.original.create_module(spec)

    def exec_module(self, module):
        self.original.exec_module(module)
        patch_module(module)


class _Finder(importlib.abc.MetaPathFinder):
    """委托后续 finder，以兼容现有 Android overlay。

    Delegates to subsequent finders to compose with existing Android overlays.
    """

    _android_opsi_event_adapter = True

    def find_spec(self, fullname, path, target=None):
        if fullname not in TARGETS:
            return None
        for finder in sys.meta_path[sys.meta_path.index(self) + 1:]:
            spec = finder.find_spec(fullname, path, target)
            if spec is not None:
                if spec.loader:
                    spec.loader = _Loader(spec.loader)
                return spec
        return None


def install():
    """延迟接入且幂等；新 worker 与热更重启后重新绑定当前核心。

    Installs lazily and idempotently, rebinding the current core in new or updated workers.
    """
    for name in TARGETS:
        if name in sys.modules:
            patch_module(sys.modules[name])
    if not any(getattr(finder, '_android_opsi_event_adapter', False) for finder in sys.meta_path):
        sys.meta_path.insert(0, _Finder())
