# 节点参数 Schema

本文件定义每类节点的 `config` 结构。前端据此渲染参数表单，后端据此校验，执行层据此编译。

## 1. 约定

- `?` 表示可选。
- `[]` 表示数组。
- `A | B` 表示枚举。
- 所有节点自带 `schemaVersion`，第一版为 `1`。
- 所有引用使用稳定 ID，名称只作为快照。
- 节点消费的数据放在通用 `inputs` 中，每项带 `alias`。表输入和节点输出输入都会统一补上别名；关联和聚合通过该别名引用左右输入或上游输出。
- `config` 通过 `leftAlias`、`rightAlias`、`inputAliases` 引用输入。

## 2. 公共类型

### 2.1 DataRef

```text
DataRef =
  { "type": "TABLE", "tableId": string, "alias": string, "datasetVersionId"?: string }
  | { "type": "NODE_OUTPUT", "nodeId": string, "outputAlias": string }
```

### 2.2 FieldRef

```text
FieldRef = {
  "type": "FIELD",
  "tableRef"?: DataRef,
  "fieldId": string,
  "nameSnapshot"?: string
}
```

当前编译器还接受字段引用直接携带 `tableId` 和 `columnId`。解析顺序是：先按 `columnId` 找物理列，再按 `fieldId` 找当前数据集版本的字段绑定，最后用物理列的 `tableId` 校验字段所属表。`nameSnapshot` 只用于展示，不参与执行。

### 2.3 ValueRef

```text
ValueRef =
  { "type": "LITERAL", "dataType": string, "value": any }
  | { "type": "FIELD", "fieldId": string, "nameSnapshot"?: string }
  | { "type": "LIST", "dataType": string, "values": [] }
  | { "type": "RANGE", "from": any, "to": any, "includeFrom": boolean, "includeTo": boolean }
```

### 2.4 Predicate

```text
Predicate =
  { "op": "IS_NULL", "args": [FieldRef | ValueRef] }
  | { "op": "IS_EMPTY", "args": [FieldRef] }
  | { "op": "EQ" | "NE" | "GT" | "GTE" | "LT" | "LTE", "left": ValueRef, "right": ValueRef }
  | { "op": "BETWEEN", "field": FieldRef, "from": ValueRef, "to": ValueRef }
  | { "op": "IN" | "NOT_IN", "field": FieldRef, "values": [] }
  | { "op": "REGEX_MATCH", "field": FieldRef, "pattern": string }
  | { "op": "DATE_ORDER_INVALID", "earlier": FieldRef, "later": FieldRef }
  | { "op": "DUPLICATE", "fields": [FieldRef] }
  | { "op": "UNIQUE", "fields": [FieldRef] }
  | { "op": "MISSING_RATE_GT", "field": FieldRef, "threshold": number }
  | { "op": "DUPLICATE_RATE_GT", "fields": [FieldRef], "threshold": number }
  | { "op": "JOIN_MATCH_RATE_LT", "relationRef": string, "threshold": number }
  | { "op": "JOIN_FANOUT_GT", "relationRef": string, "threshold": number }
  | { "op": "ROW_COUNT_LT", "threshold": number }
  | { "op": "CATEGORY_RATIO_LT", "field": FieldRef, "category": ValueRef, "threshold": number }
  | { "op": "AND" | "OR", "predicates": [Predicate] }
  | { "op": "NOT", "predicate": Predicate }
```

### 2.5 Effect

```text
Effect =
  "OBSERVE"
  | "WARN"
  | "FAIL_RUN"
  | "BLOCK_PUBLISH"
  | "QUARANTINE_ROWS"
  | "DROP_ROWS"
  | "FLAG_ROWS"
```

### 2.6 Policy

```text
Policy = {
  "blocking": "NONE" | "STAGE" | "RUN",
  "onError": "FAIL_RUN" | "SKIP_NODE" | "KEEP_ORIGINAL" | "QUARANTINE_ROWS"
}
```

## 3. CHECK

```text
CHECK.config = {
  "schemaVersion": 1,
  "scope": "ROW" | "FIELD_AGGREGATE" | "RELATION_AGGREGATE" | "DATASET_AGGREGATE",
  "predicate": Predicate,
  "effect": Effect,
  "outputAlias"?: string,
  "effectParams"?: {
    "reason"?: string,
    "flagField"?: string,
    "sampleLimit"?: number
  }
}
```

校验：

- `ROW` 只能搭配 `QUARANTINE_ROWS`、`DROP_ROWS`、`FLAG_ROWS`、`OBSERVE`、`WARN`。
- `FIELD_AGGREGATE`、`RELATION_AGGREGATE`、`DATASET_AGGREGATE` 不能搭配 `QUARANTINE_ROWS`、`DROP_ROWS`、`FLAG_ROWS`。
- `FLAG_ROWS` 必须提供 `flagField`。
- 使用 `QUARANTINE_ROWS`、`DROP_ROWS`、`FLAG_ROWS` 时 `outputAlias` 必填，并在节点 `output` 中声明新主表。
- 聚合条件必须引用可计算指标。

