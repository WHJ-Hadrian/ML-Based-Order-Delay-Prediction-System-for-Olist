# 数据集库 × 规则库 ClickHouse 对接回复

> 回复对象：`dataset-clickhouse-handoff.md`
>
> 日期：2026-10-26
>
> 说明：规则库的 `RuleWorkbook`、`V8/V9`、字段绑定和编译器目前还在本地工作区，尚未推送；下面按本地实现和对长期架构的约束回答，不假设它们已经合并到 `main`。

---

## 0. 结论摘要

四个 P0 的答复是：

| 问题 | 结论 |
| --- | --- |
| P0-1 执行怎么访问数据 | 规则 worker 直连 ClickHouse 只读查询源表；数据集服务只做元数据和控制面，不做全量数据代理 |
| P0-2 Catalog 够不够 | 基本方向对，但要把“逻辑 Catalog”和“执行快照”拆开，并补版本哈希、稳定表键、空值信息和物理名 |
| P0-3 输出回写 | v1 选方案 A：worker 产出 Parquet + manifest，数据集服务导入并登记为新版本 |
| P0-4 版本是否不可变 | 必须不可变。`datasetVersionId` 一旦 `READY`，文件、表、列、schema、行数都不能再改 |

一句话：

```text
ClickHouse 是数据面；
MySQL + 数据集服务是元数据面；
规则库保存 datasetVersionId、tableId、columnId 等稳定逻辑 ID；
执行 worker 在运行时通过 execution snapshot 解析物理表名和物理列名；
原始数据只读，规则输出走对象存储 + 数据集服务登记。
```

---

## 1. P0-1：执行时怎么访问数据

### 1.1 推荐结论

规则执行 worker **直连 ClickHouse**，使用只读账号查询 `olist_datasets` 中的源表。

数据集服务不承担全量数据代理，原因不是“直连更简单”，而是：

- 规则会做扫描、过滤、去重、关联、聚合，数据量可能远大于普通分页。
- 如果全部经过数据集服务，数据集服务会变成数据搬运和内存瓶颈。
- 规则执行需要查询计划和资源配额，放在 worker 层更容易控制。
- 数据集服务继续负责元数据、版本、状态、Catalog 和输出登记，职责更清楚。

但“直连”不等于“随便拼 SQL”：

- 只允许执行编译器产出的 `PipelinePlan`。
- 表名、列名只能来自 execution snapshot，不能来自用户输入。
- 值必须参数化，标识符必须由系统生成并安全引用。
- 不开放任意 SQL、Python、JS。

### 1.2 库名和账号

使用独立库：

```text
olist_datasets      源数据，规则 worker 只读
olist_rule_staging  可选，规则执行中间结果/临时表，worker 受限读写
olist_analytics     分析查询/下游集市，不承载原始数据集
```

不要复用 `olist_analytics` 存原始上传表。即使当前 compose 只初始化了 `olist_analytics`，也应该新增 `olist_datasets`，避免数据集生命周期和下游分析生命周期互相污染。

建议账号边界：

- `olist_dataset_writer`：数据集服务使用，拥有 `olist_datasets` 的建表、写入、删除权限。
- `olist_rule_reader`：规则 worker 使用，只拥有 `olist_datasets.*` 的 `SELECT`。
- `olist_rule_staging` 如果启用，再给 worker 一个只对 staging 库有 `CREATE TEMPORARY TABLE` / 写入权限的账号，**不要**给 `olist_datasets` 的 DDL/DML。

这里要修正 handoff 里“规则库只读 ClickHouse”的绝对表述：

- 对源数据集表必须是只读。
- 复杂 DAG 如果需要在节点之间物化中间结果，worker 可以写独立的 `olist_rule_staging`，但不能写 `olist_datasets`。
- v1 能只用 CTE/子查询完成简单链路时，可以先不开 staging；但不要把它设计成永远无法物化中间结果。

### 1.3 规则侧拿到什么 ID

规则模型和 `PipelinePlan` 中保留：

```text
datasetVersionId
tableId
columnId
fieldId
nameSnapshot
```

不要把这些物理名当成权威数据：

```text
physicalTable
physicalName
clickhouseType
```

执行前由 worker 调数据集服务的 execution snapshot，把逻辑 ID 解析成物理名。这样做的原因：

- ClickHouse 表可以从原始文件重建，物理名不应该成为规则语义的一部分。
- 后续如果表名规范化、列名清洗或存储迁移，规则不需要重编译。
- `nameSnapshot` 只用于 UI 展示和错误提示，不能用于解析字段。
- 缓存映射时使用 `datasetVersionId + schemaHash` 作为 key，并校验哈希。

### 1.4 执行访问的最终答复

