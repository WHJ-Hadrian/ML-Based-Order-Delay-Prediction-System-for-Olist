# 规则执行 Worker 契约与当前实现

本文描述规则工作簿从“冻结计划”到“运行状态上报”的执行边界，以及 Python Worker 当前已经落地和尚未落地的部分。目标是让数据集服务、规则库和 Worker 三方只通过明确契约耦合，不互相读取对方内部表，也不伪造执行结果。

## 1. 当前结论

已实现：

- Worker 独立校验冻结的 `compiledPlan`，不直接相信 API 返回的计划一定可执行。
- 数据集版本一致性校验：运行、计划和每个 `TABLE` 输入必须指向同一个 `datasetVersionId`。
- execution snapshot 强类型校验：必须 `READY`、同一数据集版本、包含 manifest/schema 哈希、物理表列名安全、table/column/field ID 唯一。
- 节点 ID、节点类型、输出别名、输入别名、节点引用和边列表校验。
- 依赖图环检测、禁用节点依赖检测和确定性拓扑排序。
- `QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED` 编排与节点级状态上报。
- 节点执行异常、节点返回 `FAILED`、状态上报失败、成功结束被拒绝时，均不会伪造成功。
- `--validate-only` CLI，可在不启动运行的情况下检查冻结计划。
- `BackendGatedExecutionPreflight` 会先校验 READY snapshot，再在 ClickHouse 后端缺失时于 `start` 前 fail-closed，不把运行标记为成功。
- 数据集服务已提供 READY execution snapshot；Worker 通过 `HttpExecutionPreflight` 校验版本、哈希、数据库和物理列。
- 数据集服务已提供原子 claim、可续租 lease、`executionAttempt` fencing，以及仅允许持有有效 claim 的内部执行接口。
- `--consume-once` 和 `--consume` 可自动领取并执行排队运行；默认领取 PREVIEW 与 FULL，可按 worker pool 限定模式；空队列时按配置间隔等待，不忙轮询。
- 取消或租约 fencing 产生的内部 409 会被识别为 claim 失效；Worker 停止后续节点和失败状态补报，不把已取消运行误报为失败。
- PREVIEW 的 FAILED/CANCELLED 运行可通过新 run 安全重试；FULL 运行不能重试。

尚未实现：

- Parquet + manifest 输出登记。
- `GATE / OUTPUT` 及其他未列入预览支持清单的节点。
- JOIN 的 `KEEP_FIRST / KEEP_LAST / AGGREGATE` 重复处理策略；当前只准确执行 `FAIL`。
- Temporal workflow / activity 包装。

当前 Worker 可对 `CHECK / TRANSFORM / FILTER / PROJECT / DEDUPE / JOIN / AGGREGATE / DERIVE` 执行受限的 ClickHouse `PREVIEW`，也可以在 `FULL` 中执行这些关系节点并将 `OUTPUT: MATERIALIZE_VERSION + CSV` 登记为新数据集版本。它仍不是完整工作簿执行器：GATE、隔离分支、其他 OUTPUT 模式/格式、JOIN 的非 FAIL 重复策略和生产级断点恢复尚不支持。

服务端只允许为已支持节点创建 `PREVIEW` 或 `FULL` 运行；未指定 mode 时默认为 `PREVIEW`。PREVIEW 不允许启用 OUTPUT；FULL 必须包含 1 到 20 个 CSV `MATERIALIZE_VERSION` 输出节点，且只接受当前 Worker 支持的关系节点。每个输出最多 100,000 行，所有输出合计最多 256 MiB。FULL 必须通过带有效 claim 的队列消费者运行，过期租约会失败关闭且不可重试；输出写入和版本登记尚无跨进程稳定幂等键。

## 2. 分层边界

```text
RuleWorkbook / Sheet / Cell
        |
        | dataset-service compile + publish
        v
Frozen compiledPlan (logical IDs only)
        |
        | Worker plan validation + topological order
        v
ExecutionPreflight
  - verify dataset version READY
  - fetch immutable execution snapshot
        |
        v
ExecutionBackend
  - logical tableId/fieldId -> physical table/column
  - read-only ClickHouse preview query
  - metrics / bounded sample
  - (future) output manifest
        |
        v
RuleRun progress API
  - start / node reports / finish
```

编辑层、编译层和执行层不能混用：

- 前端单元格布局不进入 Worker。
- Worker 只执行冻结计划，不重新读取草稿。
- Worker 不根据显示名称猜物理列，必须通过 execution snapshot 解析。
- Worker 不直接写 `olist_datasets` 源表。

## 3. 冻结计划校验

Worker 会再次校验以下不变量：

