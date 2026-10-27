# 字段库与规则库设计说明（给另一个 AI 的交接文档）

> 用途：把“字段库 + Excel 式规则库”的产品想法、已确定的设计原则、当前实现状态和后续边界完整交给另一个 AI，方便它继续设计、评审或实现。
>
> 项目：`ML-Based-Order-Delay-Prediction-System-for-Olist`
>
> 日期：2026-10-26

---

## 0. 一句话结论

这套模块不是“把规则写进一张表”，而是：

```text
数据集上传
  -> 字段库（数据集/表/字段目录 + 稳定字段引用）
  -> 规则工作簿（Excel 式单元格编排）
  -> 类型化行/列
  -> 编译成 RuleNode 有向无环图
  -> 编译成 PipelinePlan
  -> 预览运行 / 发布不可变版本 / 全量运行
```

核心原则是：

- 编辑层负责“用户怎么填”。
- 语义层负责“这条规则是什么意思”。
- 执行层负责“怎么算出来”。
- 前端工作表不能直接成为执行模型，执行必须以编译后的节点图为准。
- 字段和表使用稳定 ID 引用，显示名称只作为快照。
- 不在规则库里开放任意 SQL / Python / JS。

---

## 1. 用户最初的产品想法

用户希望解决的问题不是单纯“管理规则”，而是让业务用户能够自己编排一条数据分析链路。原始想法可以拆为：

1. 先上传数据集。
2. 建立“字段库”，从数据集导入字段。
3. 字段库中的字段至少包含：数据集名称、数据表名称、数据表内字段名称。
4. 导入后的字段可以在字段库中查看、搜索、编辑。
5. 规则库看起来像 Excel 工作表：一行一行编排规则。
6. 配置规则时可以选择数据集、选择表、选择字段。
7. 可以配置组合规则，由用户自己编排处理步骤。
8. 可以新建多个规则库并分别命名，例如“规则库1”“规则库2”。
9. 规则库可以展开编辑，单元格像 Excel 一样点击后出现下拉选项。
10. 用户希望自己决定某一列是什么类型，例如“这一列是选择数据集”。
11. 规则不只是简单条件，还要能表达动作，例如：
    - 字段为空说明这条数据是脏数据，把整行清除或隔离。
    - A 数据集的订单表通过 `customer_id` 关联客户表。
    - 多个表通过外键字段继续关联。
12. 用户不满足于“动作”这个单一名称，希望从零设计一套合理的规则模型，而不是把一切硬塞进“动作”概念。

---

## 2. 对原始想法做出的关键收敛

用户的直觉是对的：Excel 式体验适合规则编排。但有几个地方必须在架构上收口，否则规则会变成不可验证的“自由表格”。

### 2.1 一个规则工作簿绑定一个数据集版本

确定的设计是：

```text
RuleWorkbook.datasetVersionId = 一个明确的数据集版本
```

不是每一行都能选任意数据集。

原因：

- 一次运行需要一个明确的主输入上下文。
- 字段引用、表引用、类型校验都需要稳定 schema。
- 如果每行可以隐含选择不同数据集，跨表关联和字段级联会产生歧义。
- 发布和运行必须可复现，不能让“当前数据集”随 UI 状态漂移。

需要跨表时，在同一个数据集版本内选择不同表，并通过 `JOIN` 显式关联。

需要跨版本复用时，未来可引入 `DatasetContract`：规则绑定数据契约，运行时再绑定具体版本。第一版不强制。

### 2.2 Excel 式交互，但系统列必须有语义类型

不能允许用户把任意一列随意改成“数据集选择器”“字段引用”或“任意表达式”，否则编译器不知道哪一列驱动执行。

确定的设计是：

- 工作簿中的每个 Sheet 对应一类处理阶段。
- 每个 Sheet 有系统列，例如输入、字段、条件、关联键、效果、输出别名。
- 系统列可以改名、隐藏、调宽，但不能删掉或改成无关类型。
- 用户可以新增自定义列，但自定义列只用于管理信息，不能参与执行。
- 用户编排的是“规则行”和规则内容，而不是重新定义编译器的语义。

这既保留了类似 Excel 的自由度，又保证执行语义可验证。

### 2.3 “动作”应拆成三个概念

不要把一切都叫“动作”。更合理的拆法是：

- `kind`：节点类型，表示这条规则做什么，例如 `CHECK`、`JOIN`、`FILTER`。
- `config`：节点参数，表达用什么字段、什么键、什么阈值。
- `effect / policy`：命中或失败后的结果，例如隔离、删除、阻断发布、失败退出。

例如“字段为空则清除整行”不是单纯的字段动作，而是：

```text
kind = CHECK
predicate = IS_NULL(field)
effect = QUARANTINE_ROWS 或 DROP_ROWS
```

“通过 customer_id 关联两张表”不是字段动作，而是：

```text
kind = JOIN
leftInput + leftKeys + rightInput + rightKeys + cardinality
```

---

## 3. 设计目标与非目标

### 3.1 目标

- 支持从数据集自动探测表、列和类型。
- 支持字段目录导入、幂等去重和字段编辑。
- 支持 Excel 式规则工作簿和单元格类型化。
- 支持单表清洗、条件筛选、字段投影、去重、多表关联、聚合、派生、质量门禁和输出。
- 支持一条规则内组合多个条件。
- 支持多行编排成链路，输入可以引用原始表或上游节点输出。
- 支持草稿、校验、发布版本、预览运行和全量运行。
- 发布版本不可变，运行冻结工作簿版本和数据集版本。
- 前端有清晰的字段级联和错误提示。

### 3.2 非目标

