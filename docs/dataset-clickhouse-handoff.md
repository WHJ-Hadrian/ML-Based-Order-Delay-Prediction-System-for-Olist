# 数据集库 × 规则库 ClickHouse 对接说明

> 日期：2026-10-26  
> 项目：`ML-Based-Order-Delay-Prediction-System-for-Olist`  
> 用途：数据集库改造计划、ClickHouse 对接契约、待规则库确认的问题  
> 状态：v1 对接稿，已被 `docs/dataset-clickhouse-plan-v2.md` 取代  
> **v2 更新（2026-10-26）**：P0 问题已由 `dataset-clickhouse-reply.md` 回答。v1 中“物理名直接使用原始列名”“删除数据集后不残留 ClickHouse 表”“datasetVersionId 可以追加”等假设均已作废；实施以 v2 方案为准。

---

## 0. 一句话结论

数据集库负责把上传文件变成 ClickHouse 可查询表；规则库负责规则定义、编译和执行；MySQL 只存元数据，不存数据行。

```text
上传文件
  -> 数据集库：schema 探测 + ClickHouse 导入 + 状态管理
  -> 规则库：绑定 datasetVersionId，编译 PipelinePlan，执行清洗
  -> 清洗结果：新数据集版本 / 隔离区 / 质量指标
```

---

## 1. 背景

规则库开发同学正在实现“字段库 + Excel 式规则库 + 编译执行”模块，代码尚未推送到远程。

数据集库这边先独立完成 ClickHouse 数据平面，保证规则库后续可以直接对接，不需要再改数据集库的核心存储模型。

本文档说明：

1. 数据集库准备怎么改。
2. 两边建议遵循的默认契约。
3. 需要规则库同学确认的问题。
4. 规则库同学可以向我们了解的事项。

---

## 2. 当前仓库现状

### 2.1 数据集库

- `dataset-service` 目前只保存元数据：`dataset_versions`、`dataset_files`、`dataset_tables`。
- 上传文件原样保存在本地磁盘或 S3，预览、分页、搜索都是临时读文件解析。
- 没有 `dataset_columns`，profiler 只统计列数，不保存列名、列类型、列顺序。
- 没有 ClickHouse 写入或查询逻辑。
- `dataset_versions` 目前更像“数据集”而不是不可变版本：`addFile` 会往同一条记录追加文件。

参考：

- `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/service/impl/DatasetServiceImpl.java`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/profiler/impl/DefaultDatasetProfiler.java`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/resources/db/migration/`

### 2.2 ClickHouse

- `compose.yaml` 已包含 ClickHouse `26.10.2`。
- 当前只有一个库 `olist_analytics`，端口 `8123`（HTTP）和 `9002`（native）。
- 还没有数据集专用库、表、只读账号。

### 2.3 规则库

- 当前 `main` 分支没有 `RuleWorkbook`、`V8/V9`、`dataset_columns`、`dataset_field_bindings` 等实现。
- 现有代码只有旧版扁平规则表 `cleaning_rules`，没有绑定 `datasetVersionId`，也没有真正执行。
- 规则库的具体实体、API、`PipelinePlan` 结构以规则库同学本地代码为准。
- 本文档不假设规则库代码已经完成，只约定两边交界的部分。

---

## 3. 目标架构

```text
                      MySQL
        ┌─────────────────────────────────┐
        │ dataset_versions / files        │
        │ dataset_tables / columns        │
        │ field_definitions / bindings    │
        │ rule_workbooks / versions       │
        │ rule_runs / rule_run_nodes      │
        └─────────────────────────────────┘
                       │
                       │ 元数据、状态、版本、运行记录
                       │
  原始文件 ────────> 数据集库 ────────> ClickHouse
  (S3/本地)           │                  (olist_datasets)
                      │
                      └──── catalog API ────> 规则库
                                               │
                                               ├─ 编译 PipelinePlan
                                               ├─ 执行清洗
                                               └─ 输出新数据集版本 / 隔离区
```

原则：

- 原始文件是 source of truth。
- ClickHouse 是可重建的数据副本，不承担唯一存储职责。
- MySQL 只存小数据：元数据、规则、版本、状态、指标。
- 规则库不直接依赖原始 CSV/XLSX，只依赖数据集库提供的 catalog。
- 规则执行不直接拼表名，优先使用稳定 ID + 数据集库解析出的物理名。

---

## 4. 数据集库改造计划

