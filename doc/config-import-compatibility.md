# 配置导入兼容性与规整 / Config Import Compatibility and Normalization

> 本文记录桌面端 AzurPilot 配置导入 Android 端时的格式差异、根因分析、App 壳层规整防御机制以及向上游建议的修复方案。
>
> This document details the formatting discrepancies when importing desktop AzurPilot
> configurations into the Android runtime, root cause analysis, app-shell sanitization
> safeguards, and the suggested upstream fixes.

## 中文

### 1. 现象与排查背景

从桌面端导出的配置 JSON 文件（例如 `azurpilot (2).json`）直接在 Android 端导入时，可能被连续拦截并报错：

```text
日期格式应为 YYYY-MM-DD HH:mm:ss: Dashboard.Oil.Record
```

以及在规整时间戳后继续报错：

```text
参数必须在 0 到 150 之间: CoalitionScuttle.Emotion.Fleet1Value
```

由于网关服务端的参数校验器为逐字段拦截（一次仅报一条错误），导致用户必须反复尝试。经全量 schema 审核（对比 2888 个受管字段），发现共 46 处字段存在格式不一致。

### 2. 根因分析

#### 2.1 日期时间：写入端带微秒，校验端只认整秒（共 23 处）

- **写入端**（`module/api/config_service.py` 的 `_sync_record_time()`）：使用 `now.isoformat(sep=' ', timespec='microseconds')` 写出的时间戳携带 6 位微秒（例如 `2026-11-07 02:13:11.038831`）。
- **校验端**（`module/api/config_service.py` 的校验正则）：使用 `re.fullmatch(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}', value)`，只接受整秒格式。
- **涉及字段**：
  - `Dashboard.*.Record`（12 处仪表盘资源时间戳）
  - 各任务段 `*.Emotion.Fleet1Record` / `Fleet2Record`（11 处心情刷新时间戳）

#### 2.2 数值越界：游戏心情掉至负数，schema 要求 0~150（1 处）

- 现场值：`CoalitionScuttle.Emotion.Fleet1Value = -1`。
- schema 规则：`module/config/argument/args.json` 中 `Emotion.Fleet1Value` / `Fleet2Value` 的 `validate` 约束区间为 `[0, 150]`。
- 游戏内舰船心情可掉入负数（-1），导出端原样序列化，导致导入校验器拒绝。

#### 2.3 类型不符：schema 为字符串，导出写为 null 或数字（共 22 处）

- **null 写入字符串字段（16 处）**：例如 `Alas.EmulatorInfo.RemoteStartCommand`、`Commission.Commission.Blacklist`、`IslandFarm.*.CropTargets` 等。schema 默认值为 `""`，导出端将未设置的键写为了 `null`。
- **数字写入字符串字段（6 处）**：例如 `Dorm.Dorm.FeedFilter = 1000`、`OpsiMeowfficerFarming.OpsiMeowfficerFarming.TargetZone = 71`、`EventC.EventDaily.LastStage = 0` 等。字段定义为文本框，导出端原样输出了数字。

### 3. Android 客户端防御实现（`AzurPilotConfigImport`）

为避免用户手动规整 JSON，Android 端在 `AzurPilotConfigImport.kt` 的批次生成阶段加入自动规整器 `sanitize()`：

1. **日期截断**：识别 `datetime` 类型或符合日期格式的字符串，正则截断微秒并转换 ISO `T` 为标准空格，统一产出 `YYYY-MM-DD HH:mm:ss`。
2. **区间收敛**：对具有 `validate: [min, max]` 区间的数值字段进行 `coerceIn(min, max)`，保留原始整型语义（如 `-1` 收敛为 `0`）。
3. **字符串类型兜底**：若 schema 默认值为字符串，将 `null` 收敛为空串 `""`，将数字/布尔转为字符串字面量（如 `1000` 转为 `"1000"`）。
4. **内部状态保留**：对于不归属于可编辑 schema 的内部状态字段（如 `RecoveryState.record`、`MeowfficerCleanupState.reset` 等），严格保持原样不予变动。
5. **增量比对跳过**：规整后的值若与当前配置值一致，直接跳过生成变更项，减少 `config.patch` 通信批次。

### 4. 给上游的修复建议