```text
P0-1 执行访问方式：
规则 worker 直连 ClickHouse，使用只读账号查询 olist_datasets 中的源表。
数据集服务提供 catalog / execution-snapshot / preview，不做全量数据代理。
PipelinePlan 保存 datasetVersionId + tableId + columnId + fieldId 等逻辑 ID；
执行时通过 execution snapshot 解析 physicalTable / physicalName。
库名使用 olist_datasets；中间物化如需要，单独使用 olist_rule_staging。
不用 olist_analytics 承载原始数据集。
```

---

## 2. P0-2：Catalog 字段够不够

### 2.1 方向正确，但建议拆成两个接口

handoff 里的 `/datasets/{datasetId}/catalog` 适合 UI 和编译器，但如果把物理表名、物理列名、连接信息也塞进去，会把“逻辑目录”和“执行绑定”混在一起。

建议拆成：

```http
GET /api/v1/dataset-versions/{datasetVersionId}/catalog
GET /internal/dataset-versions/{datasetVersionId}/execution-snapshot
```

兼容现有路径时，可以保留：

```http
GET /api/v1/datasets/{datasetId}/catalog
```

但必须在契约里明确 `datasetId` 到底是“逻辑数据集”还是“数据集版本”。长期看，二者不能混用。

### 2.2 逻辑 Catalog 应返回什么

建议结构如下。字段不是越多越好，重点是稳定 ID、版本哈希、可校验的类型信息：

```json
{
  "datasetId": "logical-dataset-uuid",
  "datasetVersionId": "version-uuid",
  "versionNo": 3,
  "parentVersionId": "version-uuid-or-null",
  "status": "READY",
  "manifestHash": "sha256:...",
  "schemaHash": "sha256:...",
  "createdAt": "2026-10-26T08:00:00Z",
  "tables": [
    {
      "tableId": "version-specific-table-uuid",
      "tableKey": "orders",
      "tableName": "orders",
      "ordinal": 0,
      "rowCount": 99441,
      "columnCount": 8,
      "columns": [
        {
          "columnId": "version-specific-column-uuid",
          "name": "order_id",
          "ordinal": 0,
          "logicalType": "STRING",
          "nullable": false
        }
      ]
    }
  ]
}
```

字段说明：

- `manifestHash`：整个版本的内容清单哈希。**必须有**。
- `schemaHash`：只由表键、列名、逻辑类型、可空性、顺序等 schema 信息计算，**必须有**。
- `versionNo` / `parentVersionId`：用于版本链和审计，建议有。
- `tableKey`：跨版本稳定的逻辑表标识，例如 `orders` 或 `file/orders`。建议有。
- `tableId`：当前版本内的表 ID，稳定于当前版本即可。
- `columnId`：当前版本内的列 ID，稳定于当前版本即可。
- `logicalType`、`nullable`：编译器校验和类型检查需要。
- `status`：规则发布和运行前必须检查。
- `createdAt`：审计和缓存失效需要。

不需要放进 Catalog 的字段：

- `sampleRows`：应由独立 preview 接口返回，不要把样例行塞进目录快照。
- `physicalTable`、`physicalName`：放到 execution snapshot，避免业务层依赖存储细节。
- `clickhouseType`：严格说属于执行绑定；如果为了方便调试，可以在 execution snapshot 返回。
- `primaryKey`：不是规则编译的必要条件。去重键由用户在 `DEDUPE` 规则中选择；主键识别可以作为后续增强。
- `fieldId`：Catalog 是数据集列目录，字段库是规则侧的逻辑字段层。二者不要混在一起；如果提供合并接口，再明确返回 `fieldId`。

### 2.3 execution snapshot 应返回什么

```json
{
  "datasetVersionId": "version-uuid",
  "schemaHash": "sha256:...",
  "manifestHash": "sha256:...",
  "clickhouseDatabase": "olist_datasets",
  "tables": [
    {
      "tableId": "version-specific-table-uuid",
      "physicalTable": "ds_ab12cd34_ef56gh78",
      "columns": [
        {
          "columnId": "version-specific-column-uuid",
          "physicalName": "c000_order_id",
          "clickhouseType": "String",
          "nullable": false
        }
      ]
    }
  ]
}
```

不要返回 ClickHouse 主机、端口、账号或密码。worker 使用自己的环境配置和 secret。`clickhouseDatabase` 可以作为逻辑库名返回，但不能替代连接配置。

### 2.4 物理名不要直接使用原始表头

handoff 建议“ClickHouse 标识符统一加反引号，允许中文、空格和特殊字符”。技术上可行，但不建议把它作为主方案：

- SQL 生成、Parquet、导出、下游 connector 都容易被特殊字符和大小写碰撞影响。
- 原始表头可能重复、为空、超长、包含引号或控制字符。
- 规则编译器不应该依赖“物理名等于原始名”。

建议：

