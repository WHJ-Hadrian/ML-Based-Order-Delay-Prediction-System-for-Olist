# 数据集库 ClickHouse 实施方案 v2（最终决策版）

> 输入：`docs/dataset-clickhouse-handoff.md`（v1）、`dataset-clickhouse-reply.md`  
> 日期：2026-10-26  
> 状态：契约和迁移边界已定；实现代码尚未开始  
> 重要：本文件取代 v1 handoff 中的默认假设。

---

## 0. 已对齐的 P0 决策

| 问题 | 结论 | 对数据集库的影响 |
| --- | --- | --- |
| P0-1 执行怎么访问数据 | 规则 worker 直连 ClickHouse，只读查询 `olist_datasets` 源表；数据集服务提供 catalog、execution snapshot、preview，不做全量数据代理 | 数据集库维护 ClickHouse 表、只读账号、catalog 和 snapshot |
| P0-2 Catalog 够不够 | 拆成逻辑 Catalog 和 execution snapshot；补 `manifestHash`、`schemaHash`、`tableKey` | 两个接口分开，物理名只放 snapshot |
| P0-3 输出回写 | v1 选方案 A：worker 写 Parquet + manifest，数据集服务校验、导入 ClickHouse 并登记新版本 | 多输出登记、contentHash、lineage、幂等 |
| P0-4 版本是否不可变 | 必须严格不可变；`READY` 后文件、表、列、schema、行数全部冻结 | `addFile` 生成新版本，旧版本冻结 |

库边界：

- `olist_datasets`：源数据，规则 worker 只读。
- `olist_rule_staging`：可选，复杂 DAG 的中间物化，worker 受限读写，由数据集库定义生命周期。
- `olist_analytics`：分析查询/下游集市，不承载原始上传数据。
- 规则 worker 不直接写 `olist_datasets`，输出走方案 A。

---

## 1. 迁移顺序与边界（已定）

### 1.1 顺序

1. 规则库先修正并合入 `V8/V9`。
2. 数据集库随后使用 `V10+`。
3. 不使用 Flyway `outOfOrder`。

原因：

- 空库上 `V10` 不能引用还不存在的表。
- `V8/V9` 晚到会造成迁移历史乱序。
- 规则库与数据集库共用 `olist_datasets` 同一条 Flyway 迁移链。

### 1.2 V8/V9 的边界

`V8/V9` 只修规则库/字段库自己的问题，例如：

- `dataset_field_bindings` 唯一约束。
- 列命名、字段绑定相关修正。
- `dataset_columns` 字段补齐和 `dataset_id -> dataset_version_id` 改名。

不要把 `datasets / dataset_versions` 拆表塞回 `V8/V9`。

### 1.3 V8/V9 是否已经执行过

- 如果 `V8/V9` 还没在任何持久库执行过：修正规则库自身问题后推送。
- 如果已经执行过：立即冻结；所有后续变更用 `V10+`。
- 不修改已经应用过的迁移文件。

### 1.4 V10 边界

`V10` 只做数据集版本模型和列/绑定相关迁移，不和 ClickHouse 导入混在一起。

`V10` 包含：

- 新建 `datasets`。
- 回填现有 `dataset_versions`。
- `dataset_versions` 增加版本字段和哈希。
- `dataset_columns` 字段补齐、`dataset_id -> dataset_version_id`。
- `dataset_field_bindings` 唯一约束修正。
- `dataset_tables` 增加 `table_key`、`physical_table`。
- 必要索引和回填。

`V10` 不包含：

- ClickHouse 导入任务。
- 规则工作簿/运行表结构变更。
- 规则审计外键调整。

规则审计外键放在 `V11`，见第 8 节。

### 1.5 CI 迁移测试

至少覆盖两条路径：

1. 空库全量迁移 `V1 -> latest`。
2. `V9 -> V10` 升级，并带种子数据校验回填、外键、唯一约束、哈希。

建议再加：

3. `V10 -> V11` 升级。
4. 迁移失败时不允许部分提交。

---

## 2. V10 数据集迁移范围

### 2.1 新建 `datasets`

```text
datasets
  id
  owner_id
  name
  description
  current_version_id
  created_at
  updated_at
  deleted_at
```