| 不变量 | 说明 |
| --- | --- |
| `schemaVersion=1` | 只接受当前计划版本 |
| 数据集版本一致 | `compiledPlan.datasetVersionId` 等于运行的 `datasetVersionId` |
| 输入版本一致 | 每个 `TABLE` 输入的 `datasetVersionId` 必须相同 |
| 节点 ID 唯一 | 防止上报到错误节点 |
| 节点类型受支持 | 只接受 `CHECK / TRANSFORM / PROJECT / FILTER / DEDUPE / JOIN / AGGREGATE / DERIVE / GATE / OUTPUT` |
| 输出别名唯一且安全 | 必须匹配 `[A-Za-z_][A-Za-z0-9_]*` |
| 输入别名唯一且安全 | 避免后续 SQL 标识符歧义 |
| 引用存在 | 显式依赖、`NODE_OUTPUT`、`GATE.metricRef` 必须能解析 |
| `OUTPUT` 是终态 | 其他节点不能依赖 `OUTPUT` |
| 边列表一致 | 计划中的 `edges` 必须与节点引用推导出的依赖完全一致 |
| 无环 | 依赖图必须可拓扑排序 |
| 禁用依赖 | 启用节点不能依赖禁用节点 |

校验失败时 Worker 不会调用 `start`，运行保持 `QUEUED`，由调用方修复计划或数据版本后重新投递。

## 4. 执行顺序

执行顺序只由依赖图决定，不由 Sheet 行号决定。拓扑排序在入度相同的节点之间保持计划原始顺序，保证同一计划重复执行时顺序稳定。

单个节点流程：

```text
disabled? -> report SKIPPED
otherwise:
  report RUNNING
  backend.execute(node, preparedExecution)
  report SUCCEEDED / FAILED / SKIPPED
```

只有当全部启用节点都上报 `SUCCEEDED` 或 `SKIPPED` 后，Worker 才尝试 `finish SUCCEEDED`。任一节点失败或抛出异常，Worker 停止后续节点并尝试 `finish FAILED`。若 claim 已取消、过期或被新 attempt 取代，服务端 409 被视为 fencing 信号，Worker 不再尝试写入 `FAILED`，并在本地返回 `CANCELLED`。

## 5. 失败关闭语义

以下情况不会产生成功运行：

- 冻结计划非法。
- 数据集版本不是 `READY`，或 execution snapshot 不可用。
- 节点执行器抛出异常。
- 节点返回 `FAILED`。
- 节点状态上报失败。
- `finish SUCCEEDED` 被拒绝或返回非成功状态。

上报失败时会尽量再尝试 `finish FAILED`。如果连失败结束也无法上报，Worker 会抛出错误，让 Temporal 或上层任务系统重试并暴露问题。

自动消费者只会恢复租约已过期的 PREVIEW 运行。新 claim 会增加 `executionAttempt`、清除旧 attempt 的节点上报并从头重跑；旧 claim token/attempt 随即失效，不能再续租、读取快照、上报或结束运行。当前 PREVIEW 后端只读且不登记输出，因此从头重跑不会重复物化数据。

用户取消只允许作用于 `QUEUED / RUNNING` 运行，服务端原子设置 `CANCELLED` 并清除 claim/lease；运行中的节点被置为 `CANCELLED`，尚未开始的节点标为 `SKIPPED`。取消不会即时终止 ClickHouse 中正在执行的只读查询：该查询受配置的 `max_execution_time` 限制，返回后 Worker 的节点上报会因 fencing 被拒绝，随后停止，不再调度后续节点。

FAILED/CANCELLED 的 PREVIEW 运行可安全重试。重试会创建新 run，保留原运行冻结的工作簿版本、数据集版本、样本量和 seed，并记录 `retryOfRunId`；数据集版本必须仍为 READY。FULL 运行明确禁止重试，待输出幂等物化契约落地后再设计恢复语义。

FULL 运行不能直接采用这一恢复策略。后续接入隔离区、中间结果或数据集版本物化前，必须为输出和登记操作提供稳定幂等键，并定义取消、断点恢复或安全重跑语义。

## 6. execution snapshot 契约（已实现）

真实执行前，数据集服务必须提供不可变快照。建议至少返回：

```json
{
  "datasetVersionId": "uuid",
  "status": "READY",
  "manifestHash": "sha256:...",
  "schemaHash": "sha256:...",
  "clickhouseDatabase": "olist_datasets",
  "rowCount": 12345,
  "tables": [
    {
      "tableId": "uuid",
      "tableKey": "orders",
      "tableName": "orders",
      "physicalTable": "t_...",
      "rowCount": 1000,
      "columns": [
        {
          "columnId": "uuid",
          "fieldId": "uuid",
          "sourceName": "order_id",
          "physicalName": "c001",
          "logicalType": "STRING",
          "clickhouseType": "Nullable(String)",
          "nullable": true
        }
      ]
    }
  ]
}
```

要求：