- 不开放任意 SQL、Python、JS 作为普通规则。
- 不允许一次运行隐式混合多个数据集版本。
- 不把模型算法选择、超参数和训练策略混入数据规则库。
- 不把 Excel 公式、合并单元格、自由绘图当成执行语义。
- 不把工作表的行列布局直接交给执行引擎。

---

## 4. 两个库的关系

```text
数据集库 Dataset
  |-- 数据集版本 dataset_versions
  |-- 数据表 dataset_tables
  |-- 物理列 dataset_columns
  |
  v
字段库 Field Library
  |-- 逻辑字段 field_definitions
  |-- 字段绑定 dataset_field_bindings
  |
  v
规则库 Rule Library
  |-- 规则工作簿 rule_workbooks
  |-- 草稿 rule_workbook_drafts
  |-- 发布版本 rule_workbook_versions
  |-- 运行 rule_runs / rule_run_nodes
```

- 字段库解决“有哪些业务字段，它们来自哪张表的哪一列”。
- 规则库解决“这些字段如何被检查、清洗、关联和输出”。
- 规则库依赖字段目录和物理列，但不复制字段定义。
- 字段引用保存逻辑 `fieldId` 和物理 `columnId`，显示名只做快照。

---

## 5. 字段库设计

### 5.1 三个层次

| 层次 | 实体 | 说明 |
| --- | --- | --- |
| 物理列 | `dataset_columns` | 某个数据集版本中某张表的真实列、类型、序号 |
| 逻辑字段 | `field_definitions` | 用户可维护的字段定义，包含名称、显示名、语义角色、来源说明 |
| 绑定关系 | `dataset_field_bindings` | 逻辑字段与某个数据集版本中的物理列的绑定 |

字段目录导入后，字段名称通常采用：

```text
<表名>.<列名>
```

例如：

```text
orders.order_id
orders.customer_id
orders.order_status
customers.customer_id
customers.customer_city
```

### 5.2 导入流程

```text
选择数据集版本
  -> GET /api/v1/fields/catalog?datasetVersionId=...
  -> 展示未导入和已导入字段
  -> POST /api/v1/fields/import
  -> 生成 field_definitions + dataset_field_bindings
  -> 再次导入时按列绑定跳过已导入项
```

导入是幂等的。实测：

- 第一次导入：`created=5, skipped=0`
- 第二次导入：`created=0, skipped=5`

### 5.3 字段库可编辑内容

已导入字段可以修改：

- 字段名。
- 显示名。
- 数据类型。
- 语义角色，例如主键、外键、时间、金额、类别。
- 来源表说明。
- 描述。
- 状态，例如 `ACTIVE`、`ARCHIVED`。

字段库页面支持按表名、列名、字段名、显示名、类型搜索。

### 5.4 稳定引用

规则字段引用推荐结构：

```json
{
  "type": "FIELD",
  "fieldId": "逻辑字段 UUID",
  "columnId": "物理列 UUID",
  "tableId": "所属表 UUID",
  "nameSnapshot": "customer_id"
}
```

解析顺序：

1. 优先按 `columnId` 找物理列。
2. 如果没有 `columnId`，按 `fieldId` 查当前数据集版本的字段绑定。
3. 再用物理列的 `tableId` 校验字段属于哪个输入上下文。
4. `nameSnapshot` 只用于提示、迁移和显示，不参与执行。

### 5.5 当前字段库边界

当前实现按“数据集版本”导入字段。跨数据集版本的同一业务字段复用尚未启用。未来需要把字段绑定唯一约束和导入匹配逻辑调整为“业务身份 + 数据集版本”，而不是每次导入都创建新的逻辑字段。
---

## 6. 规则库设计

### 6.1 工作簿模型

```text
RuleWorkbook
  -> Sheet
      -> Column
      -> Row
          -> Cell
```

- `RuleWorkbook`：一个命名的规则库，例如“订单训练数据清洗”。
- `Sheet`：一类处理阶段，例如“字段检查”“多表关联”。
- `Column`：有语义类型的列定义。
- `Row`：一条用户编排的规则或处理步骤。
- `Cell`：行和列的交叉值。

工作簿草稿是一个 JSON 文档，不是“每个单元格一行数据库记录”。原因是：

- 工作表布局变化频繁，文档快照更容易版本化。
- 单元格类型和参数 schema 由代码控制，不需要为每种规则建表。
- 发布时统一编译成节点图，执行层不依赖 UI 行列结构。
- 如果以后需要按单元格查询，可以增加投影表或搜索引擎，不改变主文档模型。

### 6.2 默认的 10 张工作表

| Sheet key | 中文名 | 默认节点 | 主要输入 | 主要输出 |
| --- | --- | --- | --- | --- |
| `field_checks` | 字段检查 | `CHECK` | 表 / 上游输出 | 指标、隔离集、可选主表 |
| `transforms` | 清洗转换 | `TRANSFORM` | 表 / 上游输出 | 清洗后的表 |
| `filters` | 行筛选 | `FILTER` | 表 / 上游输出 | 过滤后的表 |
| `projects` | 字段投影 | `PROJECT` | 表 / 上游输出 | 保留/删除字段后的表 |
| `dedupes` | 去重 | `DEDUPE` | 表 / 上游输出 | 去重后的表 |
| `relations` | 多表关联 | `JOIN` | 左右两个输入 | 关联后的表 |
| `aggregates` | 聚合 | `AGGREGATE` | 表 / 上游输出 | 分组聚合表 |
| `derived` | 特征派生 | `DERIVE` | 表 / 上游输出 | 增加派生字段的表 |
| `gates` | 质量门禁 | `GATE` | 上游指标 | 无数据输出，阻断/告警 |
| `outputs` | 输出 | `OUTPUT` | 表 / 上游输出 | 新数据集版本、文件、隔离区 |

