"""修复 AzurPilot 配置服务中的时间戳微秒、数值区间及类型不一致问题。

上游 `module.api.config_service` 存在导出与校验规则冲突：
1. `_sync_record_time()` 写入带 6 位微秒的时间戳（timespec='microseconds'），
   但校验器仅接受整秒格式（re.fullmatch(r'\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}', value)）；
2. 舰船心情可降为负数（-1），导出原样写出，超出 schema [0, 150] 区间要求；
3. 文本参数在未配置时导出为 null 或数字，被类型校验拒绝。

本模块在上游模块导入后安装补丁：
- 将 `_sync_record_time()` 写出时间截断为整秒；
- 放宽日期校验并自动截断微秒；
- 在参数校验、变更批次应用（patch）与实例配置导入（import_config）前依据 `args.json`
  做 schema 自动规整（区间收敛、空值与类型转换）。

Fixes timestamp microseconds, value range, and type inconsistency issues in AzurPilot's
config service.

Upstream `module.api.config_service` exhibits conflicting export and validation rules:
1. `_sync_record_time()` outputs 6-digit microsecond timestamps (timespec='microseconds'),
   while the validator strictly requires whole seconds;
2. Ship emotions can drop to negative values (-1), exported verbatim and violating the
   schema [0, 150] range constraint;
3. String parameters export as null or numbers when unconfigured, failing type checks.

This module patches the upstream service upon import to:
- Truncate `_sync_record_time()` output to whole seconds;
- Tolerate and truncate microseconds in date validation;
- Automatically sanitize values (range clamping, null/type coercion) against `args.json`
  during validation, patching, and config imports.
"""

import functools
import importlib.abc
import importlib.machinery
import json
from pathlib import Path
import re
import sys
from typing import Any, Optional

TARGET_MODULE = 'module.api.config_service'
DATETIME_PATTERN = re.compile(r'^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(?:\.\d+)?.*$')

_SCHEMA_CACHE: Optional[dict[str, Any]] = None


def _load_schema(module) -> Optional[dict[str, Any]]:
    """加载上游 args.json 字典；失败时返回 None。 / Loads upstream args.json, returning None on failure."""
    global _SCHEMA_CACHE
    if _SCHEMA_CACHE is not None:
        return _SCHEMA_CACHE

    candidates = []
    if hasattr(module, '__file__') and module.__file__:
        candidates.append(Path(module.__file__).resolve().parents[1] / 'config/argument/args.json')
    candidates.extend([
        Path('/opt/azurpilot/module/config/argument/args.json'),
        Path('module/config/argument/args.json'),
    ])

    for path in candidates:
        if path.is_file():
            try:
                _SCHEMA_CACHE = json.loads(path.read_text(encoding='utf-8'))
                return _SCHEMA_CACHE
            except Exception:
                pass
    return None


