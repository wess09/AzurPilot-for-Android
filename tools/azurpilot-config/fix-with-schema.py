#!/usr/bin/env python3
"""以 AzurPilot schema 为准，截断 datetime 微秒并将越界数值收敛到 validate 区间。

用法:
    python fix-with-schema.py <源配置文件> [目标配置文件] [schema路径]
"""

import json
import re
import sys
from pathlib import Path

DATETIME_PATTERN = re.compile(r'^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})(?:\.\d+)?.*$')


def fix_config(src_path: Path, dst_path: Path, schema_path: Path):
    data = json.loads(src_path.read_text(encoding='utf-8'))
    schema = json.loads(schema_path.read_text(encoding='utf-8')) if schema_path.is_file() else {}

    values = data.get('values', data) if isinstance(data, dict) else {}
    fixed_count = 0

    for task, groups in values.items():
        if not isinstance(groups, dict):
            continue
        sch_task = schema.get(task, {})
        for group, fields in groups.items():
            if not isinstance(fields, dict):
                continue
            sch_group = sch_task.get(group, {})
            for arg, val in fields.items():
                sch_field = sch_group.get(arg, {})
                path = f"{task}.{group}.{arg}"

                # 1. 日期微秒截断
                if isinstance(val, str) and (
                    sch_field.get('type') == 'datetime' or
                    sch_field.get('validate') == 'datetime' or
                    'Record' in arg
                ):
                    match = DATETIME_PATTERN.match(val.strip())
                    if match:
                        norm = f"{match.group(1)} {match.group(2)}"
                        if norm != val:
                            fields[arg] = norm
                            fixed_count += 1
                            print(f"[日期截断] {path}: {val} -> {norm}")

                # 2. 数值区间收敛
                val_range = sch_field.get('validate')
                if isinstance(val_range, list) and len(val_range) == 2:
                    min_val, max_val = val_range
                    if isinstance(val, (int, float)) and not (min_val <= val <= max_val):
                        clamped = max(min_val, min(max_val, val))
                        fields[arg] = int(clamped) if isinstance(val, int) else clamped
                        fixed_count += 1
                        print(f"[数值收敛] {path}: {val} -> {fields[arg]}")
                elif 'Emotion.Fleet' in path and path.endswith('Value') and isinstance(val, (int, float)):
                    if val < 0:
                        fields[arg] = 0
                        fixed_count += 1
                        print(f"[心情收敛] {path}: {val} -> 0")

    dst_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"规整完成：共修复 {fixed_count} 处，输出至 {dst_path}")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法: python fix-with-schema.py <源配置文件> [目标配置文件] [schema路径]")
        sys.exit(1)
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else src.with_name(src.stem + '-fixed.json')
    sch = Path(sys.argv[3]) if len(sys.argv) > 3 else Path('module/config/argument/args.json')
    fix_config(src, dst, sch)