这意味着用户看到的是一个像一个工作簿的多张工作表，而不是一个巨大的万能规则表。

### 6.3 系统列与自定义列

系统列由代码定义，例如：

- `enabled`
- `group`
- `input` / `leftInput` / `rightInput`
- `field` / `leftField` / `rightField`
- `condition`
- `effect`
- `joinType`
- `cardinality`
- `outputAlias`
- `metrics`
- `metricRef`
- `threshold`

自定义列允许：

```text
TEXT
NUMBER
BOOLEAN
ENUM
DATE_VALUE
TAGS
```

自定义列可以放：

- 负责人。
- 业务说明。
- 部门。
- 优先级。
- 审核备注。

自定义列不能参与编译，不能承载 `FIELD_REF`、`TABLE_REF` 之类的执行语义。列键必须以 `custom_` 开头。

### 6.4 单元格类型

| cellType | 用途 | 值形态 |
| --- | --- | --- |
| `INPUT_REF` | 节点输入，可选表或上游输出 | `{type: TABLE/NODE_OUTPUT, ...}` |
| `TABLE_REF` | 强制选择原始表 | `{type: TABLE, tableId, alias}` |
| `FIELD_REF` | 选择单个字段 | `{type: FIELD, fieldId, columnId, tableId, nameSnapshot}` |
| `FIELD_LIST_REF` | 选择字段组合 | 字段引用数组 |
| `NODE_OUTPUT_REF` | 选择上游节点输出 | `{type: NODE_OUTPUT, nodeId, outputAlias}` |
| `PREDICATE` | 条件树 | `{op, args / field / fields / predicates}` |
| `PARAM_OBJECT` | 复杂参数 | JSON 对象 |
| `EFFECT` | 校验效果 | 固定枚举 |
| `OPERATION` | 清洗操作 | 固定枚举 |
| `JOIN_TYPE` | 关联类型 | `INNER / LEFT / RIGHT / FULL` |
| `CARDINALITY` | 关联基数 | `1:1 / 1:N / N:1 / N:N` |
| `NUMBER` | 数值 | number |
| `BOOLEAN` | 布尔 | boolean |
| `ENUM` | 固定选项 | string |
| `TEXT` | 文本/备注 | string |
| `GROUP` | 分组展示 | string |
| `OUTPUT_ALIAS` | 输出别名 | string |

### 6.5 一行规则示例

字段为空并隔离整行：

```json
{
  "id": "check_order_id",
  "enabled": true,
  "cells": {
    "table": {
      "type": "TABLE",
      "tableId": "orders-table-uuid"
    },
    "field": {
      "type": "FIELD",
      "fieldId": "order-id-field-uuid",
      "columnId": "order-id-column-uuid",
      "tableId": "orders-table-uuid",
      "nameSnapshot": "order_id"
    },
    "condition": {
      "op": "IS_NULL",
      "args": [
        {
          "type": "FIELD",
          "fieldId": "order-id-field-uuid",
          "columnId": "order-id-column-uuid",
          "nameSnapshot": "order_id"
        }
      ]
    },
    "effect": "QUARANTINE_ROWS",
    "outputAlias": "orders_checked"
  }
}
```

### 6.6 字段级联

```text
工作簿选择数据集
  -> 行选择输入（表或上游输出）
  -> 字段下拉只显示该输入上下文中可用的字段
  -> 选择字段后写入 fieldId / columnId / tableId / nameSnapshot
```

级联规则：

- 输入变化时，字段值如果不在新上下文中，应清空或标记待确认。
- 字段引用必须属于当前输入的表。
- 上游节点输出可以作为输入，编译器会给输入补一个 `alias`。
- 节点输出别名在工作簿内必须唯一。
- 关联和聚合使用输入别名引用左右输入或上游输出。

### 6.7 组合规则

组合规则要分两类：

1. 条件组合：同一行内用 `AND` / `OR` / `NOT` 组合谓词。
2. 步骤组合：多行分别编译成节点，通过 `outputAlias` 和 `NODE_OUTPUT` 串成链路。

例如：

```text
行1：CHECK 空值隔离
行2：FILTER order_status = delivered
行3：DEDUPE order_id keep LAST
行4：PROJECT order_id, customer_id
行5：JOIN customers
行6：AGGREGATE 按 customer_id 计数
行7：GATE row_count >= 1
行8：OUTPUT Parquet
```

行号只用于展示和默认排序，真实执行顺序由输入引用和依赖边决定。发现环依赖必须阻断发布。

### 6.8 为什么不允许每行重新选数据集

因为规则执行必须可复现。允许每行选不同数据集会带来：

- 字段 ID 上下文不稳定。
- 跨数据集隐式混合。
- 无法确定主输入 schema。
- 发布快照无法唯一绑定数据契约。

正确方式是：

- 工作簿绑定一个数据集版本。
- 同一版本内的多张表通过表引用和 JOIN 组合。
- 未来如果要跨版本复用规则，引入 `DatasetContract`，而不是在单元格里塞数据集。

---

## 7. 节点与动作语义

### 7.1 节点类型

