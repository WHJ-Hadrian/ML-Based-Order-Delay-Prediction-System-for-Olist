# 规则工作簿设计契约

本目录定义 Olist 数据平台规则库模块的领域契约。规则库不是一张规则表的 CRUD，而是一个“可编排的规则工作簿 + 可编译的节点图 + 可追溯的运行记录”。

## 1. 设计目标

- 用户可以在类似工作表的界面中编排规则和处理步骤。
- 支持数据集、表、字段、表关系、节点输出等引用。
- 支持质量检查、清洗、关联、聚合、派生、抽样、门禁和输出。
- 支持草稿编辑、校验、试跑、发布版本和全量运行。
- 规则发布后不可变，运行必须可复现。
- 前端布局不直接成为执行模型，执行以编译后的节点图为准。

## 2. 非目标

- 不把任意 SQL、Python 或 JS 代码直接作为普通规则开放。
- 不允许一个运行同时隐式混合多个数据集版本。
- 不把模型的算法选择、超参数和训练策略混入数据规则库。
- 不把 Excel 的公式、合并单元格、自由绘图等能力当作执行语义。

## 3. 分层架构

```text
编辑层
  RuleWorkbook -> Sheet -> Column -> Cell
       |
       | compile + validate
       v
语义层
  RuleNode: CHECK / TRANSFORM / FILTER / PROJECT / DEDUPE / JOIN /
            AGGREGATE / DERIVE / GATE / OUTPUT
       |
       | compile
       v
执行层
  PipelinePlan -> DuckDB / Polars / Spark adapter
       |
       v
结果层
  Cleaned Dataset Version + Quality Report + Quarantine + Lineage
```

三层之间必须是显式边界：

- 编辑层只负责“用户怎么填”。
- 语义层负责“这条规则到底是什么意思”。
- 执行层负责“如何算出来”。

绝不能把单元格 JSON 直接交给执行引擎，也不能让执行引擎理解 UI 的行列布局。

## 4. 核心概念

| 概念 | 说明 |
| --- | --- |
| `RuleWorkbook` | 一个可命名的规则库，例如“订单训练数据清洗” |
| `Sheet` | 工作簿中的一张工作表，例如“字段检查”“多表关联” |
| `Column` | 有语义类型的列定义，例如数据集选择器、字段选择器 |
| `Row` | 一条用户编排的规则或处理步骤，编译后对应一个节点 |
| `Cell` | 行与列的交叉值，值必须是该列声明的类型 |
| `RuleNode` | 编译后的规范节点，是执行的唯一输入 |
| `DataRef` | 对数据集、表、字段、节点输出的稳定引用 |
| `Predicate` | 条件树，例如为空、重复、范围、日期顺序 |
| `Operation` | 数据变换，例如关联、聚合、过滤、派生 |
| `Effect` | 校验命中后的结果，例如隔离、删除、阻断 |
| `Policy` | 阻断策略和错误处理策略 |
| `PipelinePlan` | 编译后的有向节点图 |
| `RuleRun` | 一次冻结输入数据集版本和规则版本后的运行实例 |

## 5. 工作簿与数据集的绑定

第一版建议每个工作簿绑定一个明确的数据集版本：

```text
RuleWorkbook.datasetVersionId = 数据集版本
```

原因：

- 一次运行只能有一个主输入上下文。
- 字段引用和表引用需要稳定的 schema。
- 如果允许行内随意选择不同数据集，跨表规则和行级规则会失去明确的执行语义。

需要跨表时，使用同一个数据集版本内的显式 `source alias` 和关联节点，不靠隐式混合。节点输入既可以是原始表，也可以是上游节点的 `NODE_OUTPUT`；编译器会把输入别名写入计划，字段解析优先使用 `columnId` / `fieldId`，再回退到字段绑定所属表。

后续可引入 `DatasetContract`：规则工作簿绑定数据契约，运行再绑定具体数据集版本。这样可以实现规则跨版本复用，但第一版不强制。

## 6. 生命周期

```text
DRAFT -> VALIDATED -> PUBLISHED -> DEPRECATED
```

- `DRAFT`：当前编辑态，允许修改。
- `VALIDATED`：结构和参数通过校验，但尚未发布。
- `PUBLISHED`：不可变快照，可以运行。
- `DEPRECATED`：不再新建运行，但历史运行仍可追溯。

修改已发布版本时必须复制成新的草稿，不能原地修改。

## 7. 编译流程