- 物理表名：`ds_{datasetVersionId去横线}_{tableId去横线}`，或至少包含版本 ID 和表 ID。
- 物理列名：`c{ordinal:03d}` 或 `c{ordinal:03d}_{safe_slug}`，由数据集服务确定性生成。
- 原始名称保留在 `sourceName` / `name` 中，用于展示和字段库。
- 重复表头：源名称加 `__2` 等后缀；物理名仍然按 ordinal 唯一。
- 空表头：源名称 `column_<ordinal>`。
- `physicalName` 一旦版本 `READY` 就不可变。

这样规则编译器只需要使用 `columnId`；执行绑定再映射到稳定的物理列。反引号仍然保留作为防御，但不再是解决命名问题的唯一手段。

### 2.5 对 P0-2 的最终答复

```text
P0-2 Catalog 是否够用 / 缺什么：
方向够，但需要拆成逻辑 Catalog 和 execution snapshot。
逻辑 Catalog 必带 datasetVersionId、status、manifestHash、schemaHash、
tables[].tableId/tableKey/tableName/ordinal/rowCount、
columns[].columnId/name/ordinal/logicalType/nullable。
physicalTable、physicalName、clickhouseType 放 execution snapshot。
不需要 sampleRows、primaryKey；sampleRows 走 preview，primaryKey 后续可选。
Catalog 不返回 ClickHouse 账号密码，只返回逻辑库名。
```

---

## 3. P0-3：输出回写选方案 A 还是 B

### 3.1 结论：v1 选 A

选择：

```text
规则 worker 执行 OUTPUT
  -> 写 Parquet 到对象存储
  -> 写 output manifest（schema、rowCount、contentHash、lineage）
  -> 调数据集服务登记接口
  -> 数据集服务校验并导入 ClickHouse
  -> 创建新的不可变 dataset version
```

不选 B 作为 v1 主路径，原因：

- 规则 worker 直接写 `olist_datasets` 会绕过数据集服务的建表、命名、行数校验和生命周期管理。
- 谁能建表、谁能删表、失败后谁清理，会形成两套 owner。
- 规则输出需要成为“新数据集版本”，这本质上是数据集服务的职责。
- 直接写 ClickHouse 很难保证“元数据登记”和“数据写入”原子一致。
- 只读账号和写账号混用会增加权限面。

方案 B 不是永远不能做，但只能作为明确的性能优化：

- worker 只写 `olist_rule_staging`，不写 `olist_datasets`。
- 数据集服务负责把 staging 结果登记/提升为正式版本。
- staging 表命名、TTL、清理、权限由数据集服务定义。
- 不能把 staging 表直接暴露成正式 `datasetVersionId`。

### 3.2 输出登记 API 需要支持多输出

handoff 里的登记请求只有一个扁平 `schema`，不够。一个运行可能产生：

- 清洗后的主输出
- 隔离区输出
- 多个 `OUTPUT` 节点
- 多个表

建议请求体：

```json
{
  "sourceType": "RULE_RUN",
  "sourceRunId": "run-uuid",
  "sourceWorkbookVersionId": "workbook-version-uuid",
  "parentDatasetVersionId": "input-version-uuid",
  "outputs": [
    {
      "nodeId": "outputs:clean",
      "outputName": "orders_clean",
      "kind": "CLEAN",
      "datasetName": "订单清洗结果",
      "description": "由规则工作簿 v3 生成",
      "idempotencyKey": "run-uuid:outputs:clean:orders_clean",
      "format": "PARQUET",
      "uri": "s3://olist-artifacts/runs/run-uuid/outputs/clean.parquet",
      "contentHash": "sha256:...",
      "rowCount": 12345,
      "tables": [
        {
          "tableName": "orders_clean",
          "schema": [
            {
              "name": "order_id",
              "logicalType": "STRING",
              "nullable": false
            }
          ]
        }
      ]
    }
  ]
}
```

要点：

- 每个 `outputs[]` 元素最终生成一个独立的 dataset version；如果一个输出包含多张表，则这些表属于同一个版本。
- 清洗结果和隔离区建议分别登记，不要混在同一个数据集版本里。
- `sourceRunId`、`sourceWorkbookVersionId`、`nodeId` 必须能追溯。
- `parentDatasetVersionId` 表示输入版本，不要把输入和输出混成一个版本。
- 每个输出有自己的 `idempotencyKey`，至少包含 `runId + nodeId + outputName`。
- 同一幂等键重复提交时返回已有结果；如果内容哈希不同，返回冲突，不要静默覆盖。
- `kind` 区分 `CLEAN`、`QUARANTINE`、`METRIC` 等。质量指标不应该被登记成数据表。
- `schema` 是校验输入，实际 schema 以 Parquet 文件为准；两者不一致就失败。
- 支持 `PARQUET` 为主，`CSV` 作为兼容格式。

返回：

```json
{
  "outputs": [
    {
      "outputName": "orders_clean",
      "datasetId": "logical-dataset-uuid",
      "datasetVersionId": "new-version-uuid",
      "status": "PROCESSING"
    }
  ]
}
```