### 2.2 `dataset_versions` 增加字段

```text
dataset_id
version_no
parent_version_id
manifest_hash
schema_hash
source_type
source_run_id
source_workbook_version_id
source_node_id
ready_at
archived_at
deleted_at
```

约束：

- `dataset_versions.id` 继续作为 `datasetVersionId`；`V8/V9` 里的 `dataset_version_id` 外键不用改。
- `dataset_versions.dataset_id -> datasets.id`。
- `datasets.current_version_id -> dataset_versions.id`。
- `UNIQUE (dataset_id, version_no)`。
- `dataset_versions.name / description / owner_id` 保留为版本快照列，避免历史显示漂移；`datasets` 为权威来源。

### 2.3 回填现有版本

- 每个现有 `dataset_versions` 行创建一个逻辑 `datasets` 行。
- `dataset_versions.dataset_id` 指向新建的逻辑数据集。
- 初始 `version_no = 1`，`parent_version_id = null`。
- `datasets.current_version_id` 指向该版本。
- 现有 `name`、`description`、`owner_id` 复制到 `datasets`，同时保留版本快照。
- 现有 `status` 保持不变；未完成 ClickHouse 导入的版本保持 `UPLOADED`，不要伪造 `READY`。

### 2.4 列和表元数据

> 条件执行：如果 V8/V9 尚未执行，并且已经在 V8/V9 完成列字段、`dataset_version_id` 改名和绑定唯一约束，则 V10 跳过这些步骤；如果 V8/V9 已执行或未修正，则由 V10 补做。V10 不重复添加已存在的列/索引。

- `dataset_columns.dataset_id` 改名为 `dataset_version_id`。
- 补字段：
  - `physical_name`
  - `logical_type`
  - `clickhouse_type`
  - `nullable`
  - `file_id`
- `dataset_field_bindings`：
  - 删除 `field_id` 单列唯一。
  - 增加 `UNIQUE (field_id, dataset_version_id)`。
  - 保留 `UNIQUE (column_id)`。
- `dataset_tables`：
  - 增加 `table_key` / `logical_table_id`。
  - 增加 `physical_table`。
- 补索引：
  - `dataset_versions (dataset_id, version_no)`。
  - `dataset_columns (dataset_version_id, table_id, ordinal)`。
  - `dataset_field_bindings (dataset_version_id, field_id)`。
  - `dataset_tables (dataset_version_id, table_key)`。
  - `datasets (owner_id, deleted_at)`。

### 2.5 哈希回填

- `schema_hash`：由 `tableKey`、列名、逻辑类型、可空性、顺序计算。
- `manifest_hash`：由有序文件 sha256、文件大小、`tableKey`、行数、`schema_hash` 计算。
- `schema_hash` 不包含 UUID、`physical_name`、ClickHouse 类型。
- `manifest_hash` 不包含物理表名/列名。
- 如果历史数据无法可靠计算，允许暂时为空；但 `READY` 前必须补齐。
- 规则发布时冻结 `datasetVersionId + manifestHash + schemaHash`；运行时重新校验。

### 2.6 `addFile` 语义

- `addFile` 不再追加到旧版本。
- 生成新的 `datasetVersionId`。
- 新版本复制旧版本的文件/表/列元数据（新 ID，相同存储 key），再加入新文件。
- 旧版本永久冻结。
- 返回新的 `datasetId + datasetVersionId`。
- 前端和 API 需要处理“追加后版本号变化”。

---

## 3. 数据模型与 ID 语义

### 3.1 直接拆表

采用：

```text
datasets
  id, owner_id, name, description, current_version_id,
  created_at, updated_at, deleted_at

dataset_versions
  id, dataset_id, version_no, parent_version_id,
  status, manifest_hash, schema_hash,
  source_type, source_run_id, source_workbook_version_id, source_node_id,
  created_at, ready_at, archived_at, deleted_at
```

不使用 `logical_dataset_id` 过渡方案。

### 3.2 ID 语义