| kind | 作用 | 输入 | 输出 | 关键配置 |
| --- | --- | --- | --- | --- |
| `CHECK` | 质量检查、隔离、删除、标记 | 1 个 | 指标/隔离集/可选主表 | `scope`, `predicate`, `effect`, `effectParams`, `outputAlias` |
| `TRANSFORM` | 字段级清洗 | 1 个 | 同 schema 的表 | `target`, `operation`, `params`, `condition`, `outputField` |
| `FILTER` | 保留满足条件的行 | 1 个 | 过滤后的表 | `predicate`, `outputAlias` |
| `PROJECT` | 保留/删除/重命名字段 | 1 个 | 投影后的表 | `select`, `drop`, `rename`, `outputAlias` |
| `DEDUPE` | 按字段组合去重 | 1 个 | 去重后的表 | `keys`, `keep`, `orderBy`, `outputAlias` |
| `JOIN` | 多表关联 | 2 个 | 关联后的表 | `leftAlias`, `rightAlias`, `leftKeys`, `rightKeys`, `joinType`, `cardinality`, `guards`, `outputAlias` |
| `AGGREGATE` | 分组聚合 | 1 个 | 聚合表 | `groupBy`, `metrics`, `having`, `outputAlias` |
| `DERIVE` | 派生特征 | 1 个 | 增加字段的表 | `outputField`, `dataType`, `expression` |
| `GATE` | 发布门禁 | 0 或 1 个 | 无数据输出 | `metricRef`, `comparator`, `threshold`, `effect` |
| `OUTPUT` | 终态输出 | 1 个 | 数据集版本/文件/隔离区 | `mode`, `format`, `name`, `partitionBy` |

### 7.2 效果枚举

```text
OBSERVE          只记录指标
WARN             告警但继续
FAIL_RUN         运行失败
BLOCK_PUBLISH    阻止发布
QUARANTINE_ROWS  把命中行送入隔离区
DROP_ROWS        删除命中行
FLAG_ROWS        给命中行加标记字段
```

行级效果：

- `QUARANTINE_ROWS`
- `DROP_ROWS`
- `FLAG_ROWS`

这些效果只能用于 `ROW` 作用域，并必须提供输出别名。

### 7.3 条件操作符

当前设计支持：

- 空值/空字符串/空白：`IS_NULL`, `IS_EMPTY`, `IS_BLANK`
- 比较：`EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `BETWEEN`
- 集合：`IN`, `NOT_IN`
- 正则：`REGEX_MATCH`
- 日期：`DATE_PARSEABLE`, `DATE_BEFORE`, `DATE_AFTER`, `DATE_ORDER_INVALID`
- 唯一性：`DUPLICATE`, `UNIQUE`, `DUPLICATE_RATE_GT`
- 关联：`FK_EXISTS`, `JOIN_MATCH_RATE_LT`, `JOIN_FANOUT_GT`
- 聚合：`MISSING_RATE_GT`, `ROW_COUNT_LT`, `CATEGORY_RATIO_LT`, `DISTINCT_COUNT_LT`
- 逻辑组合：`AND`, `OR`, `NOT`

### 7.4 清洗操作

```text
TRIM
LOWER
UPPER
CAST
REPLACE
REGEX_REPLACE
MAP_VALUES
FILL_VALUE
COALESCE
DATE_PARSE
CLIP_OUTLIER
MASK
SPLIT_COLUMN
MERGE_COLUMNS
```

### 7.5 数据分析流程中的规则清单

| 分析阶段 | 典型规则 | 当前设计中的承载方式 | v1 状态 |
| --- | --- | --- | --- |
| 结构检查 | 表存在、列存在、类型一致 | 数据集探测 + 字段目录 | 已具备 |
| 完整性 | 主键为空、必填为空、缺失率 | `CHECK` + 空值/缺失率谓词 | 已具备 |
| 唯一性 | 主键重复、组合键重复 | `CHECK / DEDUPE` | 已具备 |
| 合法性 | 枚举值、正则、日期可解析 | `CHECK + PREDICATE` | 已具备 |
| 一致性 | 开始时间晚于结束时间 | `CHECK + DATE_ORDER_INVALID` | 已具备 |
| 外键完整性 | 子表键在父表不存在 | `JOIN + match_rate` 或 `CHECK + FK_EXISTS` | 已具备 |
| 多表关联 | 订单-客户、订单-支付 | `JOIN` | 已具备 |
| 关联放大 | 1:N 导致行数膨胀 | `JOIN guards.maxFanout` | 已具备 |
| 清洗 | 去空格、大小写、类型转换、映射 | `TRANSFORM` | 已具备 |
| 异常值 | IQR/Z-score 截断或置空 | `TRANSFORM + CLIP_OUTLIER` | 已具备 |
| 行级过滤 | 只保留已交付订单 | `FILTER` | 已具备 |
| 字段选择 | 去无关字段、控制宽表 | `PROJECT` | 已具备 |
| 去重 | 保留最新/最早记录 | `DEDUPE` | 已具备 |
| 聚合 | 每单金额、每用户订单数 | `AGGREGATE` | 已具备 |
| 特征派生 | 延误天数、比率、分桶 | `DERIVE` | 已具备 |
| 质量门禁 | 匹配率、缺失率、行数阈值 | `GATE` | 已具备 |
| 输出 | 训练集、分析集、隔离集 | `OUTPUT` | 已具备 |
| 抽样 | 随机/分层抽样 | `SAMPLE` | 预留 |
| 数据集拆分 | train/validation/test | `SPLIT` | 预留 |
| 纵向合并 | UNION | `UNION` | 预留 |
| 行列透视 | PIVOT/UNPIVOT | `PIVOT` | 预留 |
| 自定义节点 | 受控 Python/SQL | `CUSTOM` | 预留，不开放给普通用户 |

说明：表中的“已具备”指对应节点或配置已能被编译器识别、生成计划和校验，不代表执行 Worker 已经真实运行。运行控制面已可从 `QUEUED` 进入 `RUNNING` 并上报节点，但尚未接入真实执行 Worker。

这张表说明：规则库不应该只做“空值清洗”，它应该覆盖从结构检查、完整性、合法性、关联、聚合、特征到质量门禁的完整数据分析链路。
---

## 8. 可执行示例

### 8.1 示例一：字段为空则隔离整行

需求：

> `order_id` 不能为空。为空说明这条数据是脏数据，不能参与训练。

配置：

```text
Sheet: field_checks
kind: CHECK
input: orders 表
field: orders.order_id
condition: IS_NULL(order_id)
effect: QUARANTINE_ROWS
outputAlias: orders_checked
```

编译结果：

```text
节点: field_checks:check
类型: CHECK
输入: TABLE(orders)
条件: IS_NULL(order_id)
效果: QUARANTINE_ROWS
输出: orders_checked
```

说明：

- 使用 `QUARANTINE_ROWS` 比直接删除更利于追溯。
- 如果业务明确不需要追溯，也可以使用 `DROP_ROWS`。
- 如果只是标记异常，不删除数据，使用 `FLAG_ROWS` 并指定标记字段。

### 8.2 示例二：订单表通过 customer_id 关联客户表

需求：

> A 数据集的订单表 1 有一个 `customer_id` 字段，客户表 2 的主键也是 `customer_id`，用这个字段把两张表关联起来。

配置：

```text
Sheet: relations
kind: JOIN
leftInput: orders 表
leftField: orders.customer_id
rightInput: customers 表
rightField: customers.customer_id
joinType: LEFT
cardinality: N:1
guards:
  minMatchRate: 0.95
  maxFanout: 1