每个输出独立变为 `READY` 或 `FAILED`。规则侧轮询或接收回调，但不能在 `PROCESSING` 时假装输出已经可用。如果两个输出属于同一个逻辑数据集，也可以让数据集服务把它们组织成同一数据集的后续版本，但登记和幂等仍应按输出拆分。

### 3.3 隔离区和指标怎么处理

- 隔离区：如果用户需要查看/下载，登记成独立的输出数据集版本；如果只是运行产物，保留 Parquet URI 和样例行即可。
- 质量指标：写 `rule_run_nodes.metrics_json` 或独立指标表，不要写进数据集行数据。
- 预览运行：默认只产生 run artifact，不自动创建新的正式数据集版本；只有 `MATERIALIZE_VERSION` 才登记。
- 重试：同一个 `runId + nodeId + outputName` 重试时复用原登记记录，状态从 `FAILED` 重新进入 `PROCESSING`，不要创建第二个版本。

### 3.4 对 P0-3 的最终答复

```text
P0-3 输出回写方案（A/B）：
v1 选 A。worker 写 Parquet + manifest 到对象存储，数据集服务校验、
导入 ClickHouse 并登记为新的不可变 dataset version。
worker 不在 olist_datasets 建表或写入；复杂执行如需中间物化，
只允许写独立的 olist_rule_staging，由数据集服务管理生命周期。
登记接口必须支持多输出、lineage、contentHash 和幂等键。
```

---

## 4. P0-4：datasetVersionId 是否必须不可变

### 4.1 结论：必须不可变

规则库已经按“运行冻结数据集版本”设计：

- `RuleWorkbook.datasetVersionId` 在创建时固定。
- `RuleWorkbookVersion.datasetVersionId` 在发布时固定。
- `RuleRun.datasetVersionId` 在运行时固定。
- 规则编译依赖 `columnId`、`fieldId` 和表结构。
- 运行记录需要可复现、可审计、可回放。

如果数据集版本还能追加文件、改列、改行数，会出现：

- 同一条已发布规则在不同时间运行，输入不同，但没有新版本号。
- 字段绑定和 `columnId` 失效，历史运行无法解释。
- 模型训练数据不可追溯。
- 缓存和结果复用不安全。
- “同一个 datasetVersionId”无法作为幂等和审计依据。

因此：

```text
datasetVersionId 一旦 READY，内容、schema、表、列、行数、manifestHash 全部不可变。
追加文件、重新上传、修改表结构，都必须生成新的 datasetVersionId。
逻辑数据集的名称、描述、标签可以改，但不能改版本内容。
```

### 4.2 建议的数据模型

如果迁移成本可接受，建议现在拆成“逻辑数据集”和“版本快照”：

```text
datasets
  id, owner_id, name, description, current_version_id,
  created_at, updated_at, deleted_at

dataset_versions
  id, dataset_id, version_no, parent_version_id,
  status, manifest_hash, schema_hash,
  source_type, source_run_id, source_workbook_version_id, source_node_id,
  created_at, ready_at, archived_at
```

如果暂时不拆表，至少在当前 `dataset_versions` 上补：

```text
dataset_group_id / logical_dataset_id
version_no
parent_version_id
manifest_hash
schema_hash
is_current
deleted_at
```

并明确：

- 当前 `dataset_versions` 的一行就是不可变快照。
- `addFile` 不再修改旧行，而是生成新行。
- 旧行的表、列、文件、行数、哈希永久冻结。
- `datasetId` 和 `datasetVersionId` 不再混用；API 需要返回两个 ID。

### 4.3 版本状态机

建议：

```text
EMPTY
  -> UPLOADED
  -> PROCESSING
  -> READY
  -> ARCHIVED

PROCESSING -> FAILED -> PROCESSING（仅重试导入，且尚未 READY）
```

约束：

- `READY` 是规则可引用的唯一状态。
- `READY` 之后不能回到 `UPLOADED` / `PROCESSING`。
- `ARCHIVED` 只影响 UI 可见性和清理策略，不改变版本内容。
- 多表、多 sheet 的导入应整体成功后再进入 `READY`；不要让部分表 READY、部分表 PROCESSING。
- `READY` 前校验 ClickHouse 行数与 profiler/manifest 一致。

当前本地代码还有一个需要修正的点：上传完成后只到 `UPLOADED`，没有进入 `READY`，规则工作簿只检查数据集存在，没有检查 `READY`。接入 ClickHouse 后必须补上：

- 数据集服务导入成功 -> `READY`。
- 规则创建/发布/运行前检查 `READY`。
- 非 `READY` 返回明确错误，例如 `DATASET_NOT_READY`。

### 4.4 对 P0-4 的最终答复

