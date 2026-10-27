# 单元格契约

工作表的自由来自“用户可配置列”，但执行的安全来自“每个格子有明确类型”。列可以改名、重排、隐藏，但不能把一个纯文本格子当成字段引用。

## 1. 工作簿文档结构

```json
{
  "workbookId": "uuid",
  "name": "订单训练数据清洗",
  "datasetVersionId": "uuid",
  "sheets": [
    {
      "key": "field_checks",
      "title": "字段检查",
      "kind": "FIELD_RULES",
      "columns": [],
      "rows": []
    }
  ]
}
```

### 1.1 Sheet

```json
{
  "key": "field_checks",
  "title": "字段检查",
  "kind": "FIELD_RULES",
  "order": 10,
  "columns": [],
  "rows": []
}
```

`kind` 决定默认列和允许的节点类型。第一版建议的 Sheet 类型：

```text
FIELD_RULES       字段检查
TRANSFORMS        清洗转换
FILTERS           行筛选
PROJECTS          字段投影
DEDUPES           去重
RELATIONS         多表关联
AGGREGATES        聚合
DERIVED           特征派生
GATES             质量门禁
OUTPUTS           输出
```

### 1.2 Column

```json
{
  "key": "field",
  "title": "字段",
  "cellType": "FIELD_REF",
  "required": true,
  "system": true,
  "hidden": false,
  "width": 220,
  "defaultValue": null,
  "dependsOn": [
    "table"
  ],
  "config": {}
}
```

列字段说明：

| 字段 | 说明 |
| --- | --- |
| `key` | 稳定列键，发布后不可变 |
| `title` | 显示名称，可修改 |
| `cellType` | 单元格语义类型 |
| `required` | 是否必填 |
| `system` | 是否系统列，系统列不可删除或改类型 |
| `hidden` | 是否隐藏 |
| `width` | 显示宽度 |
| `defaultValue` | 默认值 |
| `dependsOn` | 依赖的其他列键 |
| `config` | 单元格类型相关配置 |

### 1.3 Row

```json
{
  "id": "row_01",
  "enabled": true,
  "cells": {
    "table": {
      "type": "TABLE",
      "tableId": "uuid",
      "alias": "orders"
    },
    "field": {
      "type": "FIELD",
      "fieldId": "uuid",
      "nameSnapshot": "order_purchase_timestamp"
    },
    "condition": {
      "op": "IS_NULL"
    },
    "effect": "QUARANTINE_ROWS"
  }
}
```

行不是“一条 SQL”，而是一个待编译的节点草稿。编译后生成 `nodeId` 和规范节点。

## 2. 单元格类型

### 2.1 引用类

| cellType | 值形态 | 数据来源 | 依赖 |
| --- | --- | --- | --- |
| `DATASET_REF` | `{type, datasetVersionId}` | 数据集/字段库 | 无 |
| `INPUT_REF` | 表引用或节点输出引用 | 当前数据集表 / 上游输出 | 数据集 / 节点图 |
| `TABLE_REF` | `{type, tableId, alias}` | 当前数据集的表 | 数据集 |
| `FIELD_REF` | `{type, fieldId, nameSnapshot}` | 当前表的字段 | 表 |
| `FIELD_LIST_REF` | `[{type, fieldId}]` | 当前表的字段 | 表 |
| `NODE_OUTPUT_REF` | `{type, nodeId, outputAlias}` | 上游节点输出 | 节点图 |
| `RELATION_REF` | `{type, relationshipId}` | 表关系库 | 左右表 |

`FIELD_REF` 同时携带两个标识，避免“逻辑字段”和“物理列”混淆：

- `fieldId`：字段库中的稳定逻辑字段 ID，跨数据集版本保持业务语义。
- `columnId`：当前数据集版本中的物理列 ID，执行引擎最终读取该列。
- `nameSnapshot`：选择时的显示名快照，只用于提示和迁移，不作为执行依据。

编译器会优先解析 `columnId`；只有 `fieldId` 时，会通过字段绑定表解析到当前数据集版本的物理列。两者同时存在时必须指向同一列。

当节点输入是上游 `NODE_OUTPUT` 时，输入会同时保存 `alias`。字段引用可以只带 `fieldId`，编译器会先解析出物理列，再用该列的 `tableId` 校验是否属于当前输入上下文。这样规则行不需要重复填写表的显示名，也能避免字段跨表误用。

### 2.2 语义类

