# 节点契约

节点是规则工作簿编译后的最小执行单元。工作表的一行可以编译成一个或多个节点，但执行引擎只认识节点。

## 1. 通用节点结构

```json
{
  "id": "node_01",
  "kind": "CHECK",
  "name": "下单时间必填",
  "stage": "CHECK",
  "enabled": true,
  "inputs": [],
  "config": {},
  "output": null,
  "dependsOn": [],
  "policy": {
    "blocking": "NONE",
    "onError": "FAIL_RUN"
  }
}
```

通用字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 节点稳定 ID，发布后不可变 |
| `kind` | 是 | 节点类型 |
| `name` | 是 | 用户可读名称 |
| `stage` | 是 | 执行阶段，用于默认排序和 UI 分组 |
| `enabled` | 是 | 是否启用 |
| `inputs` | 视类型 | 输入引用列表，每项必须带 `alias` |
| `config` | 视类型 | 类型相关参数 |
| `output` | 视类型 | 输出别名和推导出的 schema |
| `dependsOn` | 否 | 显式依赖的节点 ID |
| `policy` | 是 | 阻断和错误处理策略 |

阶段枚举：

```text
PREPARE -> CHECK -> CLEAN -> RELATE -> ENRICH -> FEATURE -> VALIDATE -> OUTPUT
```

阶段用于默认排序，但真实执行顺序以 `dependsOn` 为准。

输入约定：

- 节点消费的数据放在 `inputs` 中。
- 多输入节点通过输入别名引用，例如 `leftAlias`、`rightAlias`。
- 表输入和节点输出输入都会写入统一的 `alias`；上游输出的默认别名优先取 `outputAlias`，没有时退回 `nodeId`。
- `config` 不重复保存完整表引用，只保存键、条件和参数。
- 节点产生的新表通过 `outputAlias` 暴露给下游。

## 2. 引用类型

规则中不能只存显示名称。执行引用必须是稳定 ID，名称只作为快照展示。

### 2.1 数据集引用

```json
{
  "type": "DATASET",
  "datasetVersionId": "uuid"
}
```

### 2.2 表引用

```json
{
  "type": "TABLE",
  "datasetVersionId": "uuid",
  "tableId": "uuid",
  "alias": "orders"
}
```

`alias` 用于 join、聚合和中间输出引用。一个工作簿内别名必须唯一。

### 2.3 字段引用

```json
{
  "type": "FIELD",
  "tableRef": {
    "type": "TABLE",
    "tableId": "uuid",
    "alias": "orders"
  },
  "fieldId": "uuid",
  "nameSnapshot": "order_purchase_timestamp"
}
```

### 2.4 字段列表引用

```json
{
  "type": "FIELD_LIST",
  "fields": [
    {
      "type": "FIELD",
      "fieldId": "uuid",
      "nameSnapshot": "order_id"
    },
    {
      "type": "FIELD",
      "fieldId": "uuid",
      "nameSnapshot": "order_item_id"
    }
  ]
}
```

### 2.5 节点输出引用

```json
{
  "type": "NODE_OUTPUT",
  "nodeId": "node_join_01",
  "outputAlias": "orders_with_items"
}
```

节点输出输入会被编译成带 `alias` 的 `NODE_OUTPUT`。字段引用可以不携带 `tableId`，编译器先通过 `fieldId` / `columnId` 找到物理列，再用列的所属表校验输入上下文；只有无法解析物理列时，才依赖输入别名判断。

### 2.6 字面量

```json
{
  "type": "LITERAL",
  "dataType": "STRING",
  "value": "delivered"
}
```

### 2.7 关系引用

```json
{
  "type": "RELATION",
  "relationshipId": "uuid"
}
```

关系库描述逻辑关系。关联节点可以使用现成关系，也可以直接配置左右字段。

## 3. 条件 AST

条件不是字符串 SQL，而是结构化树。

```json
{
  "op": "IS_NULL",
  "args": [
    {
      "type": "FIELD",
      "fieldId": "uuid",
      "nameSnapshot": "purchase_ts"
    }
  ]
}
```

### 3.1 条件作用域