```text
P0-4 datasetVersionId 是否不可变：
是，必须严格不可变。READY 后文件、表、列、schema、行数、哈希都不能改。
追加文件/重新上传/改表结构必须生成新 datasetVersionId，旧版本保留。
规则工作簿、发布版本和运行记录都固定 datasetVersionId。
逻辑数据集名称/描述可以改，但不能改版本内容。
当前 addFile 追加到同一记录的语义需要改掉；推荐拆 datasets 和 dataset_versions，
至少也要让 addFile 生成新快照并冻结旧快照。
```

---

## 5. 当前 V8/V9 需要同步修正的地方

这一节是联调前必须对齐的实现细节，否则 handoff 的阶段 1 会和规则库现有代码冲突。

### 5.1 `dataset_columns` 字段不足

当前 V8 只有：

```text
id, dataset_id, table_id, column_name, ordinal, data_type, created_at
```

handoff 阶段 1 需要补：

```text
source_name        原始列名，展示用
physical_name      ClickHouse 物理列名，执行绑定用
logical_type       逻辑类型
clickhouse_type    ClickHouse 类型
nullable           是否可为空
file_id            来源文件
```

另外建议把 `dataset_id` 明确改名为 `dataset_version_id`，或者在 API 层明确它代表版本 ID。现在“dataset_id”同时承担逻辑数据集和版本 ID，是后续最容易出错的地方。

### 5.2 字段绑定的唯一约束会阻断跨版本复用

当前迁移里有：

```sql
CREATE UNIQUE INDEX uk_dataset_field_bindings_field
    ON dataset_field_bindings (field_id);
```

这意味着一个逻辑字段只能绑定一个数据集版本，无法实现 handoff 里说的“跨版本通过 fieldId / dataset_field_bindings 复用”。

建议改为：

```sql
UNIQUE (field_id, dataset_version_id)
UNIQUE (column_id)
```

同时规则编译和字段导入要明确：

- `fieldId` 是逻辑字段身份，跨版本稳定。
- `columnId` 是当前版本的物理列身份。
- `nameSnapshot` 只用于展示。
- 同一个逻辑字段在新版本中重新绑定到新 `columnId`。
- 如果新版本找不到对应列，发布或运行要报 `FIELD_REBIND_REQUIRED`，不能静默用旧列。

### 5.3 `dataset_versions.sha256` 不够表达多文件版本

当前 `sha256` 更像“第一个主文件”的哈希。一个版本可能有多个文件、多个表、多个 sheet，需要版本级：

```text
manifest_hash
schema_hash
```

建议：

- 每个 `dataset_files` 保留自己的 `sha256`。
- 版本级 `manifest_hash` 由有序文件、表、schema、行数等计算。
- `schema_hash` 不包含随机 UUID 和物理名，只包含逻辑 schema，便于跨版本比较。
- 规则运行前校验快照哈希，不匹配直接失败。

### 5.4 逻辑表身份

当前 `dataset_tables.id` 是版本内生成的 UUID，跨版本没有稳定表身份。建议增加：

```text
table_key / logical_table_id
```

例如按“源文件相对路径 + sheet 名”归一化生成。这样：

- 规则工作簿从版本 1 迁移到版本 2 时，可以提示“orders 表已找到，customer_id 列可用”。
- 新版本可以自动建议字段绑定，但仍需要用户确认。
- 不要用 `tableName`（用户可改的展示名）作为唯一匹配依据。

### 5.5 空值推断

当前 profiler 只取前 1000 行做类型推断，且没有记录 nullability。handoff 里“只有观测到 NULL 才使用 Nullable”如果只看样本，会在全量导入时失败。

建议二选一：

- 在导入时对全量数据统计 nullability，再生成 ClickHouse schema；或
- v1 统一把源列建为 `Nullable(...)`，内部 `_row_id` 非空，后续再优化。

不能把“前 1000 行没看到 NULL”当成“整列非空”。

### 5.6 数据类型枚举缺 FLOAT

当前 `FieldDataType` 是：

```text
STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME
```

handoff 类型映射中有 `FLOAT`。要么补 `FLOAT`，要么明确所有浮点都落 `DECIMAL`。建议补 `FLOAT`，映射到 `Float64`；金额仍用 `Decimal(38, 6)` 或更精确的业务配置。

---

## 6. P1 问题的答复

### 6.1 状态与就绪

规则发布/运行必须等数据集 `READY`。

但不要只让规则侧读一个 `status` 字符串。建议 readiness API 返回：

```json
{
  "datasetVersionId": "version-uuid",
  "status": "READY",
  "manifestHash": "sha256:...",
  "schemaHash": "sha256:...",
  "readyAt": "2026-10-26T08:00:00Z"
}
```

规则发布时固定 `datasetVersionId + manifestHash + schemaHash`；运行时再次校验。这样即使有人误操作数据库，也能发现快照不一致。

### 6.2 类型、空值和编码

建议默认：