- 快照只能对应一个 `datasetVersionId`，`READY` 后不可变。
- v1 源列统一为 `Nullable(String)`；类型推断和 FLOAT/DATE 映射后续再做。
- 物理表名和物理列名只出现在 snapshot，不出现在逻辑 Catalog。
- Worker 缓存 key 使用 `datasetVersionId + schemaHash`。
- 运行创建、发布和启动前都应通过数据集服务校验 `READY`，不要各自查库。
- snapshot 获取失败或哈希不一致时，Worker 必须停止，不得回退到按名称猜测。

## 7. ClickHouse 预览后端

当前已实现的 `ClickHousePreviewBackend`：

- 支持 opt-in 的 `PREVIEW`，以及受限的 `FULL` CSV 版本物化；两种模式均通过相同的只读 ClickHouse 查询限制执行转换，FULL 仅在最终输出阶段通过受控上传登记新版本。
- 只执行 `CHECK / TRANSFORM / FILTER / PROJECT / DEDUPE / JOIN / AGGREGATE / DERIVE`；其他节点明确拒绝。
- 所有表名、列名从 READY snapshot 解析，操作符和函数来自静态白名单，用户值以 ClickHouse 参数传递。
- 查询使用 `readonly=1`，并配置 `max_rows_to_read`、`max_bytes_to_read`、`max_execution_time`。
- 使用冻结的 `sampleSize + seed` 选择确定性预览源样本；样本量必须在 1 到 5000。
- `QUARANTINE_ROWS` 目前只在预览指标中报告命中数，并把未命中行作为主输出，不落盘隔离分支。
- DEDUPE `FIRST`/`LAST` 依据稳定 hash 选择，因 snapshot 尚无源行序，不代表原始文件顺序或“最新”。
- JOIN 只消费两个带安全别名的输入，键必须由 snapshot 中的稳定列/字段 ID 或无歧义名称解析；不同逻辑类型不会隐式强转。
- JOIN 支持 `INNER / LEFT / RIGHT / FULL`，并设置 `join_use_nulls=1`，避免外连接缺失侧被 ClickHouse 默认值伪装成真实值。
- JOIN 在生成结果前统计左/右样本行数、匹配左行数、左右重复键组、右键最大匹配倍数，验证 `1:1 / 1:N / N:1 / N:N` 声明和 `rightKeyUnique / maxFanout / minMatchRate` guards。
- guards 的 `onViolation=FAIL_RUN` 会阻断节点；`WARN` 会把信息写入 `guardWarnings` 后继续。无论配置如何，最大 fanout 超过 1000 都会失败关闭。
- JOIN 当前只执行 `duplicatePolicy=FAIL`；`KEEP_FIRST / KEEP_LAST / AGGREGATE` 明确拒绝，不能伪造去重或聚合已发生。
- AGGREGATE 支持最多 100 个分组字段和 100 个指标，指标操作白名单为 `COUNT / COUNT_DISTINCT / SUM / AVG / MIN / MAX / MEDIAN`。
- AGGREGATE 的 `HAVING` 只能使用结构化 Predicate 引用指标输出，不能引用分组字段；显示名称不会成为 SQL 标识符，比较值仍使用参数绑定。
- 指标 `params` 目前仅接受缺省或空对象，任何非空参数都会明确拒绝；不接受前端 SQL、函数名或可执行表达式。
- 数值聚合要求逻辑类型与 ClickHouse 物理类型兼容。字符串物理列不会为了聚合被隐式转成 Float64；DECIMAL 的 `SUM / AVG / MEDIAN` 只有在物理列本身为 ClickHouse Decimal 时才执行，以避免精度丢失。
- DERIVE 使用结构化表达式树，支持字段、常量、日期差/日期拆解、拼接、空值合并、条件分支、算术和球面距离；操作符、日期部分和底层 ClickHouse 函数均来自 Worker 白名单。
- DERIVE 常量全部参数化，输出字段使用合成物理名，表达式最多 20 层、200 个节点、单个可变参数操作最多 50 项；不接受 SQL、脚本、外部函数或前端指定函数名。
- DERIVE 只有在 `replaceExisting=true` 时才覆盖同名字段；除法/取模的除数为 0 时返回空值。字符串物理列只有在 snapshot 已声明对应逻辑类型时才进行明确的可空类型解析。

ClickHouse 账号还必须由运维配置为只读；查询的 `readonly=1` 是额外限制，不替代数据库权限配置。预览结果只回传至多 25 行样例，不登记新数据集版本，也不写源表。

后续完整执行仍需增加：JOIN 去重策略、隔离区与中间结果物化，Parquet + manifest 写入对象存储和数据集服务登记，稳定幂等输出键与恢复策略。

## 8. 控制面接口

手动诊断和兼容路径使用用户 API：