### 阶段 0：契约对齐

目标：先冻结两边交界的字段和语义，避免一边写完再返工。

产出：

- 本文件中的 P0 问题有明确答复。
- `catalog` 返回结构确认。
- 输出回写方式确认。
- `datasetVersionId` 是否不可变确认。

验收：

- 规则库同学确认 P0 的 4 个问题。
- 双方认可默认契约，或者明确列出差异。

### 阶段 1：列级 schema 落 MySQL

目标：让字段引用有稳定的 `columnId`，而不是靠列名字符串。

内容：

- 扩展 profiler：从只统计列数，改为返回列名、列顺序、推断类型、是否可空。
- 新增 `dataset_columns` 表：
  - `id`（稳定 columnId）
  - `dataset_id`
  - `table_id`
  - `file_id`
  - `ordinal`
  - `name`（原始列名）
  - `physical_name`（ClickHouse 列名）
  - `logical_type`
  - `clickhouse_type`
  - `nullable`
  - `created_at`
- 上传或追加文件时写入列元数据。
- 提供列查询接口，供字段库和规则库级联使用。
- 对已有数据提供一次性回填或重建入口。

验收：

- 上传 CSV/TSV/ZIP/XLSX 后，能查到每张表的列名、类型、顺序和稳定 ID。
- 同一数据集版本内列 ID 稳定；跨版本通过 `fieldId` / `dataset_field_bindings` 复用。
- 规则库能通过 `columnId` 找到具体物理列。

### 阶段 2：ClickHouse 导入

目标：上传后异步把数据写入 ClickHouse，并维护状态。

内容：

- 新增 ClickHouse 数据源配置：URL、库名、账号、密码、超时。
- 新建数据集专用库 `olist_datasets`。
- 上传完成后异步执行：
  1. 解析文件/压缩包/工作表。
  2. 推断类型，生成 ClickHouse 建表语句。
  3. 流式写入 ClickHouse。
  4. 校验行数。
  5. 更新状态为 `READY` 或 `FAILED`。
- 支持 CSV、TSV、ZIP、XLSX。
- ZIP 内每个可识别文件对应一张逻辑表。
- XLSX 每个 sheet 对应一张逻辑表。
- 失败可重试，重试前清理临时表。
- 以 `datasetFileId + tableId` 作为幂等键。
- 记录失败原因，前端可展示。

验收：

- 1GB 以内文件可以异步导入，不阻塞上传请求。
- 导入成功后 `SELECT count()` 与 profiler 统计一致。
- 导入失败不留下半成品表，原始文件仍可重新导入。
- 重复触发同一文件不会产生重复数据。

### 阶段 3：Catalog API 与查询切换

目标：规则库只通过 catalog 取表/列信息；数据集库自己的预览、分页、搜索切到 ClickHouse。

内容：

- 新增 catalog API，返回数据集状态、表、列、物理名、类型、行数。
- 预览改为 `SELECT ... LIMIT n`。
- 分页改为基于 `_row_id` 的稳定分页。
- 搜索改为 ClickHouse 条件查询；必要时对高频搜索列加 skip index。
- 导入期间保留文件 fallback，导入完成后走 ClickHouse。
- 只允许 `READY` 状态的数据集被规则库引用。

验收：

- 规则库不需要知道本地磁盘/S3 路径。
- 规则库不需要自己解析 CSV/XLSX。
- 预览和分页不再每次重新解析原始文件。

### 阶段 4：规则输出回写

目标：规则运行产生的清洗结果可以登记成新的数据集版本。

建议流程：

```text
规则库执行 OUTPUT
  -> 写出 Parquet 到对象存储
  -> 调数据集库登记接口（runId + URI + schema）
  -> 数据集库导入 ClickHouse
  -> 创建新的 dataset version / tables / columns
  -> 返回 datasetId + status
```

内容：

- 新增输出登记 API。
- 支持 `PARQUET` 和 `CSV` 两种输出格式。
- 用 `runId + nodeId` 做幂等键。
- 输出数据集与输入数据集分开，不覆盖原始数据。
- 隔离区数据单独存对象存储，登记 URI。

验收：

- 同一个运行重复登记不会创建重复数据集。
- 规则运行可以追溯到输入版本、输出版本和运行记录。
- 原始数据集不被修改。

### 阶段 5：生命周期、权限与测试

内容：