## 4. TRANSFORM

```text
TRANSFORM.config = {
  "schemaVersion": 1,
  "target": FieldRef,
  "operation": TransformOperation,
  "params": object,
  "condition"?: Predicate,
  "outputField"?: string
}
```

`TransformOperation`：

```text
"TRIM"
"LOWER"
"UPPER"
"CAST"
"REPLACE"
"REGEX_REPLACE"
"MAP_VALUES"
"FILL_VALUE"
"COALESCE"
"DATE_PARSE"
"CLIP_OUTLIER"
"MASK"
"SPLIT_COLUMN"
"MERGE_COLUMNS"
```

常用 `params`：

- `CAST`：`{ "dataType": string, "dateFormat"?: string, "onError": "FAIL" | "SET_NULL" | "KEEP_ORIGINAL" }`
- `REPLACE`：`{ "find": string, "replace": string, "regex": boolean }`
- `MAP_VALUES`：`{ "mappings": [{ "from": any, "to": any }], "defaultValue"?: any }`
- `FILL_VALUE`：`{ "value": any, "fillWhen": "NULL" | "EMPTY" | "BOTH" }`
- `CLIP_OUTLIER`：`{ "method": "IQR" | "ZSCORE", "threshold": number, "action": "CLIP" | "SET_NULL" | "FLAG" }`
- `MASK`：`{ "mode": "FULL" | "PARTIAL", "keepPrefix": number, "keepSuffix": number, "maskChar": string }`

## 5. PROJECT

```text
PROJECT.config = {
  "schemaVersion": 1,
  "select"?: [string | FieldRef],
  "rename"?: object,
  "drop"?: [string | FieldRef],
  "outputAlias": string
}
```

校验：

- `select` 和 `drop` 不能同时为空。
- `rename` 的新名称不能与已有字段冲突。
- `outputAlias` 在工作簿内唯一。

## 6. FILTER

```text
FILTER.config = {
  "schemaVersion": 1,
  "predicate": Predicate,
  "outputAlias": string
}
```

校验：

- 条件作用域必须是 `ROW`。
- `outputAlias` 唯一。
- 过滤后的 schema 与输入一致。

## 7. DEDUPE

```text
DEDUPE.config = {
  "schemaVersion": 1,
  "keys": [FieldRef],
  "keep": "FIRST" | "LAST" | "LATEST" | "EARLIEST",
  "orderBy"?: [{ "field": FieldRef, "direction": "ASC" | "DESC" }],
  "outputAlias": string
}
```

校验：

- `keys` 至少一个字段。
- `keep` 为 `LATEST` 或 `EARLIEST` 时必须提供 `orderBy`。
- 复合键字段必须来自同一输入表。

## 8. JOIN

```text
JOIN.config = {
  "schemaVersion": 1,
  "leftAlias": string,
  "rightAlias": string,
  "leftKeys": [FieldRef],
  "rightKeys": [FieldRef],
  "joinType": "INNER" | "LEFT" | "RIGHT" | "FULL",
  "cardinality": "1:1" | "1:N" | "N:1" | "N:N",
  "duplicatePolicy": "FAIL" | "KEEP_FIRST" | "KEEP_LAST" | "AGGREGATE",
  "outputAlias": string,
  "guards"?: {
    "rightKeyUnique"?: boolean,
    "maxFanout"?: number,
    "minMatchRate"?: number,
    "onViolation"?: "WARN" | "FAIL_RUN"
  }
}
```

校验：

- `leftAlias` 和 `rightAlias` 必须能在通用 `inputs` 中找到。
- 左右键数量一致。
- 左右键类型兼容。
- `1:1` 和 `N:1` 应要求右表键唯一，除非选择了去重策略。
- `maxFanout` 默认建议 `1`。
- `minMatchRate` 范围 `0..1`。
- 输出别名唯一。

## 9. AGGREGATE

```text
AGGREGATE.config = {
  "schemaVersion": 1,
  "groupBy": [FieldRef],
  "metrics": [{
    "name": string,
    "op": "COUNT" | "COUNT_DISTINCT" | "SUM" | "AVG" | "MIN" | "MAX" | "MEDIAN",
    "field": FieldRef | "*",
    "params"?: object
  }],
  "having"?: Predicate,
  "outputAlias": string
}
```

校验：

- 指标名称唯一。
- `COUNT` 可以使用 `*`，其他指标必须指定字段。
- 指标类型与字段类型兼容。
- `having` 只能引用聚合结果。

## 10. DERIVE

```text
DERIVE.config = {
  "schemaVersion": 1,
  "outputField": string,
  "dataType": "STRING" | "INTEGER" | "DECIMAL" | "BOOLEAN" | "DATE" | "DATETIME",
  "expression": Expression,
  "replaceExisting"?: boolean
}
```

`Expression` 第一版支持：

```text
"FIELD"
"LITERAL"
"DATE_DIFF_DAYS"
"DATE_PART"
"CONCAT"
"COALESCE"
"CASE_WHEN"
"ARITHMETIC"
"DISTANCE_HAVERSINE"
```