```text
GET  /api/v1/rule-runs/{runId}/execution
POST /api/v1/rule-runs/{runId}/start
PUT  /api/v1/rule-runs/{runId}/nodes/{nodeId}
POST /api/v1/rule-runs/{runId}/finish
```

自动消费者使用仅对服务开放的内部 API：

```text
POST /internal/rule-runs/claim
POST /internal/rule-runs/{runId}/lease
GET  /internal/rule-runs/{runId}/execution
GET  /internal/rule-runs/{runId}/execution-snapshot
POST /internal/rule-runs/{runId}/start
PUT  /internal/rule-runs/{runId}/nodes/{nodeId}
POST /internal/rule-runs/{runId}/finish
```

内部请求必须携带 `X-Rule-Worker-Token`；领取后的请求还必须携带 `X-Rule-Claim-Token` 和 `X-Rule-Execution-Attempt`。`RULE_WORKER_INTERNAL_TOKEN` 为空时内部 API 返回 503，错误令牌返回 401。普通用户 API 不能操作已被 Worker claim 的运行。

用户运行控制另提供 `POST /api/v1/rule-runs/{runId}/cancel` 与 `POST /api/v1/rule-runs/{runId}/retry`。重试只接受 FAILED/CANCELLED 的 PREVIEW 运行，且每次生成新的运行 ID；它不会重放或修改原运行记录。

上报节点时 `nodeKind` 必须是冻结计划中的类型，`status` 只使用 `RUNNING / SUCCEEDED / FAILED / SKIPPED`。终态不可改写。

## 9. 并发与幂等

领取通过条件更新完成，不依赖先查询后启动的竞态窗口；多个 Worker 竞争同一候选时只有一个能获得 claim。租约范围为 15 到 300 秒，Worker 默认每约三分之一租期续租。claim token、execution attempt 和未过期租约共同构成 fencing 条件。

运行进入终态时会清空当前 claim。因而同一 claim 重复调用内部 `finish` 当前返回冲突，而不是幂等 200；调用方应把第一次成功响应视为完成。节点状态的业务规则仍防止终态被改写，但未来 FULL 输出必须另外使用稳定幂等键，不能只依赖控制面 fencing。

## 10. 本地验证

运行 Python 测试：

```powershell
cd ML-Based-Order-Delay-Prediction-System-for-Olist-backend
python -m pytest -q
```

只校验冻结计划、不启动运行：

```powershell
python -m app.workers.rule_execution `
  --run-id <run-uuid> `
  --base-url http://localhost:8082 `
  --token <bearer-token> `
  --validate-only
```

校验 READY execution snapshot（不启动运行）：

```powershell
python -m app.workers.rule_execution `
  --run-id <run-uuid> `
  --base-url http://localhost:8082 `
  --token <bearer-token> `
  --check-snapshot
```

普通执行默认仍关闭。只有设置 `OLIST_RULE_EXECUTION_ENABLED=true` 才会打开 ClickHouse 预览；关闭时 Worker 会在 `start` 前 fail-closed，snapshot 校验保持可用。

运行一个自动消费周期：

```powershell
$env:OLIST_RULE_EXECUTION_ENABLED = "true"
$env:OLIST_RULE_RUN_API_TOKEN = "<RULE_WORKER_INTERNAL_TOKEN>"
python -m app.workers.rule_execution --consume-once --worker-id local-preview-worker
```

持续消费使用 `--consume`。Compose 启动时必须同时启用应用和 Worker profile，并为共享令牌设置非空随机值：

```powershell
$env:RULE_WORKER_INTERNAL_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
docker compose --profile apps --profile rule-worker up --build -d
```

`rule-preview-worker` 使用独立的 `olist_rule_reader` ClickHouse 账户；初始化脚本仅授予 `olist_datasets.*` 的 `SELECT` 并设置 `readonly=1`。查询本身还会重复请求 readonly 和资源限制。修改初始化账户配置后，如复用既有 ClickHouse volume，需要手动迁移账户或重建本地开发 volume，初始化脚本不会在已有数据目录上重新执行。

## 11. 解阻塞清单

1. 已提供 READY 校验、execution snapshot、原子 claim、lease 续租和 attempt fencing。
2. 已实现 `HttpExecutionPreflight`、只读 ClickHouse PREVIEW 后端和自动队列消费者。
3. 扩展 ClickHouse 编译器，补 JOIN 重复处理策略、GATE 及其他计划节点。
4. 实现隔离分支、Parquet + manifest 输出和数据集版本登记。
5. 为 FULL 输出定义稳定幂等键、取消和安全恢复，再接入 Temporal workflow/activity。
6. 补真实 Compose 环境中的 ClickHouse 权限、Worker 领取、CSV 输出登记、租约过期和端到端执行测试。

对应实现位置：

- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/plan.py`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/runner.py`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/client.py`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/ports.py`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/snapshot.py`