- 删除数据集/表时同步删除 ClickHouse 表。
- 删除失败可补偿，记录孤儿表。
- 支持从原始文件重建 ClickHouse 表。
- 规则库使用只读 ClickHouse 账号。
- 数据集服务使用读写账号。
- 集成测试覆盖：CSV、TSV、ZIP、XLSX、空值、重复列名、导入失败、重试、删除、catalog、输出登记。
- 用 Compose 的 ClickHouse 做集成测试。

验收：

- 删除数据集后 ClickHouse 不残留业务表。
- 历史运行输出不受源表删除影响。
- 只读账号无法执行 DDL/DML。

---

## 5. 建议的默认契约

以下契约如果规则库同学没有异议，数据集库按此实现。

### 5.1 存储边界

| 数据 | 存储 | 说明 |
| --- | --- | --- |
| 数据集/表/列元数据 | MySQL | 小数据、事务、状态、版本 |
| 规则/草稿/版本/运行 | MySQL | 规则库负责写入 |
| 原始上传文件 | 对象存储/本地 | source of truth，保留 |
| ClickHouse 数据表 | ClickHouse `olist_datasets` | 可重建的数据副本 |
| 运行输出 | 对象存储 + ClickHouse | 新数据集版本 |
| 隔离区 | 对象存储 | 保留 URI，避免大 JSON 入 MySQL |
| 预览样例/指标 | MySQL 小字段 + 对象存储 URI | 不存全量行数据 |

### 5.2 状态机

使用现有状态：

```text
EMPTY -> UPLOADED -> PROCESSING -> READY
                              \-> FAILED
```

- `EMPTY`：还没有文件。
- `UPLOADED`：文件已保存，尚未导入 ClickHouse。
- `PROCESSING`：正在导入。
- `READY`：可被规则库引用。
- `FAILED`：导入失败，可重试。

规则库发布或运行前应检查 `READY`。

### 5.3 表/列命名

- ClickHouse 库：`olist_datasets`。
- 一张 `dataset_tables` 对应一张 ClickHouse 表。
- 物理表名建议：`ds_{datasetId去横线}_{tableId去横线}`，避免前缀碰撞。
- 原始表名、列名保留在 MySQL 元数据中，用于展示。
- ClickHouse 标识符统一加反引号，允许中文、空格和特殊字符。
- 内部列：
  - `_row_id UInt64`：导入顺序，用于稳定分页。
  - 可选 `_ingested_at DateTime64(3, 'UTC')`。
- 规则库不应依赖以下划线开头的内部列。

### 5.4 类型映射建议

| 逻辑类型 | ClickHouse 类型 |
| --- | --- |
| STRING | `String` 或 `Nullable(String)` |
| INTEGER | `Int64` 或 `Nullable(Int64)` |
| FLOAT | `Float64` 或 `Nullable(Float64)` |
| DECIMAL | `Decimal(38, 6)` 或 `Nullable(Decimal(38, 6))` |
| BOOLEAN | `Bool` 或 `Nullable(Bool)` |
| DATE | `Date32` 或 `Nullable(Date32)` |
| DATETIME | `DateTime64(3, 'UTC')` 或 `Nullable(...)` |
| UNKNOWN | `Nullable(String)` |

默认规则：

- 空字符串在字符串列保留为空字符串。
- 空字符串在非字符串列按 NULL 处理。
- 日期时间统一按 UTC 存储，展示层再转时区。
- 金额默认 `Decimal(38, 6)`，如规则库有更精确要求再调整。
- 只有观测到 NULL 时才使用 `Nullable`，否则用非空类型。

### 5.5 Catalog API 建议

```http
GET /api/v1/datasets/{datasetId}/catalog
Authorization: Bearer <service-token>
```

```json
{
  "datasetId": "uuid",
  "status": "READY",
  "clickhouseDatabase": "olist_datasets",
  "tables": [
    {
      "tableId": "uuid",
      "tableName": "orders",
      "physicalTable": "ds_ab12cd34_ef56gh78",
      "rowCount": 99441,
      "columns": [
        {
          "columnId": "uuid",
          "name": "order_id",
          "physicalName": "order_id",
          "logicalType": "STRING",
          "clickhouseType": "String",
          "nullable": false,
          "ordinal": 0
        }
      ]
    }
  ]
}
```

待确认：

- 是否需要 `version`、`contentHash`、`format`、`sampleRows`。
- 是否需要按表返回独立状态。
- 是否需要返回 ClickHouse 连接信息，还是规则库配置固定连接。