| ID | 稳定性 | 用途 |
| --- | --- | --- |
| `datasetId` | 跨版本稳定 | 逻辑数据集、名称、所有者、列表 |
| `datasetVersionId` | 每个快照唯一、不可变 | 规则绑定、运行冻结、ClickHouse 表定位 |
| `tableId` | 当前版本内稳定 | 版本内表身份 |
| `tableKey` | 跨版本尽量稳定 | 表迁移、字段绑定建议，如 `file/orders` |
| `columnId` | 当前版本内稳定 | 版本内物理列身份 |
| `fieldId` | 跨版本稳定 | 逻辑字段身份 |

规则模型只保存 `datasetVersionId + tableId + columnId + fieldId + nameSnapshot`。  
`physicalTable`、`physicalName`、`clickhouseType` 不作为规则语义，只通过 execution snapshot 在执行时解析。

### 3.3 API 兼容

- 新接口使用 `/api/v1/dataset-versions/{datasetVersionId}`。
- 旧接口 `/api/v1/datasets/{id}` 过渡期可以作为 alias：
  - 如果 `{id}` 是逻辑数据集 ID，返回当前版本。
  - 如果 `{id}` 是版本 ID，返回该版本，兼容旧前端。
- 所有响应同时返回 `datasetId` 和 `datasetVersionId`。
- 不允许再把 `dataset_versions.id` 当逻辑 `datasetId` 使用。

---

## 4. 列元数据与类型

### 4.1 `dataset_columns` 最终字段

```text
id                 columnId，版本内唯一
dataset_version_id 所属不可变版本
table_id           所属表
file_id            来源文件
ordinal            列顺序
name               原始列名（sourceName）
physical_name      ClickHouse 物理列名
logical_type       逻辑类型
clickhouse_type    ClickHouse 类型
nullable           是否可空
created_at
```

### 4.2 物理命名

- 物理表名：`ds_{datasetVersionId去横线}_{tableId去横线}`。
- 物理列名：`c{ordinal:03d}`，可选加安全 slug，如 `c000_order_id`。
- 重复表头：源名称加 `__2`、`__3`，物理名仍按 ordinal 唯一。
- 空表头：源名称 `column_<ordinal>`。
- 大小写碰撞：规范化后唯一化，原始名保留。
- 物理名一旦版本 `READY` 不可变。
- 原始名、显示名、物理名三者分开。

### 4.3 逻辑表身份

`dataset_tables.table_key` 建议来源：

- CSV/TSV：相对文件路径或文件名规范化。
- ZIP：压缩包内相对路径。
- XLSX：文件名 + sheet 名。

不要用用户可改的 `tableName` 作为唯一匹配依据。

### 4.4 类型映射

| 逻辑类型 | ClickHouse |
| --- | --- |
| STRING | `String` / `Nullable(String)` |
| INTEGER | `Int64` / `Nullable(Int64)` |
| FLOAT | `Float64` / `Nullable(Float64)` |
| DECIMAL | `Decimal(38, 6)` / `Nullable(Decimal(38, 6))` |
| BOOLEAN | `Bool` / `Nullable(Bool)` |
| DATE | `Date32` / `Nullable(Date32)` |
| DATETIME | `DateTime64(3, 'UTC')` / `Nullable(...)` |
| UNKNOWN | `Nullable(String)` |

必须补充：

- `FieldDataType` 增加 `FLOAT`。
- v1 默认所有源列 `Nullable(...)`，避免全量导入时出现未观测到的 NULL。
- 字符串保留空字符串和 NULL 的区别；非字符串空字符串按 NULL。
- 日期时间默认 UTC；源时区作为版本级配置记录。
- `Decimal(38,6)` 是默认值，金额精度后续可配置。
- CSV 至少支持 UTF-8（含 BOM）；GBK/GB18030 作为后续可选项，检测失败要明确报错。

---

## 5. ClickHouse 物理模型

### 5.1 库和账号

```text
olist_datasets       源数据；数据集服务读写，规则 worker 只读
olist_rule_staging   可选；worker 受限读写中间结果
olist_analytics      分析/下游集市，不承载原始数据集
```

账号：

- `olist_dataset_writer`：`olist_datasets` 建表、写入、删除。
- `olist_rule_reader`：`olist_datasets.*` 只读 `SELECT`。
- staging 单独账号，只对 `olist_rule_staging` 有受限写权限。
- 连接信息只走环境变量/secret，不进 catalog、不回传前端。

