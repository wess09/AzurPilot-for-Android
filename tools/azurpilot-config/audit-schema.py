#!/usr/bin/env python3
"""全量复核配置 JSON 是否符合 AzurPilot schema。

用法:
    python audit-schema.py <配置文件路径> [schema路径]
"""

import json
import re
import sys
from pathlib import Path

DATETIME_PATTERN = re.compile(r'^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$')
DATETIME_WITH_US = re.compile(r'^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}\.\d+')


def audit_config(config_path: Path, schema_path: Path) -> int:
    config = json.loads(config_path.read_text(encoding='utf-8'))
    schema = json.loads(schema_path.read_text(encoding='utf-8'))

    values = config.get('values', config)
    errors = []
    audited = 0

    for task, groups in schema.items():
        if not isinstance(groups, dict) or task.startswith('_'):
            continue
        cfg_task = values.get(task, {})
        for group, fields in groups.items():
            if not isinstance(fields, dict) or group.startswith('_'):
                continue
            cfg_group = cfg_task.get(group, {})
            for arg, field_def in fields.items():
                if not isinstance(field_def, dict) or arg.startswith('_'):
                    continue
                if field_def.get('display') in ('readonly', 'disabled', 'hide'):
                    continue
                if field_def.get('type') in ('storage', 'stored', 'state', 'lock'):
                    continue

                audited += 1
                path = f"{task}.{group}.{arg}"
                val = cfg_group.get(arg)

                # 1. 日期检查
                if field_def.get('type') == 'datetime' or field_def.get('validate') == 'datetime':
                    if isinstance(val, str):
                        if DATETIME_WITH_US.match(val):
                            errors.append(f"[日期带微秒] {path} = {val}")
                        elif not DATETIME_PATTERN.match(val) and val != "":
                            errors.append(f"[日期格式错误] {path} = {val}")

                # 2. 数值区间检查
                val_range = field_def.get('validate')
                if isinstance(val_range, list) and len(val_range) == 2:
                    min_val, max_val = val_range
                    if isinstance(val, (int, float)):
                        if not (min_val <= val <= max_val):
                            errors.append(f"[数值越界] {path} = {val} 不在 [{min_val}, {max_val}] 内")

                # 3. 字符串类型检查
                if isinstance(field_def.get('value'), str) or field_def.get('type') in ('input', 'textarea'):
                    if val is None:
                        errors.append(f"[字符串为null] {path} 应为字符串但为 null")
                    elif isinstance(val, (int, float)) and not isinstance(val, bool):
                        errors.append(f"[字符串为数字] {path} 应为字符串但为 {type(val).__name__} ({val})")

    print(f"=== 审计完成：共核对 {audited} 个受管字段 ===")
    if errors:
        print(f"发现 {len(errors)} 处不合规：")
        for err in errors:
            print(f"  - {err}")
        return len(errors)
    else:
        print("全量复核通过：0 处不合规！")
        return 0


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法: python audit-schema.py <配置文件路径> [schema路径]")
        sys.exit(1)
    cfg_p = Path(sys.argv[1])
    sch_p = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('module/config/argument/args.json')
    if not sch_p.is_file():
        sch_p = Path('rootfs/seeds/deploy-azurpilot.yaml')  # 备用
    sys.exit(audit_config(cfg_p, sch_p))