def _sanitize_value(path: str, value: Any, schema: Optional[dict[str, Any]]) -> Any:
    """根据字段路径和 schema 约束规整值。 / Sanitizes value according to path and schema constraints."""
    if not isinstance(path, str):
        return value

    parts = path.split('.')
    field_def = None
    if schema and len(parts) == 3:
        task, group, arg = parts
        field_def = schema.get(task, {}).get(group, {}).get(arg)

    # 1. 日期格式处理：截断微秒并统一空格分隔
    is_datetime = False
    if field_def:
        is_datetime = field_def.get('type') == 'datetime' or field_def.get('validate') == 'datetime'
    else:
        is_datetime = path.endswith('.Record') or 'Record' in path

    if (is_datetime or (isinstance(value, str) and DATETIME_PATTERN.match(value.strip()))) and isinstance(value, str):
        match = DATETIME_PATTERN.match(value.strip())
        if match:
            return f"{match.group(1)} {match.group(2)}"

    # 2. 数值区间约束（如心情 0 ~ 150）
    if field_def and isinstance(field_def.get('validate'), list) and len(field_def['validate']) == 2:
        val_range = field_def['validate']
        if isinstance(val_range[0], (int, float)) and isinstance(val_range[1], (int, float)):
            min_val, max_val = val_range[0], val_range[1]
            num = None
            if isinstance(value, (int, float)):
                num = value
            elif isinstance(value, str) and value.lstrip('-').replace('.', '', 1).isdigit():
                try:
                    num = float(value)
                except ValueError:
                    num = None
            if num is not None:
                clamped = max(min_val, min(max_val, num))
                if isinstance(field_def.get('value'), int) or isinstance(value, int):
                    return int(clamped)
                return clamped
    elif 'Emotion.Fleet' in path and path.endswith('Value'):
        # 兼容无 schema 时的心情兜底
        if isinstance(value, (int, float)):
            return max(0, min(150, int(value)))
        elif isinstance(value, str) and value.lstrip('-').isdigit():
            return max(0, min(150, int(value)))

    # 3. 字符串类型规范化
    expects_string = False
    if field_def:
        expects_string = isinstance(field_def.get('value'), str) or (
            field_def.get('type') in {'input', 'textarea', 'select'} and
            not isinstance(field_def.get('value'), (int, float, bool))
        )
    else:
        expects_string = path in {
            'Dorm.Dorm.FeedFilter',
            'OpsiMeowfficerFarming.OpsiMeowfficerFarming.TargetZone',
            'EventC.EventDaily.LastStage',
            'EventD.EventDaily.LastStage',
            'Ambush11.GemsFarming.EventFallbackStage',
            'OpsiScheduling.OpsiScheduling.MonthEndMeowTargetZone',
        }

    if expects_string and not isinstance(value, (dict, list)):
        if value is None:
            return ""
        if isinstance(value, (int, float, bool)):
            return str(value)

    # 4. null 写入字符串字段兜底
    if value is None:
        if expects_string or (
            'RemoteStartCommand' in path or
            'RemoteStopCommand' in path or
            'CropTargets' in path or
            'SeasonTaskTargets' in path or
            'CargoTargets' in path or
            'ExtraTargets' in path or
            'RemoteSSH' in path or
            path.endswith('.Progress') or
            path.endswith('.Blacklist')
        ):
            return ""

    return value


def _sanitize_dict_dates(data: dict) -> None:
    """递归遍历字典，将带微秒的时间戳截断为整秒。 / Recursively truncates microsecond timestamps in dict."""
    for key, val in data.items():
        if isinstance(val, dict):
            _sanitize_dict_dates(val)
        elif isinstance(val, str) and ('Record' in str(key) or DATETIME_PATTERN.match(val)):
            match = DATETIME_PATTERN.match(val.strip())
            if match:
                data[key] = f"{match.group(1)} {match.group(2)}"


def _sanitize_config_json(content: str, schema: Optional[dict[str, Any]]) -> str:
    """对完整配置 JSON 文本做全量规整。 / Sanitizes entire config JSON text."""
    try:
        data = json.loads(content)
    except Exception:
        return content

    values = data.get('values', data) if isinstance(data, dict) else None
    if isinstance(values, dict):
        for task, groups in values.items():
            if not isinstance(groups, dict):
                continue
            for group, fields in groups.items():
                if not isinstance(fields, dict):
                    continue
                for arg, val in fields.items():
                    path = f"{task}.{group}.{arg}"
                    fields[arg] = _sanitize_value(path, val, schema)
    return json.dumps(data, ensure_ascii=False, indent=2)