| 逻辑类型 | ClickHouse |
| --- | --- |
| STRING | `String` 或 `Nullable(String)` |
| INTEGER | `Int64` 或 `Nullable(Int64)` |
| FLOAT | `Float64` 或 `Nullable(Float64)` |
| DECIMAL | `Decimal(38, 6)` 或 `Nullable(Decimal(38, 6))` |
| BOOLEAN | `Bool` 或 `Nullable(Bool)` |
| DATE | `Date32` 或 `Nullable(Date32)` |
| DATETIME | `DateTime64(3, 'UTC')` 或 `Nullable(...)` |
| UNKNOWN | `Nullable(String)` |

约定：

- 字符串列保留空字符串和 NULL 的区别。
- 非字符串列的空字符串按 NULL 处理。
- 日期时间默认 UTC；如果源数据是本地时间，数据集版本要记录 `sourceTimezone`，不要静默假设。
- `Decimal(38,6)` 是默认值，不是所有金额的最终精度；后续可通过数据集级配置覆盖。
- CSV 至少支持 UTF-8（含 BOM）；GBK/GB18030 作为后续可选项，检测失败要明确报编码错误，不要产生乱码。
- `nullable` 必须来自全量扫描或统一 Nullable，不能只来自 1000 行样本。

### 6.3 预览与采样

建议区分三个概念：

- 数据集预览：数据集服务直接查 ClickHouse，`LIMIT n`，只用于看表。
- 规则预览：规则 worker 执行 `PipelinePlan` 后限制输出行数，必须保存 `sampleSize` 和 `seed`。
- 全量运行：执行完整计划，输出登记为新数据集版本。

采样语义要写清楚：

- v1 推荐“按 `_row_id` 排序取前 N 行”，可复现、容易解释。
- 如果做随机采样，必须保存 seed，并使用稳定算法。
- 预览不自动登记正式数据集版本。
- 预览也要有资源上限，不能因为 `LIMIT` 就允许扫描无限大的中间结果。

### 6.4 表名、列名、拼接和重复表头

见 2.4。补充：

- 规则编译器可以处理带引号的特殊标识符，但执行绑定应优先使用规范化物理名。
- 表重命名只改 `tableName`，不改 `physicalTable`。
- 原始列名、显示名、物理名三者分开：
  - `sourceName`：上传时的原始名称
  - `displayName`：字段库/规则库展示名
  - `physicalName`：ClickHouse 实际名称
- 重复表头按 ordinal 唯一化；大小写碰撞按规范化后唯一化。
- 如果用户改字段显示名，不触发重编译；如果改物理列/类型，必须生成新版本。

### 6.5 权限

- 服务间调用使用独立的 service token，不复用终端用户 token。
- 建议使用 OAuth2 client credentials 或签名服务令牌，scope 至少区分：
  - `dataset:catalog:read`
  - `dataset:snapshot:read`
  - `dataset:output:write`
  - `dataset:preview:read`
- 用户发起的运行仍要携带 `ownerId` 或 `on-behalf-of` 信息；数据集服务负责校验该 owner 是否有权限访问目标版本。
- ClickHouse 连接信息走环境变量/secret，不进 catalog。
- `olist_datasets` 只读账号只给 `SELECT`；worker 不得执行 DDL/DML。
- 如果未来多租户隔离要求高，需要评估 ClickHouse 的库/表级授权或按 owner 分库；仅靠应用层过滤不够。这个可以作为 P1 的独立安全设计项。

### 6.6 规模与资源

当前已知限制：

- 单文件最大 1GB。
- ZIP 解压后总大小最大 2GB。
- 最多 200 张表。
- XLSX 单文件最大 100MB。

规则执行还需要补：

- 单次查询最大执行时间。
- 单次查询最大扫描字节/返回行数。
- 单次运行最大内存和并发数。
- 预览与全量运行的资源配额分开。
- ClickHouse 表建议 `MergeTree ORDER BY _row_id`；版本表不可变，不需要按业务日期分区。
- 批量写入 5 万到 10 万行一批；是否启用 async insert 由导入压测决定。
- 复杂 DAG 可能需要 staging；见 P0-1。

### 6.7 删除与历史

handoff 阶段 5 的验收“删除数据集后 ClickHouse 不残留业务表”与“历史规则运行可复现”有冲突，需要改成引用感知的清理策略：

- 默认软删除：`deleted_at` / `ARCHIVED`，UI 不可见，但保留版本。
- 被已发布规则版本或历史运行引用的 `datasetVersionId` 不允许直接硬删。
- 只有没有任何引用、且超过保留期的版本，才允许删除 ClickHouse 表和原始文件。
- 输出数据集独立于输入数据集；删除输入不应删除已经生成的输出数据，但 lineage 仍指向已归档/删除的输入。
- 如果用户强制硬删，运行记录要标记 `SOURCE_DELETED`，不能假装还能重放。
- 隔离区可以设置更短的 TTL，但也要保留 manifest 和运行记录。