| cellType | 值形态 | 数据来源 | 说明 |
| --- | --- | --- | --- |
| `NODE_KIND` | 字符串枚举 | 节点注册表 | CHECK、JOIN、AGGREGATE 等 |
| `PREDICATE` | 条件 AST | 条件注册表 | 为空、重复、范围等 |
| `EFFECT` | 效果枚举 | 效果注册表 | 隔离、删除、告警、阻断 |
| `OPERATION` | 操作枚举 | 操作注册表 | TRIM、CAST、FILL 等 |
| `PARAM_OBJECT` | JSON 对象 | 节点参数 schema | 复杂参数抽屉 |
| `JOIN_TYPE` | 枚举 | 关联注册表 | INNER、LEFT、RIGHT、FULL |
| `CARDINALITY` | 枚举 | 关联注册表 | 1:1、1:N、N:1、N:N |

### 2.3 值类

| cellType | 值形态 | 说明 |
| --- | --- | --- |
| `VALUE` | `{dataType, value}` | 常量、阈值、字符串 |
| `VALUE_LIST` | 数组 | 枚举、类目列表 |
| `DATE_VALUE` | ISO 日期字符串 | 日期常量 |
| `NUMBER` | 数字 | 阈值、比例、数量 |
| `BOOLEAN` | 布尔 | 开关 |
| `ENUM` | 字符串 | 固定选项 |

### 2.4 布局与元数据类

| cellType | 说明 |
| --- | --- |
| `TEXT` | 备注、负责人、业务说明 |
| `TAGS` | 标签 |
| `GROUP` | 规则组名称 |
| `ORDER` | 组内顺序 |
| `OUTPUT_ALIAS` | 节点输出别名 |

## 3. 列定义规则

### 3.1 系统列

以下列属于执行语义，必须由系统定义：

- 节点类型
- 表
- 字段
- 条件
- 效果
- 关联类型
- 基数
- 输出别名
- 阻断策略

系统列可以隐藏、调宽、改名，但不能删除或改成无关类型。

### 3.2 自定义列

用户可以新增自定义列，但第一版只允许：

```text
TEXT
NUMBER
BOOLEAN
ENUM
DATE_VALUE
TAGS
```

自定义列不能参与执行。它的作用是承载负责人、业务说明、优先级、部门等管理信息。

自定义列键必须以 `custom_` 开头，避免和系统列冲突。

### 3.3 修改列

- 修改显示名称：安全。
- 修改宽度、顺序、隐藏：安全。
- 修改是否必填：草稿可用，发布前重新校验。
- 修改 `cellType`：只有在草稿中允许，并触发迁移检查。
- 修改 `key`：等同于新建列，必须迁移引用。
- 已发布版本的列定义不可变。

## 4. 级联和清空规则

选择器之间必须级联：

```text
数据集 -> 表 -> 字段
表 -> 字段列表
左右表 -> 关系
节点类型 -> 参数 schema
```

上游变化时，下游处理分三种：

| 场景 | 处理 |
| --- | --- |
| 下游值在新上下文中完全不存在 | 自动清空 |
| 下游值可按 ID 或名称重新映射 | 保留并标记待确认 |
| 下游值可能存在多个候选 | 标记冲突，要求人工选择 |

例如：

- 数据集改变，表选择器必须重新选择。
- 表改变，字段选择器如果不存在同名字段则清空。
- 节点类型从 CHECK 改为 JOIN，原来的效果参数清空。
- 关联左表改变，原有关系引用失效，标记为待修复。

### 4.1 组合规则

组合规则分为两种，不能混为一谈：

- 条件组合：同一行内用 `AND` / `OR` / `NOT` 组合 `Predicate`，表达“多个条件同时满足才命中”。这属于一个节点，不新增执行阶段。
- 步骤组合：多行分别编译成节点，通过 `outputAlias` 和 `NODE_OUTPUT` 串成链路，例如“先筛选、再去重、再投影、再关联”。这属于多个节点，按依赖图执行。

因此，用户新增一行表示新增一个处理步骤；要在一条规则里表达多个判断条件，应编辑该行的条件 AST。行号只用于展示，不能代替依赖关系。

## 5. 默认工作表列

### 5.1 字段检查

```text
启用 | 分组 | 表 | 字段 | 条件 | 效果 | 输出别名 | 参数 | 阻断 | 备注
```

### 5.2 清洗转换

```text
启用 | 分组 | 表 | 字段 | 操作 | 参数 | 条件 | 输出字段 | 备注
```

### 5.3 行筛选

```text
启用 | 分组 | 输入 | 保留条件 | 输出别名 | 备注
```

### 5.4 字段投影

```text
启用 | 分组 | 输入 | 保留字段 | 移除字段 | 输出别名 | 备注
```

### 5.5 去重

```text
启用 | 分组 | 输入 | 去重键 | 保留策略 | 排序依据 | 输出别名 | 备注
```