def patch_ap_module(module) -> None:
    """为上游 config_service 注入微秒截断与参数规整补丁。 / Injects patches into upstream config_service."""
    if getattr(module, '_android_config_compat_installed', False):
        return

    schema = _load_schema(module)

    # 1. 修复 re.fullmatch 校验端对日期微秒的拒绝
    orig_fullmatch = getattr(module, 're', re).fullmatch

    def compat_fullmatch(pattern, string, flags=0):
        if pattern == r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}' and isinstance(string, str):
            if DATETIME_PATTERN.match(string):
                return DATETIME_PATTERN.match(string)
        return orig_fullmatch(pattern, string, flags)

    if hasattr(module, 're'):
        module.re.fullmatch = compat_fullmatch

    # 2. 包装 ConfigService 校验与写入方法
    service_cls = getattr(module, 'ConfigService', None)
    targets = []
    if service_cls:
        targets.append(service_cls)
    if hasattr(module, 'config_service'):
        targets.append(module.config_service)

    for target in targets:
        # 拦截 _sync_record_time
        if hasattr(target, '_sync_record_time'):
            orig_sync = getattr(target, '_sync_record_time')

            @functools.wraps(orig_sync)
            def wrapped_sync(self, *args, **kwargs):
                res = orig_sync(self, *args, **kwargs)
                for arg in args:
                    if isinstance(arg, dict):
                        _sanitize_dict_dates(arg)
                    elif hasattr(arg, 'data') and isinstance(arg.data, dict):
                        _sanitize_dict_dates(arg.data)
                return res

            setattr(target, '_sync_record_time', wrapped_sync)

        # 拦截 validate
        for val_name in ('validate', '_validate'):
            if hasattr(target, val_name):
                orig_val = getattr(target, val_name)

                @functools.wraps(orig_val)
                def wrapped_val(self, path, value, *args, **kwargs):
                    sanitized = _sanitize_value(path, value, schema)
                    return orig_val(self, path, sanitized, *args, **kwargs)

                setattr(target, val_name, wrapped_val)

        # 拦截 patch
        if hasattr(target, 'patch'):
            orig_patch = getattr(target, 'patch')

            @functools.wraps(orig_patch)
            def wrapped_patch(self, *args, **kwargs):
                # changes 可能是位置参数或关键字参数
                if len(args) >= 2 and isinstance(args[1], list):
                    for change in args[1]:
                        if isinstance(change, dict) and 'path' in change and 'value' in change:
                            change['value'] = _sanitize_value(change['path'], change['value'], schema)
                elif 'changes' in kwargs and isinstance(kwargs['changes'], list):
                    for change in kwargs['changes']:
                        if isinstance(change, dict) and 'path' in change and 'value' in change:
                            change['value'] = _sanitize_value(change['path'], change['value'], schema)
                return orig_patch(self, *args, **kwargs)

            setattr(target, 'patch', wrapped_patch)

        # 拦截 import_config
        if hasattr(target, 'import_config'):
            orig_import = getattr(target, 'import_config')

            @functools.wraps(orig_import)
            def wrapped_import(self, *args, **kwargs):
                if len(args) >= 2 and isinstance(args[1], str):
                    args = list(args)
                    args[1] = _sanitize_config_json(args[1], schema)
                elif 'content' in kwargs and isinstance(kwargs['content'], str):
                    kwargs['content'] = _sanitize_config_json(kwargs['content'], schema)
                return orig_import(self, *args, **kwargs)

            setattr(target, 'import_config', wrapped_import)

    module._android_config_compat_installed = True


class _Loader(importlib.abc.Loader):
    """在上游模块初始化完成后注入补丁。 / Injects patches after upstream module initialization."""

    def __init__(self, original):
        self.original = original

    def create_module(self, spec):
        return self.original.create_module(spec)

    def exec_module(self, module):
        self.original.exec_module(module)
        patch_ap_module(module)


class _Finder(importlib.abc.MetaPathFinder):
    """拦截目标配置服务模块。 / Intercepts the target config service module."""

    _android_config_adapter = True

    def find_spec(self, fullname, path, target=None):
        if fullname != TARGET_MODULE:
            return None
        spec = importlib.machinery.PathFinder.find_spec(fullname, path)
        if spec and spec.loader:
            spec.loader = _Loader(spec.loader)
        return spec


def install():
    """安装配置兼容层；已导入时立即打补丁。 / Installs config compatibility layer, patching immediately if imported."""
    module = sys.modules.get(TARGET_MODULE)
    if module is not None:
        patch_ap_module(module)
    if not any(getattr(finder, '_android_config_adapter', False) for finder in sys.meta_path):
        sys.meta_path.insert(0, _Finder())