### 6.8 需要规则库提供的材料

可以提供：

- 一份最小 `PipelinePlan` JSON，见第 7 节。
- 运行实体字段：

```text
rule_runs:
  id, owner_id, workbook_id, workbook_version_id, dataset_version_id,
  mode, status, sample_size, seed, error_message,
  created_at, started_at, finished_at

rule_run_nodes:
  id, run_id, node_id, node_kind, status, metrics_json,
  sample_uri, error_message, started_at, finished_at
```

- 当前编译器支持 10 类节点：`CHECK / TRANSFORM / FILTER / PROJECT / DEDUPE / JOIN / AGGREGATE / DERIVE / GATE / OUTPUT`。
- 当前运行只创建 `QUEUED` 记录，尚未接 Temporal / Python Worker；不能声称已经真实执行。
- 当前没有节点输出 schema 传播；输出 schema 应以实际 Parquet 为准，由数据集服务校验。
- 测试数据集：建议先用 Olist `orders` + `customers` + `order_items` 的小样本，覆盖空值隔离、两表 JOIN、去重、聚合和一个 OUTPUT。

---

## 7. 最小 PipelinePlan 示例

下面是编译器实际输出的简化形态。**执行端以 `config.predicate` 为规范条件树**；当前同时输出的 `config.condition` 是兼容字段，后续可以移除。

```json
{
  "schemaVersion": 1,
  "compiler": "rule-workbook-compiler/v1",
  "workbookId": "workbook-uuid",
  "datasetVersionId": "dataset-version-uuid",
  "nodes": [
    {
      "id": "checks:missing_order_id",
      "kind": "CHECK",
      "name": "订单号不能为空",
      "stage": "CHECK",
      "enabled": true,
      "inputs": [
        {
          "type": "TABLE",
          "tableId": "table-uuid",
          "datasetVersionId": "dataset-version-uuid",
          "alias": "orders"
        }
      ],
      "config": {
        "schemaVersion": 1,
        "scope": "ROW",
        "predicate": {
          "op": "IS_NULL",
          "args": [
            {
              "type": "FIELD",
              "fieldId": "field-uuid",
              "columnId": "column-uuid",
              "tableId": "table-uuid",
              "nameSnapshot": "order_id"
            }
          ]
        },
        "effect": "QUARANTINE_ROWS",
        "outputAlias": "orders_checked"
      },
      "dependsOn": [],
      "policy": {
        "blocking": "NONE",
        "onError": "FAIL_RUN"
      },
      "output": {
        "alias": "orders_checked"
      }
    },
    {
      "id": "filters:delivered",
      "kind": "FILTER",
      "name": "只保留已送达订单",
      "stage": "CLEAN",
      "enabled": true,
      "inputs": [
        {
          "type": "NODE_OUTPUT",
          "nodeId": "checks:missing_order_id",
          "outputAlias": "orders_checked",
          "alias": "orders_checked"
        }
      ],
      "config": {
        "schemaVersion": 1,
        "predicate": {
          "op": "EQ",
          "args": [
            {
              "type": "FIELD",
              "fieldId": "field-uuid",
              "columnId": "column-uuid",
              "tableId": "table-uuid",
              "nameSnapshot": "order_status"
            },
            {
              "type": "LITERAL",
              "value": "delivered"
            }
          ]
        },
        "outputAlias": "orders_delivered"
      },
      "dependsOn": [],
      "policy": {
        "blocking": "NONE",
        "onError": "FAIL_RUN"
      },
      "output": {
        "alias": "orders_delivered"
      }
    },
    {
      "id": "outputs:training",
      "kind": "OUTPUT",
      "name": "orders_training",
      "stage": "OUTPUT",
      "enabled": true,
      "inputs": [
        {
          "type": "NODE_OUTPUT",
          "nodeId": "filters:delivered",
          "outputAlias": "orders_delivered",
          "alias": "orders_delivered"
        }
      ],
      "config": {
        "schemaVersion": 1,
        "mode": "MATERIALIZE_VERSION",
        "format": "PARQUET",
        "name": "orders_training"
      },
      "dependsOn": [],
      "policy": {
        "blocking": "NONE",
        "onError": "FAIL_RUN"
      }
    }
  ]
}
```

执行端必须：

- 按 `inputs` / `NODE_OUTPUT` 构建依赖图，不按行号排序。
- 对 `tableId` 和 `columnId` 通过 execution snapshot 解析物理名。
- 对 JOIN 等节点强制校验输入数量。
- 不使用 `nameSnapshot` 作为执行字段名。
- 不直接执行 `document_json` 里的原始单元格。

---

## 8. 建议的联调顺序

在 handoff 的阶段 1/2 之前，先完成以下契约修正：

