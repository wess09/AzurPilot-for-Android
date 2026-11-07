#!/usr/bin/env python3
"""按 AzurPilot schema 默认值类型兜底：null -> ""、数字 -> 字符串、类型对齐。

用法:
    python normalize-types.py <源配置文件> [目标配置文件] [schema路径]
"""

import json
import sys
from pathlib import Path


def normalize_types(src_path: Path, dst_path: Path, schema_path: Path):
    data = json.loads(src_path.read_text(encoding='utf-8'))
    schema = json.loads(schema_path.read_text(encoding='utf-8')) if schema_path.is_file() else {}

    values = data.get('values', data) if isinstance(data, dict) else {}
    fixed_count = 0

    KNOWN_STRING_FIELDS = {
        'Dorm.Dorm.FeedFilter',
        'OpsiMeowfficerFarming.OpsiMeowfficerFarming.TargetZone',
        'EventC.EventDaily.LastStage',
        'EventD.EventDaily.LastStage',
        'Ambush11.GemsFarming.EventFallbackStage',
        'OpsiScheduling.OpsiScheduling.MonthEndMeowTargetZone',
    }

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

                expects_string = isinstance(sch_field.get('value'), str) or path in KNOWN_STRING_FIELDS

                # 1. 字符串字段收到 null
                if val is None:
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
                        fields[arg] = ""
                        fixed_count += 1
                        print(f"[null -> 空串] {path}")

                # 2. 字符串字段收到数字或布尔
                elif expects_string and isinstance(val, (int, float, bool)):
                    fields[arg] = str(val)
                    fixed_count += 1
                    print(f"[数字/布尔 -> 字符串] {path}: {val} -> \"{val}\"")

    dst_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"类型规整完成：共规整 {fixed_count} 处，输出至 {dst_path}")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("用法: python normalize-types.py <源配置文件> [目标配置文件] [schema路径]")
        sys.exit(1)
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else src.with_name(src.stem + '-ready.json')
    sch = Path(sys.argv[3]) if len(sys.argv) > 3 else Path('module/config/argument/args.json')
    normalize_types(src, dst, sch)