| 作用域 | 含义 | 典型条件 |
| --- | --- | --- |
| `ROW` | 逐行判断 | 为空、范围、日期顺序 |
| `FIELD_AGGREGATE` | 按字段/表聚合判断 | 缺失率、重复率 |
| `RELATION_AGGREGATE` | 按关联结果判断 | 匹配率、fanout |
| `DATASET_AGGREGATE` | 按整个数据集判断 | 总行数、类目占比 |

### 3.2 条件操作符

完整性：

- `IS_NULL`
- `IS_EMPTY`
- `IS_BLANK`

比较：

- `EQ`、`NE`
- `GT`、`GTE`、`LT`、`LTE`
- `BETWEEN`
- `IN`、`NOT_IN`
- `REGEX_MATCH`

日期：

- `DATE_PARSEABLE`
- `DATE_BEFORE`
- `DATE_AFTER`
- `DATE_ORDER_INVALID`

唯一性：

- `DUPLICATE`
- `UNIQUE`
- `DUPLICATE_RATE_GT`

关联：

- `FK_EXISTS`
- `JOIN_MATCH_RATE_LT`
- `JOIN_FANOUT_GT`

聚合：

- `MISSING_RATE_GT`
- `ROW_COUNT_LT`
- `CATEGORY_RATIO_LT`
- `DISTINCT_COUNT_LT`

逻辑组合：

- `AND`
- `OR`
- `NOT`

受控表达式：

- `EXPRESSION`

第一版不建议开放任意表达式。确需使用时应只允许白名单函数，并禁止访问外部资源。

## 4. 效果与策略

效果描述“命中后做什么”。

| 效果 | 适用作用域 | 说明 |
| --- | --- | --- |
| `OBSERVE` | 全部 | 只记录指标 |
| `WARN` | 全部 | 产生告警 |
| `FAIL_RUN` | 全部 | 终止运行 |
| `BLOCK_PUBLISH` | 全部 | 阻止输出发布 |
| `QUARANTINE_ROWS` | `ROW` | 命中行移入隔离区 |
| `DROP_ROWS` | `ROW` | 命中行从主输出删除 |
| `FLAG_ROWS` | `ROW` | 给命中行增加标记字段 |

策略：

```json
{
  "blocking": "NONE",
  "onError": "FAIL_RUN"
}
```

- `blocking`：`NONE`、`STAGE`、`RUN`。
- `onError`：`FAIL_RUN`、`SKIP_NODE`、`KEEP_ORIGINAL`、`QUARANTINE_ROWS`。

兼容性规则：

- 行级条件只能使用行级效果。
- 聚合条件不能使用删除行、隔离行这类行级效果。
- 聚合条件应使用告警、阻断运行或阻断发布。

## 5. 节点类型

### 5.1 CHECK（v1）

质量检查节点。可以逐行判断，也可以做聚合判断。

```json
{
  "kind": "CHECK",
  "stage": "CHECK",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "uuid",
      "alias": "orders"
    }
  ],
  "config": {
    "scope": "ROW",
    "predicate": {
      "op": "IS_NULL",
      "args": [
        {
          "type": "FIELD",
          "fieldId": "uuid",
          "nameSnapshot": "purchase_ts"
        }
      ]
    },
    "effect": "QUARANTINE_ROWS",
    "outputAlias": "orders_with_valid_purchase_ts"
  }
}
```

输出：

- `OBSERVE`、`WARN`、`FAIL_RUN`、`BLOCK_PUBLISH`：只产生指标和样例。
- `QUARANTINE_ROWS`、`DROP_ROWS`、`FLAG_ROWS`：必须产生新的主表输出，`outputAlias` 必填。
- 隔离行同时产生隔离区分支，但不占用 `outputAlias`。
- 检查指标包括命中行数、命中率、样例和运行状态。

### 5.2 TRANSFORM（v1）

字段级清洗节点。

常用操作：

- `TRIM`
- `LOWER`、`UPPER`
- `CAST`
- `REPLACE`
- `REGEX_REPLACE`
- `MAP_VALUES`
- `FILL_VALUE`
- `COALESCE`
- `DATE_PARSE`
- `CLIP_OUTLIER`
- `MASK`
- `SPLIT_COLUMN`
- `MERGE_COLUMNS`