outputAlias: orders_with_customers
```

编译结果：

```text
左别名: orders
右别名: customers
左键: orders.customer_id
右键: customers.customer_id
关联类型: LEFT
基数: N:1
输出: orders_with_customers
```

关键校验：

- 左右输入必须存在。
- 左右键数量必须一致。
- 字段必须属于各自的输入上下文。
- 必须声明基数，避免意外的 1:N 放大。
- 可以设置匹配率门禁和 fanout 保护。

### 8.3 示例三：完整 8 节点链路

需求：

> 清洗订单数据，保留已交付订单，按订单去重，保留关联所需字段，关联客户表，按客户聚合订单数，质量门禁通过后输出 Parquet。

链路：

```text
CHECK 空值隔离
  -> FILTER order_status = delivered
  -> DEDUPE order_id keep LAST
  -> PROJECT order_id, customer_id
  -> JOIN customers
  -> AGGREGATE group by customer_id
  -> GATE row_count >= 1
  -> OUTPUT Parquet
```

实际回归结果：

```text
valid = true
nodeCount = 8
edges = 7
JOIN leftAlias = orders_projected
JOIN rightAlias = customers
发布版本 = v1
创建后状态 = QUEUED
控制面回归 = RUNNING -> SUCCEEDED
runMode = PREVIEW
sampleSize = 100
seed = 42
```

依赖边：

```text
field_checks:check   -> filters:filter
filters:filter       -> dedupes:dedupe
dedupes:dedupe       -> projects:project
projects:project     -> relations:join
relations:join       -> aggregates:aggregate
aggregates:aggregate -> gates:gate
relations:join       -> outputs:output
```

GATE 不需要手工声明输入时，编译器会根据 `metricRef = orders_by_customer.row_count` 自动解析上游节点并补依赖边。

---

## 9. 编译与校验

### 9.1 编译流程

```text
Draft Document
  -> 文档结构校验
  -> 工作表/列/行校验
  -> 引用解析
  -> 字段与表上下文校验
  -> 节点配置校验
  -> 效果与作用域校验
  -> 输出别名唯一性校验
  -> 依赖边生成
  -> 环检测
  -> 生成 RuleNode 图
  -> 生成 PipelinePlan