1. 冻结版本语义：明确 `datasetVersionId` 不可变；`addFile` 生成新版本；新增 `manifestHash` / `schemaHash`。
2. 明确 ID 语义：拆出逻辑 `datasetId` 和 `datasetVersionId`；API 返回两个 ID。
3. 补列元数据：`physical_name`、`logical_type`、`clickhouse_type`、`nullable`、`file_id`。
4. 修正字段绑定唯一约束：`(field_id, dataset_version_id)`，而不是 `field_id` 单列唯一。
5. 补 `READY` 状态和 readiness API；规则发布/运行检查 `READY`。
6. 实现 Catalog 和 execution snapshot 两个接口。
7. 用一个小数据集完成编译 + 只读 ClickHouse 查询联调。
8. 再实现输出登记和 Parquet 导入。
9. 最后补删除、归档、权限、配额和集成测试。

建议先不要做全量 ClickHouse 导入再回头改版本模型，否则数据集、字段绑定、规则运行三处都会返工。

---

## 9. 可以直接回给数据集库同学的简版

```text
P0-1 执行访问方式：
规则 worker 直连 ClickHouse，只读查询 olist_datasets 的源表。数据集服务提供 catalog / execution-snapshot / preview，不做全量数据代理。规则侧保存 datasetVersionId + tableId + columnId + fieldId，执行时用 execution snapshot 解析 physicalTable / physicalName。不要用 olist_analytics 承载原始数据集。复杂 DAG 如需中间物化，只写独立的 olist_rule_staging，不写 olist_datasets。

P0-2 Catalog 是否够用 / 缺什么：
方向够，但建议拆成逻辑 Catalog 和 execution snapshot。Catalog 必带 datasetVersionId、status、manifestHash、schemaHash、tables[].tableId/tableKey/tableName/ordinal/rowCount、columns[].columnId/name/ordinal/logicalType/nullable。physicalTable、physicalName、clickhouseType 放 execution snapshot。不需要 sampleRows 和 primaryKey；sampleRows 走 preview，primaryKey 后续可选。不要返回 ClickHouse 账号密码。

P0-3 输出回写方案（A/B）：
v1 选 A。worker 写 Parquet + manifest 到对象存储，数据集服务校验、导入 ClickHouse 并登记为新的不可变 dataset version。worker 不在 olist_datasets 建表或写入；如需中间物化，只写 olist_rule_staging。登记接口要支持多输出、lineage、contentHash、幂等键和 FAILED 重试。

P0-4 datasetVersionId 是否不可变：
必须严格不可变。READY 后文件、表、列、schema、行数、哈希都不能改。追加文件/重新上传/改表结构必须生成新 datasetVersionId，旧版本保留。规则工作簿、发布版本、运行记录都固定 datasetVersionId。当前 addFile 追加到同一记录的语义需要改掉；推荐拆 datasets 和 dataset_versions，至少也要让 addFile 生成新快照并冻结旧快照。

额外三个必须先改的实现点：
1. dataset_columns 补 physical_name、logical_type、clickhouse_type、nullable、file_id，并明确 dataset_id 是版本 ID。
2. dataset_field_bindings 的唯一约束从 field_id 改为 (field_id, dataset_version_id)，否则无法跨版本复用字段。
3. 上传完成必须进入 READY，规则创建/发布/运行必须校验 READY；当前只到 UPLOADED。
```

---

## 10. 仍然需要双方确认的开放问题

这些问题不阻塞阶段 1，但会影响阶段 2/4 的最终实现：

- 逻辑数据集和版本是否现在就拆表，还是先加 `dataset_group_id` 过渡。
- 规则 worker 是否允许写 `olist_rule_staging`；如果不允许，复杂 DAG 只能依赖 CTE，性能边界需要压测。
- 多租户场景下，ClickHouse 只读账号如何限制不同 owner 的数据可见性。
- 日期时间的源时区如何配置；默认 UTC 是否可接受。
- `Decimal` 的默认精度和金额字段的精度是否需要数据集级配置。
- 输出登记是同步导入还是异步任务；是否需要回调通知。
- 归档/硬删除的保留期和引用检查规则。
- 规则执行是否需要 Temporal 或独立 job queue；当前 `QUEUED` 只是占位状态，尚无 worker 消费。

---

## 11. 最终边界

这份回复确认的是架构契约，不是声称数据集库或规则执行已经完成。

当前事实：

- 数据集库还没有 ClickHouse 导入和 Catalog。
- 规则库还在本地工作区，运行只创建 `QUEUED`，没有真实执行 Worker。
- 节点输出 schema 传播尚未实现。
- 独立表关系库尚未实现，JOIN 仍在规则行内配置。
- 跨数据集版本的字段复用需要先修正 `dataset_field_bindings` 唯一约束。

在这些边界内，先冻结 `datasetVersionId` 不可变、Catalog/execution snapshot 分离、输出走方案 A，可以避免后续规则编译、字段绑定和运行审计反复返工。