### 5.2 表引擎

- `MergeTree`。
- `ORDER BY _row_id`。
- `_row_id UInt64` 非空，导入时按顺序生成。
- 版本表不可变，不按业务日期分区；后续如有需要再按时间分区。
- 批量写入 5 万到 10 万行一批；是否 async insert 由压测决定。

### 5.3 导入约束

- 单文件最大 1GB。
- ZIP 解压后总大小最大 2GB。
- 最多 200 张表。
- XLSX 单文件最大 100MB。
- 多表导入必须整体成功后再进入 `READY`。
- `READY` 前校验 ClickHouse 行数与 profiler/manifest 一致。
- 失败可重试；重试前清理临时表；以 `datasetFileId + tableId` 幂等。

---

## 6. 状态机与 READY

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
- `READY` 后文件、表、列、schema、行数、哈希全部冻结。
- 规则创建、发布、运行前必须检查 `READY`；非就绪返回 `DATASET_NOT_READY`。
- 当前上传完成后只到 `UPLOADED`，这是必须修的行为。

Readiness API：

```http
GET /api/v1/dataset-versions/{datasetVersionId}/readiness
```

```json
{
  "datasetVersionId": "version-uuid",
  "status": "READY",
  "manifestHash": "sha256:...",
  "schemaHash": "sha256:...",
  "readyAt": "2026-10-26T08:00:00Z"
}
```

---

## 7. API 契约

### 7.1 逻辑 Catalog

```http
GET /api/v1/dataset-versions/{datasetVersionId}/catalog
```

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

不返回：

- `sampleRows`
- `primaryKey`
- `physicalTable`
- `physicalName`
- `clickhouseType`
- ClickHouse 账号密码

兼容路径：

```http
GET /api/v1/datasets/{datasetId}/catalog
```

实现时必须明确 `datasetId` 是逻辑数据集还是版本；长期不要混用。

### 7.2 Execution snapshot

```http
GET /internal/dataset-versions/{datasetVersionId}/execution-snapshot
```

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

- 不返回连接主机、端口、账号、密码。
- worker 用 `datasetVersionId + schemaHash` 缓存映射，并校验哈希。
- 规则 worker 只使用 snapshot 中的物理名，不直接使用用户输入。

### 7.3 Preview

- 数据集预览：数据集服务查 ClickHouse，`LIMIT n`。
- 规则预览：worker 执行 `PipelinePlan` 后限制输出行数，必须保存 `sampleSize` 和 `seed`。
- v1 采样建议按 `_row_id` 排序取前 N 行；随机采样必须保存 seed。
- 预览不自动登记正式数据集版本。

### 7.4 输出登记

```http
POST /internal/dataset-versions/imports
```

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

要求：

- 每个 `outputs[]` 最终生成独立的不可变 dataset version。
- 清洗结果和隔离区分别登记；质量指标写 `rule_run_nodes.metrics_json`，不登记成数据表。
- `sourceRunId`、`sourceWorkbookVersionId`、`nodeId` 必须可追溯。
- `parentDatasetVersionId` 表示输入版本，输入和输出不能混成一个版本。
- 每个输出有 `idempotencyKey`；重复提交返回已有结果；内容哈希不同返回冲突。
- `schema` 是校验输入，实际 schema 以 Parquet 为准；不一致失败。
- v1 以 `PARQUET` 为主，`CSV` 兼容。
- 输出可以异步导入；规则侧轮询或接收回调，不能在 `PROCESSING` 时假装可用。
- 同一个 `runId + nodeId + outputName` 重试时复用原登记记录，状态从 `FAILED` 回到 `PROCESSING`，不创建第二个版本。

### 7.5 权限 scope

```text
dataset:catalog:read
dataset:snapshot:read
dataset:output:write
dataset:preview:read
```

- 服务间调用使用独立 service token，不复用终端用户 token。
- 用户发起的运行仍携带 `ownerId` 或 `on-behalf-of`。
- 数据集服务校验 owner 是否有权访问目标版本。
- ClickHouse 只读账号只给 `SELECT`，worker 不得执行 DDL/DML。
- 多租户隔离如果要更严格，需要评估 ClickHouse 库/表级授权或按 owner 分库；仅靠应用层过滤不够。

