"""自律寻敌开关两帧确认：连续两张不同截图都识别到 OFF 才点击，降低结算退场后过早点击的风险。

Requires the auto-search OFF toggle to be confirmed on two consecutive distinct
screenshots before clicking, reducing premature clicks right after battle-result
screens that toggle the icon without issuing a move order.
"""

import functools
import importlib.abc
import sys

TARGET_HANDLER = 'module.handler.auto_search'
TARGET_COMBAT = 'module.combat.auto_search_combat'
HANDLER_CLASS = 'AutoSearchHandler'
COMBAT_CLASS = 'AutoSearchCombat'
# 与上游战斗/续接流程入口对齐：每个入口都是新阶段，进入前清空上一阶段的 OFF 确认。
ENTRY_RESETS = (
    'prepare_auto_search_recovery',
    'map_offensive_auto_search',
    'auto_search_moving',
    'auto_search_combat_execute',
    'auto_search_combat_status',
)
HANDLER_MARKER = '_android_auto_search_handler_adapter'
COMBAT_MARKER = '_android_auto_search_combat_adapter'
# patch_handler 在修改类之前记录「上游原生 / overlay 注入」判定，供 combat 侧区分状态来源。
NATIVE_MARKER = '_android_auto_search_native'


def two_frame_handle(handler_module):
    """构造替换原方法的立即点击守卫，模板与颜色判据沿用上游模块内的同名资产。

    Builds the replacement handler that reuses the upstream module's template,
    color matching, and click cooldown, only adding the second-frame gate.

    Args:
        handler_module: 上游 handler 模块，用于取模板资产常量。
            The upstream handler module, providing the template asset constant.
    """

    def handle_auto_search_map_option(self):
        """连续两张不同截图均确认为 OFF 后，按原有冷却点击开启自律。

        Records the first screenshot that matches OFF and clicks only after a
        later, distinct screenshot still matches; any non-OFF frame resets the
        pending confirmation. Flow entries clear the state so confirmations
        never leak across stages.

        Returns:
            bool: 是否执行了点击开启操作。
        """
        button = handler_module.AUTO_SEARCH_MAP_OPTION_OFF
        if not self.match_template_color(button,
                                         offset=self._auto_search_offset, threshold=30):
            self._auto_search_off_frame = None
            return False
        if self._auto_search_off_frame is None:
            self._auto_search_off_frame = self.device.image
            return False
        if self._auto_search_off_frame is self.device.image:
            return False
        self._auto_search_off_frame = None
        return self.appear_then_click(button, interval=2)

    return handle_auto_search_map_option


def upstream_is_native():
    """读取 patch_handler 修改类之前记录的原生判定，避免把 overlay 注入误当原生。

    Reads the pre-patch decision recorded by patch_handler, so overlay-injected
    state is never mistaken for a native upstream implementation.
    """
    module = sys.modules.get(TARGET_HANDLER)
    cls = getattr(module, HANDLER_CLASS, None) if module else None
    return cls is not None and bool(vars(cls).get(NATIVE_MARKER, False))


def patch_handler(module):
    """替换基类的开关处理方法；上游原生实现两帧语义时整体让位。

    Replaces the base handler with the two-frame version, yielding entirely when
    upstream already ships its own pending-frame state.

    Args:
        module: 上游 `module.handler.auto_search` 模块对象。
            The upstream `module.handler.auto_search` module object.
    """
    cls = vars(module).get(HANDLER_CLASS)
    if cls is None or vars(cls).get(HANDLER_MARKER, False):
        return
    # 判定必须发生在任何修改之前：注入后类上也会出现 _auto_search_off_frame。
    native = '_auto_search_off_frame' in vars(cls)
    if native:
        # 上游已自带确认状态（例如未来原生化本补丁），overlay 不覆盖。
        setattr(cls, NATIVE_MARKER, True)
        setattr(cls, HANDLER_MARKER, True)
        return
    cls._auto_search_off_frame = None
    cls.handle_auto_search_map_option = two_frame_handle(module)
    setattr(cls, HANDLER_MARKER, True)