### 5.6 输出登记 API 建议

```http
POST /api/v1/datasets/imports
Authorization: Bearer <service-token>
Content-Type: application/json
```

```json
{
  "sourceType": "PARQUET",
  "uri": "s3://olist-artifacts/runs/{runId}/output.parquet",
  "name": "订单清洗结果",
  "sourceRunId": "uuid",
  "idempotencyKey": "runId:nodeId",
  "schema": [
    {
      "name": "order_id",
      "logicalType": "STRING",
      "nullable": false
    }
  ]
}
```

返回：

```json
{
  "datasetId": "uuid",
  "status": "PROCESSING",
  "tables": []
}
```

规则库轮询 `status`，或由数据集库回调通知。

### 5.7 权限建议

- 数据集服务：ClickHouse 读写账号，负责建表、写入、删除。
- 规则库：ClickHouse 只读账号，只能 `SELECT`。
- 数据集库对外 API 使用服务间 token，不复用终端用户 token。
- 如果规则库不想直连 ClickHouse，可以由数据集库提供查询代理，但会增加一层网络开销。

### 5.8 版本语义（待确认）

当前实现：

- `dataset_versions` 是数据集主记录。
- `addFile` 会往同一条记录追加文件。
- 没有 `parentVersionId`、`versionNo`、内容哈希。

规则库如果要求运行冻结的 `datasetVersionId` 不可变，有两个方案：

- 方案 A（推荐）：`dataset_versions` 改为不可变快照；每次上传/追加生成新版本，旧版本保留。
- 方案 B：保持当前追加语义；规则运行额外冻结一份 manifest/hash，但这会让版本语义更复杂。

需要规则库同学明确：你们运行记录里的 `datasetVersionId` 是否假设不可变。

---

## 6. 需要规则库同学确认的问题

### 6.1 P0：会直接影响表结构和接口，建议优先回复

**P0-1. 执行时怎么访问数据？**

- 规则执行是直连 ClickHouse，还是调数据集服务 API？
- 如果直连：库名用 `olist_datasets` 还是 `olist_analytics`？账号由谁提供？
- 如果走 API：需要哪些查询接口？
- 规则侧拿到的是 `datasetVersionId + tableId + columnId`，还是物理表名/列名？

**P0-2. Catalog 字段够不够？**

- 上面 5.5 的结构是否满足规则编译和字段级联？
- 还需要哪些字段：`version`、`contentHash`、`format`、`status`、`sampleRows`、`primaryKey`？
- 是否需要一次性拉取整个数据集的 catalog 快照？

**P0-3. 输出回写谁负责？**

- 方案 A：规则侧产出 Parquet + schema + runId，数据集库导入 ClickHouse 并登记。
- 方案 B：规则侧直接写 ClickHouse，数据集库只登记元数据。
- 你们倾向哪个？如果选 B，ClickHouse 的表命名、建表、清理由谁负责？

**P0-4. `datasetVersionId` 是否要求不可变？**

- 当前 `addFile` 会追加到同一个数据集记录。
- 如果规则运行要求冻结快照，数据集库需要改成“每次上传/追加生成新版本”。
- 你们是否按不可变版本假设写规则和运行记录？

### 6.2 P1：重要，但可以稍后确认

**P1-1. 状态与就绪**

- 规则发布/运行是否必须等数据集 `READY`？
- 需要独立 readiness API，还是只读 `status` 字段？

**P1-2. 类型与空值**

- `String / Int64 / Float64 / Decimal / Date / DateTime64 / Bool` 是否够用？
- 空字符串和 NULL 怎么区分？
- 金额精度、日期时区怎么约定？
- CSV 是否只需要 UTF-8？GBK 要不要支持？

**P1-3. 预览与采样**

- 规则预览是数据集服务给样例，还是规则侧自己查 ClickHouse？
- 采样是随机、前 N 行还是按主键？
- `seed` 由谁生成和保存？

**P1-4. 表名/列名**

- 规则编译器能否处理带引号的中文、空格、特殊字符列名？
- 如果不能，规范名由谁生成？原始名怎么保留？
- 重复表头、空表头怎么处理？

**P1-5. 权限**

- 规则 worker 直连 ClickHouse 的只读账号、网络、库名怎么给？
- 是否需要数据集库代理查询？
- 服务间认证用什么 token？