---

## 8. 删除、引用检查与审计

### 8.1 同库引用检查

规则库和数据集库共用 `olist_datasets` MySQL 库，Flyway 同一条迁移链。

引用检查直接查：

- `rule_workbooks.dataset_version_id`
- `rule_workbook_versions.dataset_version_id`
- `rule_runs.dataset_version_id`

封装成 `DatasetReferenceChecker`，不要把 SQL 散落在数据集服务里：

```java
interface DatasetReferenceChecker {
    boolean hasReferences(UUID datasetVersionId);
}
```

实现可以是 `JdbcDatasetReferenceChecker`。未来拆库时替换成规则服务 API 或 outbox。

### 8.2 删除策略

- 默认软删除：`datasets.deleted_at` / `dataset_versions.archived_at` / `ARCHIVED`。
- 被已发布规则版本或历史运行引用的 `datasetVersionId` 不允许直接硬删。
- 硬删除有引用时返回冲突，例如 `409 DATASET_IN_USE`。
- 只有没有任何引用、且超过保留期的版本，才允许删除 ClickHouse 表和原始文件。
- 输出数据集独立于输入数据集；删除输入不删除已生成的输出，lineage 仍指向已归档/删除的输入。
- 强制硬删时，运行记录标记 `SOURCE_DELETED`，不能假装还能重放。
- 隔离区可以设置更短 TTL，但保留 manifest 和运行记录。

### 8.3 V9 外键风险

V9 里 `rule_runs` 和 `rule_workbook_versions` 对 workbook 是 `ON DELETE CASCADE`。删除工作簿会连带清空运行历史。

如果运行审计重要，V11 要做：

- `rule_workbooks` 增加 `deleted_at`，改为软删除。
- 或把 `rule_runs.workbook_id`、`rule_workbook_versions.workbook_id` 外键改成 `RESTRICT`。
- 推荐两者都做：软删除 + `RESTRICT` 作为防御。
- `rule_runs` 增加 `source_deleted_at` 或 `source_deleted` 标记。
- 给 `rule_workbooks.dataset_version_id`、`rule_workbook_versions.dataset_version_id`、`rule_runs.dataset_version_id` 加索引，支持引用检查。

---

## 9. 实施阶段

### 阶段 0：迁移协作

- 规则库修正并合入 V8/V9。
- 确认 V8/V9 是否已在持久库执行。
- 确认 V10 起始编号。
- 不使用 `outOfOrder`。

验收：空库全量迁移和 V9 -> V10 升级都可重复执行。

### 阶段 1：V10 数据集迁移 + 版本模型代码

- 新建 `datasets`。
- 回填现有版本。
- `dataset_versions` 增加版本字段和哈希。
- `addFile` 生成新版本。
- API 同时返回 `datasetId` 和 `datasetVersionId`。
- 旧 `/datasets/{id}` alias 到当前版本。

验收：`READY` 后同一 `datasetVersionId` 内容不可变；新上传/追加生成新版本。

### 阶段 2：列元数据与绑定约束

- `dataset_columns` 最终字段。
- `dataset_field_bindings` 唯一约束。
- `dataset_tables.table_key` / `physical_table`。
- `FieldDataType.FLOAT`。
- 类型推断、物理名生成、哈希计算。

验收：跨版本字段复用可工作；同一版本内物理列唯一；类型映射可生成 ClickHouse DDL。

### 阶段 3：ClickHouse 导入与 READY

- 新增 `olist_datasets` 库和账号。
- 上传后异步导入：建表、流式写入、校验行数、更新状态。
- 支持 CSV/TSV/ZIP/XLSX。
- 失败重试、临时表清理、幂等。
- 导入成功 -> `READY`；失败 -> `FAILED`。
- 规则创建/发布/运行前检查 `READY`。

验收：`READY` 前不可被规则引用；行数一致；重复导入不重复数据。

### 阶段 4：Catalog、execution snapshot、preview

- 实现逻辑 Catalog。
- 实现 execution snapshot。
- 实现 readiness API。
- 数据集预览/分页/搜索切 ClickHouse。
- 导入期间文件 fallback。
- 物理名不进入 Catalog。