```

### 9.2 必须校验的不变量

- 文档 `schemaVersion` 正确。
- 工作簿绑定数据集与文档数据集一致。
- 工作表 key 唯一。
- 行 ID 在工作表内唯一。
- 自定义列只能使用允许的管理类型。
- 节点类型合法。
- 单输入节点必须且只有一个输入。
- JOIN 必须且只有两个输入。
- 输入别名非空且唯一。
- 表必须属于当前数据集版本。
- 字段必须存在且属于当前输入上下文。
- PROJECT 的 `select` / `drop` 字段必须可解析。
- 行级效果只能用于 `ROW` 作用域。
- `FLAG_ROWS` 必须指定标记字段。
- 输出别名必须符合命名规则且工作簿内唯一。
- 依赖节点必须存在。
- OUTPUT 是终态，不能被其他节点依赖。
- 依赖图不能有环。

### 9.3 典型错误码

```text
DOCUMENT_INVALID
DATASET_MISMATCH
SHEET_KEY_DUPLICATE
ROW_ID_DUPLICATE
COLUMN_CELL_TYPE_INVALID
CUSTOM_COLUMN_TYPE_INVALID
NODE_ID_DUPLICATE
INPUT_REQUIRED
INPUT_COUNT_INVALID
JOIN_INPUT_COUNT_INVALID
INPUT_ALIAS_INVALID
INPUT_ALIAS_DUPLICATE
TABLE_NOT_FOUND
FIELD_NOT_FOUND
PREDICATE_FIELD_INVALID
DEDUPE_KEY_INVALID
PROJECT_SELECT_INVALID
PROJECT_DROP_INVALID
JOIN_LEFT_ALIAS_INVALID
JOIN_RIGHT_ALIAS_INVALID
JOIN_KEYS_REQUIRED
JOIN_KEYS_LENGTH_MISMATCH
GATE_METRIC_NOT_FOUND
OUTPUT_HAS_DEPENDENTS
DEPENDENCY_CYCLE
```

错误模型：

```json
{
  "code": "PROJECT_SELECT_INVALID",
  "severity": "ERROR",
  "sheetKey": "projects",
  "rowId": "project",
  "columnKey": null,
  "message": "字段不存在或尚未从字段库导入",
  "fixSuggestion": "重新选择字段"
}
```

### 9.4 PipelinePlan 的定位

`PipelinePlan` 是执行层唯一输入。它不是 UI 文档，也不是单元格 JSON。

计划中包含：

- `workbookId`
- `datasetVersionId`
- `compiler` 版本
- `nodes`
- `edges`
- 每个节点的 `kind`、`stage`、`inputs`、`config`、`dependsOn`、`policy`、`output`

### 9.5 为什么不能直接执行单元格

- 单元格是用户交互数据，可能包含空值、显示名、隐藏列、自定义备注。
- 行列布局会变。
- 同一个字段可能由名称选择，也可能由 ID 选择。
- 执行需要归一化的输入别名、字段 ID、列 ID、效果和依赖。
- 发布版本必须可复现，不能依赖前端当前状态。

---

## 10. 生命周期、版本与运行

### 10.1 生命周期

```text
DRAFT -> VALIDATED -> PUBLISHED -> DEPRECATED
```

- `DRAFT`：当前编辑态，允许修改。
- `VALIDATED`：结构和参数通过校验，但尚未发布。
- `PUBLISHED`：不可变快照，可以运行。
- `DEPRECATED`：不再新建运行，但历史运行仍可追溯。

发布时保存：

- 文档快照。
- 编译计划。
- 内容哈希。
- 数据集版本。
- 版本号。

### 10.2 运行冻结

一次运行必须冻结：

- `workbookVersionId`
- `datasetVersionId`
- 运行模式 `PREVIEW / FULL`
- 预览采样参数 `sampleSize`
- 随机种子 `seed`

运行请求示例：

```json
{
  "workbookVersionId": "...",
  "mode": "PREVIEW",
  "sampleSize": 100,
  "seed": 42
}
```

工作簿已经绑定数据集版本，所以运行请求不重复传数据集版本。

### 10.3 运行控制面（已实现）

当前 `dataset-service` 已实现运行控制面：

- 创建运行记录，冻结工作簿版本、数据集版本、运行模式、采样参数和种子。
- `GET /rule-runs/{runId}/execution` 返回冻结的 `compiledPlan` 和 `workbookVersionContentHash`。
- `POST /rule-runs/{runId}/start` 执行 `QUEUED -> RUNNING`，重复启动幂等。
- `PUT /rule-runs/{runId}/nodes/{nodeId}` 上报节点状态、指标、样例行 URI 和错误。
- 节点必须存在于冻结计划中，节点类型必须一致，进入终态后不可改写。
- 运行结束为 `SUCCEEDED` 前，冻结计划中的全部启用节点必须是 `SUCCEEDED` 或 `SKIPPED`。
- `POST /rule-runs/{runId}/finish` 结束为 `SUCCEEDED / FAILED / CANCELLED`，重复结束幂等。
- 前端只读展示运行历史与节点进度，普通用户界面不暴露 `start / finish` 特权操作。

Python Worker 已实现控制面骨架：再次校验冻结计划、数据集版本一致性、输入/输出引用、边列表、环和禁用节点依赖，按稳定拓扑序上报 `RUNNING` 与终态；节点异常、上报失败或成功结束被拒绝时会转 `FAILED`，不会伪造成功。详见 `docs/rule-workbook/worker-execution.md`。

Temporal 尚未接入，ClickHouse 执行后端也尚未落地；execution snapshot 已由数据集服务提供，并由 Worker 的 `HttpExecutionPreflight` 校验。真实执行入口仍会在 `start` 前 fail-closed，因此服务端不会自动把运行标记为成功。

---

## 11. 前端交互设计

页面建议分成三块或三个层次：

```text
左侧：规则库列表 / 新建规则库
中间：Sheet 标签 + Excel 式规则网格
右侧或抽屉：条件编辑器、参数编辑器、校验结果
```

### 11.1 规则库列表

- 选择数据集。
- 新建规则库并命名。
- 搜索规则库。
- 删除规则库。
- 展示状态：草稿、已校验、已发布、已废弃。
- 展示最新版本和最新运行。

### 11.2 规则工作表

- 顶部是 Sheet 标签，例如字段检查、清洗转换、多表关联。
- 表格第一列用于行状态/启用开关。
- 单元格根据 `cellType` 渲染不同控件：
  - 下拉选择表。
  - 下拉选择字段。
  - 多选字段列表。
  - 条件编辑器。
  - 参数 JSON 编辑器。
  - 枚举、布尔、数字、文本。
- 支持新增行、复制行、删除行。
- 支持新增自定义列、隐藏列、改名、删除自定义列。
- 输入支持选择原始表或上游节点输出。

### 11.3 条件编辑器

简单条件用表单：

- 操作符。
- 主字段。
- 第二字段。
- 字面量。
- 阈值。

`AND / OR / NOT` 当前支持编译；前端复杂条件主要走条件 JSON 编辑。后续可以补：

- 条件组树。
- 拖拽组合。
- 条件预览。
- 条件模板。

### 11.4 操作栏

- 保存草稿。
- 校验。
- 发布。
- 预览运行。
- 查看版本。
- 查看运行记录。
- 查看校验错误。

### 11.5 错误反馈

- 表格单元格标红。
- 错误列表展示 `sheetKey / rowId / columnKey / code / message / fixSuggestion`。
- 点击错误定位到对应行和列。
- 有未保存修改时切换规则库要二次确认。
---

## 12. API 设计

### 12.1 字段库

```text
GET    /api/v1/datasets/{id}/columns
GET    /api/v1/fields/catalog?datasetVersionId={id}
POST   /api/v1/fields/import
GET    /api/v1/fields
GET    /api/v1/fields/{id}
PUT    /api/v1/fields/{id}
```

字段导入请求：

```json
{
  "datasetVersionId": "uuid",
  "tableIds": ["可选"],
  "columnIds": ["可选"]
}
```

### 12.2 规则工作簿

```text
GET    /api/v1/rule-workbooks
POST   /api/v1/rule-workbooks
GET    /api/v1/rule-workbooks/{id}
DELETE /api/v1/rule-workbooks/{id}
PUT    /api/v1/rule-workbooks/{id}/draft
POST   /api/v1/rule-workbooks/{id}/validate
POST   /api/v1/rule-workbooks/{id}/publish
GET    /api/v1/rule-workbooks/{id}/versions
GET    /api/v1/rule-workbooks/{id}/versions/{versionNumber}
POST   /api/v1/rule-workbooks/{id}/runs
GET    /api/v1/rule-workbooks/{id}/runs
GET    /api/v1/rule-runs/{runId}
GET    /api/v1/rule-runs/{runId}/execution
POST   /api/v1/rule-runs/{runId}/start
POST   /api/v1/rule-runs/{runId}/finish
PUT    /api/v1/rule-runs/{runId}/nodes/{nodeId}
GET    /api/v1/rule-runs/{runId}/nodes
```

### 12.3 创建规则工作簿

```json
{
  "name": "订单训练数据清洗",
  "description": "订单数据质量检查、关联和特征输出",
  "datasetVersionId": "uuid",
  "document": {
    "schemaVersion": 1,
    "datasetVersionId": "uuid",
    "sheets": []
  }
}
```

如果不传 `document`，后端会生成默认 10 张工作表。

### 12.4 保存草稿

```json
{
  "document": {
    "schemaVersion": 1,
    "datasetVersionId": "uuid",
    "sheets": []
  },
  "expectedRevision": 3
}
```

`expectedRevision` 用于乐观锁，防止两个编辑者互相覆盖。

---

## 13. 存储模型

### 13.1 字段相关

| 表 | 作用 |
| --- | --- |
| `dataset_columns` | 数据集版本中的物理列 |
| `field_definitions` | 逻辑字段定义 |
| `dataset_field_bindings` | 逻辑字段和物理列的绑定 |

### 13.2 规则相关

| 表 | 作用 |
| --- | --- |
| `rule_workbooks` | 工作簿元数据、所有者、绑定数据集、状态 |
| `rule_workbook_drafts` | 当前草稿 JSON、修订号 |
| `rule_workbook_versions` | 发布快照、编译计划、内容哈希 |
| `rule_runs` | 运行实例、模式、状态、采样参数 |
| `rule_run_nodes` | 每个节点的运行状态、指标、样例地址 |

### 13.3 规划中的表

| 表 | 作用 |
| --- | --- |
| `table_relationships` | 可复用的逻辑表关系、关联键、基数 |
| `quality_reports` | 汇总质量报告和文件地址 |

当前 JOIN 直接在工作表行中配置，还没有独立的表关系库。

---

## 14. 当前实现状态

### 14.1 已实现

- 数据集 CSV/TSV/XLSX 列级探测和类型推断。
- 字段目录查询。
- 字段导入和幂等去重。
- 字段库搜索和编辑。
- 规则工作簿创建、列表、读取、删除。
- 草稿保存和乐观锁修订号。
- 校验、发布、版本查询。
- 预览运行记录创建。
- 运行控制面：读取冻结计划、启动运行、节点状态上报、结束运行、终态防改写和幂等处理。
- 前端运行历史、运行详情、计划节点进度、节点指标/样例/错误查看和运行中自动轮询。
- 10 类节点的编译和校验。
- 输入可以为原始表或上游节点输出。
- GATE 根据 `metricRef` 自动建立上游依赖。
- 10 张默认工作表。
- Excel 风格前端网格。
- 字段级联、条件编辑、参数 JSON 编辑。
- 自定义管理列新增、隐藏、改名、删除。
- 前端规则库和字段库导航接入。

### 14.2 已验证

- 后端主代码编译通过。
- 集成测试源码编译通过。
- 前端生产构建通过。
- 字段导入第一次 `created=5, skipped=0`。
- 字段导入第二次 `created=0, skipped=5`。
- 8 节点完整链路：`valid=true`、8 节点、7 条边。
- JOIN 左别名正确为 `orders_projected`。
- FILTER 和 DEDUPE 的字段能自动解析到物理列。
- 发布 v1 成功。
- 创建 `QUEUED` 预览运行成功，`sampleSize=100, seed=42`。
- 运行生命周期集成测试通过：`QUEUED -> RUNNING -> SUCCEEDED`，节点上报与幂等/冲突路径均覆盖。
- Python Worker 38 个测试通过：计划/版本/引用/边/环校验、稳定拓扑序、成功编排、节点失败、上报失败、成功结束被拒、快照缺失/错运行/错版本/非 READY/重复 ID/不安全物理名、HTTP snapshot 预检、后端缺失 fail-closed、非法计划、非 QUEUED 和禁用节点。
- 运行历史与节点进度界面编译通过。
- 无效 PROJECT 字段返回 `PROJECT_SELECT_INVALID`。
- FILTER 传入两个输入返回 `INPUT_COUNT_INVALID`。

---

## 15. 已知边界与后续优先级

### 15.1 必须优先解决

1. 完成 ClickHouse 执行后端，并接入 Temporal activity 消费冻结计划；execution snapshot 已接通。
2. 补节点输出 schema 传播，避免 PROJECT 删掉的字段在后续仍被引用。
3. 建独立表关系库，让 JOIN 可以复用关系定义。

### 15.2 应该尽快解决

4. 跨数据集版本的逻辑字段复用。
5. GATE 的 `metricRef` 改成上游指标下拉。
6. AND / OR / NOT 的可视化条件组。
7. 发布前校验指标确实由上游节点产生。

### 15.3 后续增强

8. `SAMPLE`、`SPLIT`、`UNION`、`PIVOT / UNPIVOT`。
9. DAG 可视化和节点影响范围。
10. 质量报告、隔离区、血缘。
11. 定时监控和运行历史趋势。
12. 受控自定义节点。

### 15.4 当前不能承诺的能力

- 不能声称运行已经真实执行。
- 不能声称已支持跨数据集版本规则复用。
- 不能声称规则库可以自由写 SQL。
- 不能声称任意单元格都可以定义执行语义。
- 不能把前端行列布局当作执行计划。

---

## 16. 关键代码位置

### 后端

```text
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/rule/RuleWorkbookCompiler.java
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/rule/RuleWorkbookDocumentFactory.java
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/service/impl/RuleWorkbookServiceImpl.java
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/service/impl/FieldLibraryServiceImpl.java
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/controller/RuleWorkbookController.java
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/controller/RuleRunController.java
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/controller/FieldLibraryController.java
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/resources/db/migration/V8__add_dataset_columns_and_field_bindings.sql
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/resources/db/migration/V9__create_rule_workbooks.sql
ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/test/java/com/olist/datasets/RuleWorkbookIntegrationTest.java
```

### 前端

```text
ML-Based-Order-Delay-Prediction-System-for-Olist-frontend/src/api/ruleLibrary.js
ML-Based-Order-Delay-Prediction-System-for-Olist-frontend/src/views/FieldLibraryView.vue
ML-Based-Order-Delay-Prediction-System-for-Olist-frontend/src/views/RuleLibraryView.vue
ML-Based-Order-Delay-Prediction-System-for-Olist-frontend/src/config/navigation.js
```

### 文档

```text
docs/rule-workbook/README.md
docs/rule-workbook/nodes.md
docs/rule-workbook/cells.md
docs/rule-workbook/schemas.md
docs/rule-workbook/olist-order-cleaning.example.yaml
```

---

## 17. 给另一个 AI 的交接提示词

可以把下面这段直接复制给另一个 AI：

```text
你正在接手 Olist 数据平台的“字段库 + 规则库”模块。