1. **写入端与校验端对齐**：`_sync_record_time()` 调整为 `timespec='seconds'`，或将校验正则放宽为 `r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?'`。
2. **心情导出收敛**：导出前对心情值按 schema 区间执行 `clamp(0, 150)`。
3. **序列化类型保护**：导出时参考 `args.json` 的字段类型，避免向字符串字段写入裸 `null` 或数字。
4. **批量校验报错**：建议导入校验收集完整错误列表后一次性返回，避免逐条拦截带来的低效重试体验。

---

## English

### 1. Phenomenon and Context

Directly importing a JSON configuration exported from desktop AzurPilot (e.g. `azurpilot (2).json`)
into the Android app was repeatedly rejected with errors such as:

```text
日期格式应为 YYYY-MM-DD HH:mm:ss: Dashboard.Oil.Record
```

And after correcting timestamps:

```text
参数必须在 0 到 150 之间: CoalitionScuttle.Emotion.Fleet1Value
```

Because the gateway backend validates arguments one-by-one and stops on the first failure, users
were forced into tedious trial-and-error cycles. Full-schema auditing against all 2,888 managed
arguments revealed 46 fields with formatting inconsistencies.

### 2. Root Cause Analysis

#### 2.1 Datetime: Microseconds vs. Whole Seconds (23 occurrences)

- **Writer** (`module/api/config_service.py` `_sync_record_time()`): Outputs 6-digit microsecond
  timestamps via `now.isoformat(sep=' ', timespec='microseconds')` (e.g. `2026-11-07 02:13:11.038831`).
- **Validator** (`module/api/config_service.py` regex check): Strictly expects
  `re.fullmatch(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}', value)`.
- **Affected fields**:
  - `Dashboard.*.Record` (12 dashboard resource timestamps)
  - `*.Emotion.Fleet1Record` / `Fleet2Record` (11 emotion timestamps)

#### 2.2 Value Out of Bounds: Negative Emotion Values (1 occurrence)

- Observed value: `CoalitionScuttle.Emotion.Fleet1Value = -1`.
- Schema rule: In `module/config/argument/args.json`, `validate` is defined as `[0, 150]`.
- In-game ship emotion can dip below 0 (-1); the exporter writes this verbatim, triggering validation failure.

#### 2.3 Type Mismatch: String Fields with Null or Numbers (22 occurrences)

- **Null for String fields (16 occurrences)**: e.g. `Alas.EmulatorInfo.RemoteStartCommand`,
  `Commission.Commission.Blacklist`, `IslandFarm.*.CropTargets`. Schema defaults to `""`, but
  unconfigured fields were exported as `null`.
- **Numbers for String fields (6 occurrences)**: e.g. `Dorm.Dorm.FeedFilter = 1000`,
  `OpsiMeowfficerFarming.OpsiMeowfficerFarming.TargetZone = 71`, `EventC.EventDaily.LastStage = 0`.
  The exporter wrote raw numeric values into text input parameters.

### 3. Android Client-Side Sanitization (`AzurPilotConfigImport`)

To eliminate the need for manual script-based JSON fixes, `AzurPilotConfigImport.kt` performs
automatic normalization before batching changes for `config.patch`:

1. **Datetime Truncation**: Matches datetime patterns, strips microseconds, replaces ISO `T` with a space,
   and normalizes output to `YYYY-MM-DD HH:mm:ss`.
2. **Range Clamping**: Clamps numerical fields with `validate: [min, max]` via `coerceIn(min, max)` while
   preserving integer types (e.g. `-1` becomes `0`).
3. **String Coercion**: Coerces `null` to empty string `""`, and numbers/booleans to string literals
   (e.g. `1000` -> `"1000"`).
4. **Preserve Internal States**: Read-only or internal state fields not managed by the editable schema
   (e.g. `RecoveryState.record`, `MeowfficerCleanupState.reset`) remain completely untouched.
5. **Delta Skipping**: Sanitized values that match current on-device settings are skipped, minimizing
   `config.patch` payloads.

### 4. Upstream Recommendations

1. **Align Writer and Validator**: Use `timespec='seconds'` in `_sync_record_time()` or broaden the
   validator regex to `r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?'`.
2. **Clamp Emotions on Export**: Clamp emotion values to `[0, 150]` upon export.
3. **Type-Safe Serialization**: Verify against `args.json` types during export to prevent raw `null`
   or numeric serialization into string fields.
4. **Aggregate Validation Errors**: Collect and report all validation failures in a single response
   rather than halting at the first mismatch.