```json
{
  "kind": "TRANSFORM",
  "stage": "CLEAN",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "uuid",
      "alias": "products"
    }
  ],
  "config": {
    "target": {
      "type": "FIELD",
      "fieldId": "uuid",
      "nameSnapshot": "category_name"
    },
    "operation": "TRIM",
    "condition": null
  }
}
```

输出：修改后的表或新字段。

### 5.3 PROJECT（v1）

选择列、重命名列、删除列。

```json
{
  "kind": "PROJECT",
  "stage": "CLEAN",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "uuid",
      "alias": "orders"
    }
  ],
  "config": {
    "select": [
      "order_id",
      "customer_id",
      "order_status"
    ],
    "rename": {
      "order_status": "status"
    },
    "drop": [
      "unused_column"
    ]
  }
}
```

### 5.4 FILTER（v1）

按条件筛选行，产生新的表。

```json
{
  "kind": "FILTER",
  "stage": "CLEAN",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "uuid",
      "alias": "products"
    }
  ],
  "config": {
    "predicate": {
      "op": "IN",
      "field": "category",
      "value": [
        "cama_mesa_banho",
        "utilidades_domesticas"
      ]
    },
    "outputAlias": "target_products"
  }
}
```

### 5.5 DEDUPE（v1）

按单键或复合键去重。

```json
{
  "kind": "DEDUPE",
  "stage": "CLEAN",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "uuid",
      "alias": "orders"
    }
  ],
  "config": {
    "keys": [
      {
        "type": "FIELD",
        "fieldId": "uuid",
        "nameSnapshot": "order_id"
      }
    ],
    "keep": "LATEST",
    "orderBy": [
      {
        "field": "updated_at",
        "direction": "DESC"
      }
    ],
    "outputAlias": "orders_deduped"
  }
}
```

### 5.6 JOIN（v1）

两个输入之间的关系操作。

```json
{
  "kind": "JOIN",
  "stage": "RELATE",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "items",
      "alias": "items"
    },
    {
      "type": "TABLE",
      "tableId": "orders",
      "alias": "orders"
    }
  ],
  "config": {
    "leftAlias": "items",
    "rightAlias": "orders",
    "leftKeys": [
      {
        "type": "FIELD",
        "fieldId": "items_order_id"
      }
    ],
    "rightKeys": [
      {
        "type": "FIELD",
        "fieldId": "orders_order_id"
      }
    ],
    "joinType": "LEFT",
    "cardinality": "N:1",
    "duplicatePolicy": "FAIL",
    "outputAlias": "items_with_orders",
    "guards": {
      "rightKeyUnique": true,
      "maxFanout": 1,
      "minMatchRate": 0.95
    }
  }
}
```

输出：新的逻辑表，后续节点通过 `NODE_OUTPUT` 引用。

### 5.7 AGGREGATE（v1）

分组聚合，特别适合把 1:N 明细表压成 1:1 订单宽表。

```json
{
  "kind": "AGGREGATE",
  "stage": "ENRICH",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "payments",
      "alias": "payments"
    }
  ],
  "config": {
    "groupBy": [
      "order_id"
    ],
    "metrics": [
      {
        "name": "payment_count",
        "op": "COUNT",
        "field": "*"
      },
      {
        "name": "payment_value",
        "op": "SUM",
        "field": "payment_value"
      }
    ],
    "outputAlias": "order_payments_agg"
  }
}
```

### 5.8 DERIVE（v1）

生成新字段。

```json
{
  "kind": "DERIVE",
  "stage": "FEATURE",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "orders",
      "alias": "orders"
    }
  ],
  "config": {
    "outputField": "delay_days",
    "dataType": "DECIMAL",
    "expression": {
      "op": "DATE_DIFF_DAYS",
      "left": "order_delivered_customer_date",
      "right": "order_estimated_delivery_date"
    }
  }
}
```

### 5.9 UNION（v1.1）

合并多个结构兼容的输入。