def patch_combat(module):
    """为战斗与续接流程入口加前置清零；仅在处理器已注入两帧语义时生效。

    Wraps flow entries with a leading state reset and adds the restart-ON
    reset, effective only when the handler patch is in place.

    Args:
        module: 上游 `module.combat.auto_search_combat` 模块对象。
            The upstream `module.combat.auto_search_combat` module object.
    """
    cls = vars(module).get(COMBAT_CLASS)
    if cls is None or vars(cls).get(COMBAT_MARKER, False):
        return
    if upstream_is_native():
        # 上游原生实现时其入口清零与 ON 处理同样原生存在，overlay 不重复包裹。
        # 时序保证：combat 顶层 import handler，真实加载序中 handler 必先经过
        # patch_handler 判定，NATIVE_MARKER 已就位，此处不会漏判。
        setattr(cls, COMBAT_MARKER, True)
        return
    for name in ENTRY_RESETS:
        original = vars(cls).get(name)
        if original is None or getattr(original, '_android_auto_search_reset', False):
            continue

        @functools.wraps(original)
        def wrapped(self, *args, _original=original, **kwargs):
            self._auto_search_off_frame = None
            return _original(self, *args, **kwargs)

        wrapped._android_auto_search_reset = True
        setattr(cls, name, wrapped)
    # 续接 override 在识别到 ON 时提前返回，不经过基类的非 OFF 清零分支，
    # 必须在此清空残留确认，否则 OFF₁→ON→OFF₂ 会立即点击（PC PR 的 ON 分支等价）。
    override = vars(cls).get('handle_auto_search_map_option')
    if override is not None and not getattr(override, '_android_auto_search_reset', False):
        @functools.wraps(override)
        def resume_wrapped(self, *args, _original=override, **kwargs):
            if self.__dict__.get('_restart_resume_guard', False) \
                    and self.is_auto_search_running():
                self._auto_search_off_frame = None
            return _original(self, *args, **kwargs)

        resume_wrapped._android_auto_search_reset = True
        setattr(cls, 'handle_auto_search_map_option', resume_wrapped)
    setattr(cls, COMBAT_MARKER, True)


def patch_module(module):
    """按模块类型分发补丁，重复加载时幂等。

    Dispatches by module name and stays idempotent across reloads.

    Args:
        module: 已执行完的目标模块对象。
            The executed target module object.
    """
    if module.__name__ == TARGET_HANDLER:
        patch_handler(module)
    elif module.__name__ == TARGET_COMBAT:
        patch_combat(module)


class _Loader(importlib.abc.Loader):
    """模块加载后接入，不写上游源码。 / Connects after loading without changing upstream files."""

    def __init__(self, original):
        self.original = original

    def create_module(self, spec):
        """委托原加载器创建模块；其未实现时交回默认机制。

        Delegates module creation, falling back to the default when absent.
        """
        if hasattr(self.original, 'create_module'):
            return self.original.create_module(spec)
        return None

    def exec_module(self, module):
        """执行模块体后立即应用补丁。 / Executes the module body, then patches it immediately."""
        self.original.exec_module(module)
        patch_module(module)


class _Finder(importlib.abc.MetaPathFinder):
    """委托后续加载器，兼容其它 overlay。 / Delegates to later finders to compose with overlays."""

    def find_spec(self, fullname, path, target=None):
        """仅拦截目标模块，包装后续加载器；其余交给默认查找。

        Intercepts only the target modules and wraps their later loaders,
        leaving everything else to the default resolution.
        """
        if fullname not in (TARGET_HANDLER, TARGET_COMBAT):
            return None
        for finder in sys.meta_path[sys.meta_path.index(self) + 1:]:
            spec = finder.find_spec(fullname, path, target)
            if spec is not None:
                if spec.loader:
                    spec.loader = _Loader(spec.loader)
                return spec
        return None


def install():
    """注册一次导入钩子；已加载的目标模块立即补丁。

    Registers one import hook and patches already-imported target modules.
    """
    for name in (TARGET_HANDLER, TARGET_COMBAT):
        module = sys.modules.get(name)
        if module is not None:
            patch_module(module)
    if not any(type(finder) is _Finder for finder in sys.meta_path):
        sys.meta_path.insert(0, _Finder())