```text
Draft Document
  -> Schema Validation
  -> Reference Validation
  -> Type Compatibility Validation
  -> Dependency / Cycle Validation
  -> Compile to RuleNode Graph
  -> Compile to Engine Plan
  -> Preview or Run
```

必须校验：

- 数据集版本、表、字段是否仍然存在。
- 字段类型和条件操作符是否兼容。
- 行级效果是否错误地用于聚合条件。
- 关联键是否存在、类型是否兼容。
- 右表键重复和 fanout 风险。
- 节点输出别名是否冲突。
- 依赖是否成环。
- 节点是否读取了尚未生成的输出。

预览和正式运行必须使用同一个编译器。

## 8. 存储模型

建议把编辑文档和运行计划分开存：

| 表 | 作用 |
| --- | --- |
| `rule_workbooks` | 工作簿元数据、所有者、绑定数据集、状态 |
| `rule_workbook_drafts` | 当前草稿文档 JSON 和修订号 |
| `rule_workbook_versions` | 已发布版本快照、编译计划、校验和 |
| `rule_runs` | 运行实例、输入数据集版本、工作簿版本、模式、状态 |
| `rule_run_nodes` | 每个节点的运行结果、指标、样例地址 |
| `table_relationships` | 规划中：逻辑表关系、关联键、基数；当前 JOIN 直接在规则行中配置 |
| `quality_reports` | 规划中：汇总质量报告和报告文件地址 |
| `dataset_field_bindings` | 数据集版本中字段与字段库、语义角色的绑定 |

编辑文档使用文档模型而不是一单元格一行，原因：

- 工作表布局变化频繁，文档快照更容易版本化。
- 单元格类型和参数 schema 由前端/后端注册表控制，不需要为每种规则建表。
- 发布时编译成标准节点，执行层不依赖 UI 结构。

如果需要按单元格查询，可在后续增加投影表或搜索引擎，不改变主文档模型。

## 9. 已实现 API

```text
GET    /api/v1/rule-workbooks
POST   /api/v1/rule-workbooks
GET    /api/v1/rule-workbooks/{id}
PUT    /api/v1/rule-workbooks/{id}/draft
POST   /api/v1/rule-workbooks/{id}/validate
POST   /api/v1/rule-workbooks/{id}/publish
POST   /api/v1/rule-workbooks/{id}/runs
GET    /api/v1/rule-workbooks/{id}/runs
GET    /api/v1/rule-runs/{runId}
GET    /api/v1/rule-runs/{runId}/execution
POST   /api/v1/rule-runs/{runId}/start
POST   /api/v1/rule-runs/{runId}/finish
PUT    /api/v1/rule-runs/{runId}/nodes/{nodeId}
GET    /api/v1/rule-runs/{runId}/nodes
GET    /api/v1/rule-workbooks/{id}/versions
GET    /api/v1/rule-workbooks/{id}/versions/{version}
GET    /api/v1/datasets/{id}/columns
GET    /api/v1/fields/catalog?datasetVersionId={id}
POST   /api/v1/fields/import
```

运行只使用已发布版本。工作簿已绑定 `datasetVersionId`，因此运行请求不需要再次传入数据集版本。

```json
{
  "workbookVersionId": "...",
  "mode": "PREVIEW",
  "sampleSize": 1000,
  "seed": 42
}
```

当前草稿文档的表结构是 `RuleWorkbook -> Sheet -> Column -> Cell`；校验通过后编译成节点图。默认提供 10 张工作表：`field_checks`、`transforms`、`filters`、`projects`、`dedupes`、`relations`、`aggregates`、`derived`、`gates`、`outputs`。

### 9.1 运行控制面

运行状态机：

```text
QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED
```

- 创建运行后固定为 `QUEUED`，由 Worker 调 `start` 进入 `RUNNING`。
- `execution` 返回创建时冻结的 `workbookVersionContentHash` 和 `compiledPlan`，Worker 只能执行这份计划。
- 节点 ID 必须存在于冻结计划中；上报的节点类型必须与计划一致。
- 节点状态为 `PENDING -> RUNNING -> SUCCEEDED / FAILED / SKIPPED`，进入终态后不可改写。
- 只有当冻结计划中的全部启用节点都处于 `SUCCEEDED` 或 `SKIPPED` 时，运行才能结束为 `SUCCEEDED`。
- `finish` 只接受 `SUCCEEDED / FAILED / CANCELLED`，相同终态重复上报是幂等的。
- 服务端不会自动把运行标记为成功；`SUCCEEDED` 只能由 Worker 显式上报。
- 前端只读展示运行历史、计划节点进度和上报结果，不向普通用户暴露 `start / finish` 特权操作。