```json
{
  "kind": "UNION",
  "stage": "RELATE",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "orders_a",
      "alias": "orders_a"
    },
    {
      "type": "TABLE",
      "tableId": "orders_b",
      "alias": "orders_b"
    }
  ],
  "config": {
    "inputAliases": [
      "orders_a",
      "orders_b"
    ],
    "mode": "ALL",
    "outputAlias": "all_orders",
    "columnMapping": {}
  }
}
```

### 5.10 SAMPLE（v1.1）

抽样节点，用于预览、训练集缩小或探索分析。

```json
{
  "kind": "SAMPLE",
  "stage": "PREPARE",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "orders",
      "alias": "orders"
    }
  ],
  "config": {
    "method": "RANDOM",
    "size": 1000,
    "seed": 42,
    "outputAlias": "orders_sample"
  }
}
```

### 5.11 SPLIT（v1.1）

训练、验证、测试拆分。

```json
{
  "kind": "SPLIT",
  "stage": "OUTPUT",
  "inputs": [
    {
      "type": "TABLE",
      "tableId": "orders",
      "alias": "orders"
    }
  ],
  "config": {
    "method": "RANDOM",
    "seed": 42,
    "splits": [
      {
        "name": "train",
        "ratio": 0.7
      },
      {
        "name": "validation",
        "ratio": 0.15
      },
      {
        "name": "test",
        "ratio": 0.15
      }
    ],
    "stratifyBy": [
      "is_delayed"
    ]
  }
}
```

### 5.12 GATE（v1）

读取检查指标或运行指标，决定是否继续或阻断发布。

```json
{
  "kind": "GATE",
  "stage": "VALIDATE",
  "inputs": [],
  "config": {
    "metricRef": "item_orders_join.match_rate",
    "comparator": "GTE",
    "threshold": 0.95,
    "effect": "BLOCK_PUBLISH"
  },
  "dependsOn": [
    "node_join_01"
  ]
}
```

`metricRef` 推荐使用 `<nodeId>.<metricName>` 或 `<outputAlias>.<metricName>`。如果 GATE 没有显式写 `inputs`，编译器会根据 `metricRef` 的前缀自动查找上游节点并补一条依赖边；如果前缀无法匹配，则校验失败。显式 `dependsOn` 仍可保留，用于表达额外顺序约束。

### 5.13 OUTPUT（v1）

运行终态，生成数据集版本、导出文件或隔离区。

```json
{
  "kind": "OUTPUT",
  "stage": "OUTPUT",
  "inputs": [
    {
      "type": "NODE_OUTPUT",
      "nodeId": "node_join_02",
      "outputAlias": "training_data"
    }
  ],
  "config": {
    "mode": "MATERIALIZE_VERSION",
    "format": "PARQUET",
    "name": "olist_order_delay_training",
    "partitionBy": []
  }
}
```

### 5.14 PIVOT / UNPIVOT（later）

行列转换属于高级分析操作，第一版不实现，但节点类型预留。

### 5.15 CUSTOM（later）

受控自定义节点。只允许管理员使用，必须有输入契约、输出契约、超时限制和沙箱，不作为普通规则开放。

## 6. 依赖和执行顺序

不能只靠工作表行号决定执行顺序。每个节点必须声明输入和依赖：

- 节点只能读取已经存在的表、字段或上游节点输出。
- 工作表行号只用于展示。
- 编译后生成有向无环图。
- 发现循环依赖直接阻断发布。
- 关联和聚合产生的新输出必须显式命名。

## 7. 编译不变量

任何节点发布前必须满足：

- 所有引用解析成功。
- 节点读取的数据集与工作簿绑定数据集一致。
- `kind` 和 `config` 匹配。
- 输出别名唯一。
- 节点依赖无环。
- 效果与条件作用域兼容。
- 关联节点有基数策略和 fanout 保护。
- 门禁节点读取已产生的指标。
- 输出节点是终态，不能被其他节点依赖。

## 8. 第一版节点范围

必须实现：

```text
CHECK
TRANSFORM
PROJECT
FILTER
DEDUPE
JOIN
AGGREGATE
DERIVE
GATE
OUTPUT
```

可以稍后实现：

```text
UNION
SAMPLE
SPLIT
PIVOT / UNPIVOT
CUSTOM
```