请先阅读项目根目录的 RULE_LIBRARY_DESIGN.md，再继续设计或实现。

已经确定的核心约束：
1. 一个规则工作簿绑定一个 datasetVersionId，不允许每行隐式选择不同数据集。
2. 字段引用必须使用稳定 ID，保存 fieldId、columnId、tableId、nameSnapshot。
3. Excel 式交互不等于任意列都能定义执行语义。
4. 系统列负责执行语义，自定义列只承载管理信息。
5. 规则行编译成 RuleNode，执行以 PipelinePlan 为准，不以 UI 行列布局为准。
6. 发布版本不可变，运行冻结工作簿版本和数据集版本。
7. 组合条件用 Predicate AST；多个处理步骤用行和 NODE_OUTPUT 串链路。
8. 不开放任意 SQL / Python / JS。
9. 运行控制面与 Python Worker 校验/编排已实现；execution snapshot 已接通，ClickHouse 后端和 Temporal 尚未接入，不要伪造执行结果。
10. 当前尚未实现节点输出 schema 传播、独立表关系库、跨数据集版本字段复用。

请特别关注：
- 如何补节点输出 schema 传播。
- 如何设计独立表关系库并兼容现有 JOIN。
- 如何把 GATE.metricRef 改成上游指标下拉。
- 如何让 AND / OR / NOT 有可视化条件组。
- 如何接入执行 Worker 并保证结果可复现。
```

---

## 18. 术语表

| 术语 | 含义 |
| --- | --- |
| 数据集版本 | 一次上传得到的不可变数据快照 |
| 物理列 | 数据集版本中真实存在的列 |
| 逻辑字段 | 用户维护的业务字段定义 |
| 字段绑定 | 逻辑字段到物理列的映射 |
| 规则工作簿 | 一个命名的规则库 |
| Sheet | 一类处理阶段 |
| RuleNode | 编译后的规范执行节点 |
| PipelinePlan | 编译后的执行计划 |
| Predicate | 结构化条件树 |
| Effect | 命中规则后的结果 |
| Policy | 阻断和错误处理策略 |
| GATE | 质量门禁节点 |
| OUTPUT | 运行终态输出节点 |
| DatasetContract | 未来用于跨版本复用的数据契约，当前未实现 |

---

## 19. 最终总结

这套设计的核心不是“把 Excel 做得更像 Excel”，而是：

> 让用户用 Excel 式交互编排规则，同时让系统始终拥有一套明确、可校验、可版本化、可执行的节点语义。

字段库提供稳定的数据上下文，规则库提供可编排的处理逻辑，编译器负责把两者连接起来。只要坚持“编辑层、语义层、执行层分离”，后续增加更多规则类型、DAG 可视化、质量报告和执行 Worker 都不会破坏主架构。