Python Worker 已实现独立的计划二次校验、强类型 READY execution snapshot（HTTP 接口与 `HttpExecutionPreflight` 已接通）、拓扑排序、运行控制和节点上报编排；真实 ClickHouse 执行仍待落地。Worker 会先校验 snapshot，再在后端缺失时于 `start` 前 fail-closed，不会调用 `start` 伪造成功。详见 `worker-execution.md`。

## 10. 与现有服务的职责

- `dataset-service`：工作簿、字段库、表关系、草稿、版本、校验和编译。
- `job-service`：通过 Temporal 启动预览和全量运行，管理运行状态。
- Python Worker：执行编译后的计划；当前已落地计划校验、READY snapshot 校验和运行编排，ClickHouse 执行后端仍待落地。
- 对象存储：清洗结果、隔离区样例、质量报告。
- MySQL：工作簿、版本、运行和汇总指标。
- ClickHouse：后续的质量趋势和跨运行分析。

第一版不要为规则库单独拆微服务；先在 `dataset-service` 内建立清晰的 `rules` 领域包，等边界稳定后再评估拆分。

## 11. 实施顺序

1. 补列级数据集探测和字段库绑定。
2. 定义节点、单元格和参数 schema 注册表。
3. 实现工作簿草稿、校验、编译和版本发布。
4. 已实现单表 CHECK、TRANSFORM、FILTER、PROJECT、DEDUPE 的编译和校验。
5. 已实现 JOIN、AGGREGATE、DERIVE、GATE、OUTPUT 的编译和校验。
6. 已实现运行控制面（start / 节点上报 / finish）和 Python Worker 的校验/编排骨架；待 execution snapshot、ClickHouse 后端和 Temporal activity 接入。
7. 待输出质量报告、隔离区、血缘和运行历史。
8. 后续增加 DAG 可视化、定时监控、受控自定义节点和跨数据集版本复用。

## 12. 关联文档

- `nodes.md`：节点类型、引用、条件、效果和策略。
- `cells.md`：列定义、单元格类型、级联和校验。
- `schemas.md`：每类节点的参数 schema。
- `olist-order-cleaning.example.yaml`：Olist 完整示例。
- `worker-execution.md`：计划校验、运行编排、失败关闭和 ClickHouse 接入前置契约。

## 13. 当前实现边界

已落地：

- 字段目录（数据集名称、表名称、列名称、物理类型）按数据集版本导入和幂等去重。
- 规则工作簿草稿、Excel 风格单元格、系统列和自定义管理列。
- 输入可以是原始表或上游节点输出；字段引用保存 `fieldId` / `columnId` / `nameSnapshot` / `tableId`。
- 10 类节点的编译、引用校验、别名检查、依赖边生成和环检测。
- 发布不可变版本；运行记录冻结工作簿版本和数据集版本。
- 运行控制面：冻结计划读取、`QUEUED -> RUNNING -> 终态` 状态机、节点级状态/指标/样例上报、终态防改写和幂等结束。
- Python Worker 核心：冻结计划二次校验、数据集版本一致性、依赖环检测、稳定拓扑排序、节点状态上报、失败即结束和 `--validate-only` CLI。
- 前端提供字段库、规则库、规则工作表、条件编辑、参数 JSON 编辑、字段级联、校验、发布和预览运行入口。
- 前端提供运行历史、运行详情、冻结计划节点进度、节点指标/样例/错误查看，并在运行未结束时每 3 秒自动刷新。

尚未完成：

- 尚未接入 Temporal；Python Worker 已有控制面骨架，但 execution snapshot、ClickHouse 执行后端和输出登记尚未落地，因此真实运行不会自动执行。
- 逻辑表关系库尚未独立成表，JOIN 仍在规则行内直接选择左右输入和键。
- 字段定义跨数据集版本的业务身份复用尚未启用；当前按数据集版本导入会创建新的字段绑定。
- GATE 的 `metricRef` 目前是文本输入；后续应改为上游指标下拉，并在发布前校验指标确实存在。
- 节点输出的字段 schema 传播尚未实现；当前字段校验以当前数据集版本的物理列为准，执行引擎接入前需要补上“投影/聚合后可见字段”校验。