**P1-6. 规模**

- 单表预计最大行数、列数、文件大小？
- 是否需要分区、`ORDER BY`、批量大小、async insert？
- 同时运行多少规则、多少用户？

**P1-7. 删除与历史**

- 删除数据集/表后，历史规则运行是否还要能查旧数据？
- 需要软删除、快照保留还是直接删除？
- 输出数据集被删除后，运行记录怎么处理？

**P1-8. 需要你们提供的材料**

- 一份最小 `PipelinePlan` JSON（CHECK / FILTER / OUTPUT）。
- 规则运行实体的字段定义（尤其是 `datasetVersionId`、`workbookVersionId`、`runId`、`nodeId`）。
- 规则编译器期望的 catalog 结构，或对应的 Java DTO / TypeScript 类型。
- 一个你们手头的测试数据集和期望清洗结果。

---

## 7. 你们可以问我们的

如果规则库同学对以下任何一点不清楚，可以直接问数据集库这边：

1. 当前上传支持哪些格式、最大文件多大、ZIP/XLSX 怎么拆表。
2. 列名、类型、空值、编码、重复表头怎么推断和处理。
3. ClickHouse 表怎么建、怎么命名、怎么删除、怎么重建。
4. 导入失败、重试、幂等、状态流转怎么设计。
5. `datasetVersionId` 现在到底是什么语义，后续会不会改成不可变版本。
6. 预览、分页、搜索在 ClickHouse 上怎么做，性能边界是什么。
7. Catalog API 的认证方式、调用方式、返回字段。
8. 规则输出如何登记成新数据集，URI 和 schema 怎么传。
9. 只读账号、服务账号、网络访问怎么配置。
10. 数据集删除、历史运行、隔离区数据的保留策略。

也欢迎直接指出：

- 你们期望的数据集接口和我们建议的不一致的地方。
- 你们已经写好的实体/接口，需要我们适配的部分。
- 你们认为会阻塞联调的点。

---

## 8. 回复模板

可以直接按下面格式回复，方便两边对齐：

```text
P0-1 执行访问方式：
P0-2 Catalog 是否够用 / 缺什么：
P0-3 输出回写方案（A/B）：
P0-4 datasetVersionId 是否不可变：
P1-1 状态与就绪：
P1-2 类型/空值/编码：
P1-3 预览与采样：
P1-4 表名列名：
P1-5 权限：
P1-6 规模：
P1-7 删除与历史：
P1-8 可提供的材料：
其他问题 / 需要数据集库适配的点：
```

---

## 9. 未确认前的默认假设

在收到 P0 回复前，数据集库按以下假设推进，不阻塞开发：

- ClickHouse 使用独立库 `olist_datasets`。
- 一张 `dataset_tables` 对应一张 ClickHouse 表。
- 规则库只读 ClickHouse，不负责建表和写入。
- 规则输出先写 Parquet，再由数据集库导入并登记。
- 数据集状态使用 `EMPTY / UPLOADED / PROCESSING / READY / FAILED`。
- 数据类型按 5.4 的映射，空值按 5.4 的规则处理。
- `datasetVersionId` 的不可变语义暂不改造，等 P0-4 确认后再决定是否引入版本链。
- 预览和分页优先走 ClickHouse，导入期间回退到文件解析。
- 原始文件永久保留，ClickHouse 表可从原始文件重建。

---

## 10. 非目标

- 不在数据集库实现业务清洗规则；清洗规则属于规则库。
- 不把数据集行数据写入 MySQL。
- 不开放任意 SQL/Python/JS 给普通用户。
- 不伪造规则运行结果；没有执行就没有结果。
- 不把前端表格布局当作执行计划。

---

## 11. 下一步

1. 规则库同学回复 P0 的 4 个问题。
2. 数据集库开始阶段 1（列级 schema）和阶段 2（ClickHouse 导入）。
3. 两边用一个小数据集联调 catalog + 查询。
4. 再确认输出回写和版本语义，补阶段 4。
5. 最后补删除、权限、集成测试和文档。

---

## 附录：相关文件

- `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/service/impl/DatasetServiceImpl.java`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/java/com/olist/datasets/profiler/impl/DefaultDatasetProfiler.java`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/resources/db/migration/`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend/dataset-service/src/main/resources/application.yml`
- `compose.yaml`
- `C:\Users\NJHTR\Desktop\RULE_LIBRARY_DESIGN.md`