验收：规则 worker 只靠 catalog + snapshot 就能生成只读查询；不需要访问原始文件。

### 阶段 5：输出登记

- 实现多输出登记 API。
- 支持 Parquet 导入、contentHash、lineage、幂等。
- 清洗结果、隔离区、指标分开处理。
- 输出登记为新的不可变版本。
- 规则运行可追溯输入/输出版本。

验收：同一运行重复登记不重复建版本；输出可查询；输入删除不影响输出。

### 阶段 6：删除归档、审计、权限、测试

- `DatasetReferenceChecker`。
- 引用感知软删除/归档。
- V11 规则审计外键/软删除。
- ClickHouse 只读/读写账号。
- 服务 token scope。
- 集成测试和迁移测试。

验收：删除有引用版本被阻止；无引用版本可归档清理；权限边界有效。

---

## 10. 实施清单

- [ ] 规则库合入修正后的 V8/V9
- [ ] 确认 V8/V9 是否已在持久库执行
- [ ] V10 新建 `datasets` 并回填
- [ ] `dataset_versions` 版本字段和哈希
- [ ] `dataset_versions.id` 保持版本 ID
- [ ] 旧 `/datasets/{id}` alias 当前版本
- [ ] `addFile` 生成新版本
- [ ] `dataset_columns.dataset_id -> dataset_version_id`
- [ ] `dataset_columns` 补 `physical_name/logical_type/clickhouse_type/nullable/file_id`
- [ ] `dataset_field_bindings` 唯一约束修正
- [ ] `dataset_tables.table_key` / `physical_table`
- [ ] `FieldDataType.FLOAT`
- [ ] 物理表名/列名生成规则
- [ ] `manifest_hash` / `schema_hash`
- [ ] `olist_datasets` 初始化与账号
- [ ] ClickHouse 异步导入
- [ ] `READY` 状态流转
- [ ] readiness API
- [ ] 逻辑 Catalog
- [ ] execution snapshot
- [ ] 预览/分页/搜索切 ClickHouse
- [ ] 多输出登记 API
- [ ] `DatasetReferenceChecker`
- [ ] 引用感知软删除/归档
- [ ] V11 规则审计外键/软删除
- [ ] 服务 token scope
- [ ] 空库全量迁移测试
- [ ] V9 -> V10 升级测试
- [ ] ClickHouse 集成测试

---

## 11. 仍需确认

这些问题不阻塞 V10，但会影响阶段 3/5/6：

- 规则 worker 是否允许写 `olist_rule_staging`；如果不允许，复杂 DAG 只能依赖 CTE，需要压测性能边界。
- 多租户场景下，ClickHouse 只读账号如何限制不同 owner 的数据可见性。
- 日期时间的源时区如何配置；默认 UTC 是否可接受。
- `Decimal` 的默认精度和金额字段精度是否需要数据集级配置。
- 输出登记是同步导入还是异步任务；是否需要回调通知。
- 归档/硬删除的保留期和引用检查规则。
- 规则执行是否需要 Temporal 或独立 job queue；当前 `QUEUED` 只是占位状态，尚无 worker 消费。
- `addFile` 新版本复制旧文件元数据的策略是否接受（相同存储 key，新 fileId/tableId/columnId）。
- V10 是否固定在数据集库手里；如果规则库还要用 V10，需要重新分配编号。

---

## 12. 本轮不做

- 不修改规则库代码。
- 不修改对方的 V8/V9。
- 不使用 Flyway `outOfOrder`。
- 不在 V8/V9 中拆 `datasets / dataset_versions`。
- 不在 V10 中混入 ClickHouse 导入。
- 不伪造 `READY` 或运行结果。
- 不把行数据写入 MySQL。
- 不开放任意 SQL/Python/JS。

---

## 13. 下一步

1. 规则库修正并推送 V8/V9。
2. 数据集库准备 V10：拆表、回填、列/绑定约束、哈希。
3. 跑空库全量迁移 + V9 -> V10 升级测试。
4. 做 ClickHouse 导入和 `READY`。
5. 做 Catalog / execution snapshot / preview。
6. 做输出登记、引用检查、V11 审计、权限和集成测试。