校验：

- 输出字段名不能与已有字段冲突，除非 `replaceExisting=true`。
- 引用的字段必须在上游可见。
- 表达式结果类型必须与 `dataType` 兼容。
- 不允许任意代码或外部函数。

## 11. UNION

```text
UNION.config = {
  "schemaVersion": 1,
  "inputAliases": [string],
  "mode": "ALL" | "DISTINCT",
  "columnMapping"?: object,
  "outputAlias": string
}
```

校验：

- 至少两个输入。
- 所有 `inputAliases` 都能在通用 `inputs` 中找到。
- 映射后的列名和类型一致。

## 12. SAMPLE

```text
SAMPLE.config = {
  "schemaVersion": 1,
  "method": "RANDOM" | "HEAD" | "STRATIFIED",
  "size"?: number,
  "fraction"?: number,
  "seed": number,
  "stratifyBy"?: [FieldRef],
  "outputAlias": string
}
```

校验：

- `size` 和 `fraction` 至少提供一个。
- `fraction` 范围 `0..1`。
- `STRATIFIED` 必须提供 `stratifyBy`。
- 固定 `seed`，保证可复现。

## 13. SPLIT

```text
SPLIT.config = {
  "schemaVersion": 1,
  "method": "RANDOM" | "STRATIFIED",
  "seed": number,
  "splits": [{ "name": string, "ratio": number }],
  "stratifyBy"?: [FieldRef]
}
```

校验：

- 至少两个拆分。
- 比例之和必须为 `1`，允许极小浮点误差。
- 拆分名称唯一。
- 必须固定随机种子。

## 14. GATE

```text
GATE.config = {
  "schemaVersion": 1,
  "metricRef": string,
  "comparator": "GT" | "GTE" | "LT" | "LTE" | "EQ" | "NE",
  "threshold": number | string | boolean,
  "effect": "WARN" | "FAIL_RUN" | "BLOCK_PUBLISH"
}
```

`metricRef` 格式：

```text
<nodeId>.<metricName>
<outputAlias>.<metricName>
```

校验：

- 指标必须由上游 CHECK、JOIN 或运行指标产生。
- 阈值类型和指标类型兼容。
- `GATE` 不能修改数据。

## 15. OUTPUT

```text
OUTPUT.config = {
  "schemaVersion": 1,
  "mode": "MATERIALIZE_VERSION" | "EXPORT" | "QUARANTINE",
  "format": "PARQUET" | "CSV" | "JSONL" | "XLSX",
  "name": string,
  "partitionBy"?: [string],
  "overwritePolicy": "FAIL_IF_EXISTS" | "REPLACE" | "NEW_VERSION"
}
```

校验：

- `OUTPUT` 是终态，不能被其他节点依赖。
- `MATERIALIZE_VERSION` 必须产生新的数据集版本。
- `EXPORT` 必须指定格式和名称。
- `QUARANTINE` 必须指定来源检查节点。

## 16. PIVOT / UNPIVOT（later）

```text
PIVOT.config = {
  "schemaVersion": 1,
  "index": [FieldRef],
  "columns": FieldRef,
  "values": FieldRef,
  "aggFunc": "SUM" | "COUNT" | "AVG" | "MIN" | "MAX",
  "outputAlias": string
}
```

第一版不实现，仅预留契约。

## 17. CUSTOM（later）

```text
CUSTOM.config = {
  "schemaVersion": 1,
  "runtime": "PYTHON" | "SQL",
  "entrypoint": string,
  "inputContract": object,
  "outputContract": object,
  "timeoutSeconds": number,
  "resourceProfile": string
}
```

限制：

- 只有管理员可以创建。
- 必须通过沙箱运行。
- 必须声明输入、输出和资源限制。
- 不能访问外部网络和未授权存储。

## 18. 校验矩阵

| 节点 | 输入 | 输出 | 是否修改数据 | 是否可被依赖 |
| --- | --- | --- | --- | --- |
| `CHECK` | 表/节点输出 | 指标、样例、可选主表 | 行级效果会修改 | 可以 |
| `TRANSFORM` | 表 | 表/字段 | 是 | 可以 |
| `PROJECT` | 表 | 表 | 是 | 可以 |
| `FILTER` | 表 | 表 | 是 | 可以 |
| `DEDUPE` | 表 | 表 | 是 | 可以 |
| `JOIN` | 两个输入 | 表 | 是 | 可以 |
| `AGGREGATE` | 表 | 表 | 是 | 可以 |
| `DERIVE` | 表 | 表/字段 | 是 | 可以 |
| `UNION` | 多个表 | 表 | 是 | 可以 |
| `SAMPLE` | 表 | 表 | 是 | 可以 |
| `SPLIT` | 表 | 多个表 | 是 | 可以 |
| `GATE` | 指标 | 无 | 否 | 可以 |
| `OUTPUT` | 表/节点输出 | 数据集版本 | 是 | 不可以 |