### 5.6 多表关联

```text
启用 | 分组 | 左输入 | 左字段 | 右输入 | 右字段 | 关联类型 | 基数 | 重复策略 | 输出别名
```

### 5.7 聚合

```text
启用 | 分组 | 输入表 | 分组字段 | 指标 | 输出别名 | 备注
```

### 5.8 特征派生

```text
启用 | 分组 | 输入表 | 新字段 | 类型 | 表达式 | 备注
```

### 5.9 质量门禁

```text
启用 | 分组 | 指标 | 比较符 | 阈值 | 效果 | 备注
```

### 5.10 输出

```text
启用 | 分组 | 输入 | 输出方式 | 格式 | 名称 | 分区字段
```

## 6. 校验层级

### 6.1 单元格级

- 必填。
- 类型正确。
- 引用对象存在。
- 数值范围合法。
- 枚举值合法。

### 6.2 行级

- 条件和效果兼容。
- 关联左右键数量一致。
- 复合键字段来自同一作用域。
- 输出别名非空且符合命名规则。

### 6.3 工作表级

- 列键唯一。
- 系统列完整。
- 行 ID 唯一。
- 行依赖可解析。
- 组内顺序可重复但必须能稳定排序。

### 6.4 工作簿级

- 所有节点引用可解析。
- 节点输出别名唯一。
- 节点依赖无环。
- 同一运行只使用一个数据集版本。
- 门禁能读取指标。
- 输出节点是终态。

## 7. 错误模型

校验结果统一返回：

```json
{
  "code": "FIELD_NOT_FOUND",
  "severity": "ERROR",
  "sheetKey": "field_checks",
  "rowId": "row_01",
  "columnKey": "field",
  "message": "字段已不存在，请重新选择",
  "fixSuggestion": "从当前表字段中重新选择"
}
```

错误只用于编辑和发布校验。运行期的数据质量失败属于另一套结果模型，不能混在一起。

## 8. 复杂参数

不同节点参数差异很大，建议：

- 简单参数用单元格直接编辑。
- 复杂参数用 `PARAM_OBJECT` 单元格显示摘要。
- 点击摘要打开右侧参数抽屉。
- 抽屉表单由后端参数 schema 动态渲染。

例如：

- CHECK 的“缺失率阈值”可以直接放在单元格。
- JOIN 的左右键、基数和 fanout 保护放在抽屉。
- AGGREGATE 的多个指标放在抽屉。
- DERIVE 的表达式放在抽屉。

## 9. 用户交互约束

- 支持行复制、多选、批量设置效果、批量分组。
- 支持拖拽排序，但排序只影响展示和默认拓扑，不覆盖显式依赖。
- 不支持 Excel 公式。
- 单元格粘贴时必须按列类型解析，解析失败拒绝粘贴。
- 删除被依赖的节点或字段时，先显示影响范围。
- 发布前必须通过校验。
- 已发布版本不可直接编辑，只能复制新草稿。

## 10. 示例

```json
{
  "key": "field_checks",
  "title": "字段检查",
  "kind": "FIELD_RULES",
  "columns": [
    {
      "key": "enabled",
      "title": "启用",
      "cellType": "BOOLEAN",
      "system": true
    },
    {
      "key": "group",
      "title": "分组",
      "cellType": "GROUP",
      "system": true
    },
    {
      "key": "table",
      "title": "表",
      "cellType": "TABLE_REF",
      "system": true,
      "required": true
    },
    {
      "key": "field",
      "title": "字段",
      "cellType": "FIELD_REF",
      "system": true,
      "required": true,
      "dependsOn": [
        "table"
      ]
    },
    {
      "key": "condition",
      "title": "条件",
      "cellType": "PREDICATE",
      "system": true,
      "required": true
    },
    {
      "key": "effect",
      "title": "效果",
      "cellType": "EFFECT",
      "system": true,
      "required": true
    },
    {
      "key": "blocking",
      "title": "阻断",
      "cellType": "ENUM",
      "system": true
    }
  ],
  "rows": [
    {
      "id": "row_01",
      "enabled": true,
      "cells": {
        "group": "基础检查",
        "table": {
          "type": "TABLE",
          "tableId": "uuid",
          "alias": "orders"
        },
        "field": {
          "type": "FIELD",
      "fieldId": "logical-field-uuid",
      "columnId": "physical-column-uuid",
          "nameSnapshot": "order_purchase_timestamp"
        },
        "condition": {
          "op": "IS_NULL"
        },
        "effect": "QUARANTINE_ROWS",
        "blocking": "NONE"
      }
    }
  ]
}
```
