# 规则执行 Worker 契约与当前实现

本文描述规则工作簿从“冻结计划”到“运行状态上报”的执行边界，以及 Python Worker 当前已经落地和尚未落地的部分。目标是让数据集服务、规则库和 Worker 三方只通过明确契约耦合，不互相读取对方内部表，也不伪造执行结果。

## 1. 当前结论

已实现：

- Worker 独立校验冻结的 `compiledPlan`，不直接相信 API 返回的计划一定可执行。
- 数据集版本一致性校验：运行、计划和每个 `TABLE` 输入必须指向同一个 `datasetVersionId`。
- execution snapshot 强类型校验：必须 `READY`、同一数据集版本、包含 manifest/schema 哈希、物理表列名安全、table/column/field ID 唯一。
- 节点 ID、节点类型、输出别名、输入别名、节点引用和边列表校验。
- 依赖图环检测、禁用节点依赖检测和确定性拓扑排序。
- `QUEUED -> RUNNING -> SUCCEEDED / FAILED` 编排与节点级状态上报。
- 节点执行异常、节点返回 `FAILED`、状态上报失败、成功结束被拒绝时，均不会伪造成功。
- `--validate-only` CLI，可在不启动运行的情况下检查冻结计划。
- `BackendGatedExecutionPreflight` 会先校验 READY snapshot，再在 ClickHouse 后端缺失时于 `start` 前 fail-closed，不把运行标记为成功。
- 数据集服务已提供 READY execution snapshot；Worker 通过 `HttpExecutionPreflight` 校验版本、哈希、数据库和物理列。

尚未实现：

- ClickHouse 只读查询和节点 SQL 编译。
- Parquet + manifest 输出登记。
- Temporal workflow / activity 包装。
- 多 Worker 抢占同一运行的 lease 机制。

因此当前 Worker 是“控制面 + 校验 + 编排骨架”，不是可执行真实清洗任务的生产执行器。

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
  - read-only ClickHouse query
  - metrics / sample / output manifest
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

只有当全部启用节点都上报 `SUCCEEDED` 或 `SKIPPED` 后，Worker 才尝试 `finish SUCCEEDED`。任一节点失败或抛出异常，Worker 停止后续节点并尝试 `finish FAILED`。

## 5. 失败关闭语义

以下情况不会产生成功运行：

- 冻结计划非法。
- 数据集版本不是 `READY`，或 execution snapshot 不可用。
- 节点执行器抛出异常。
- 节点返回 `FAILED`。
- 节点状态上报失败。
- `finish SUCCEEDED` 被拒绝或返回非成功状态。

上报失败时会尽量再尝试 `finish FAILED`。如果连失败结束也无法上报，Worker 会抛出错误，让 Temporal 或上层任务系统重试并暴露问题。

Worker 不会自动恢复 `RUNNING` 运行。原因是当前控制面没有节点级 resume 语义，直接重跑可能造成重复写入或错误覆盖。后续如要恢复，必须先补：

1. 查询已上报节点状态。
2. 只重跑非终态节点。
3. 为输出和中间物化提供幂等键。

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

## 7. ClickHouse 执行后端职责

`ExecutionBackend` 的实现应满足：

- 使用只读账号 `olist_rule_reader` 读取 `olist_datasets`。
- 不直接写 `olist_datasets`。
- 复杂 DAG 中间物化如确需落盘，只能写 `olist_rule_staging`，并遵守数据集服务定义的生命周期。
- 预览运行使用冻结的 `sampleSize` 和 `seed`，不能每次随机。
- 所有节点输入、字段和类型必须来自 execution snapshot。
- 输出写 Parquet + manifest 到对象存储，再调用数据集服务输出登记接口；不得直接登记或写入源数据集。
- 输出 manifest 至少包含表、列、逻辑类型、ClickHouse 类型、行数、`manifestHash`、`schemaHash`、`sourceRunId`、`sourceWorkbookVersionId`、`parentDatasetVersionId`。
- 同一运行重复登记使用幂等键，不能重复创建版本。

## 8. 控制面接口

Worker 当前使用：

```text
GET  /api/v1/rule-runs/{runId}/execution
GET  /internal/dataset-versions/{datasetVersionId}/execution-snapshot
POST /api/v1/rule-runs/{runId}/start
PUT  /api/v1/rule-runs/{runId}/nodes/{nodeId}
POST /api/v1/rule-runs/{runId}/finish
```

上报节点时 `nodeKind` 必须是冻结计划中的类型，`status` 只使用 `RUNNING / SUCCEEDED / FAILED / SKIPPED`。终态不可改写。

## 9. 并发与幂等

当前控制面没有“领取运行”的 lease token。两个 Worker 同时看到 `QUEUED` 都可能成功调用 `start`。生产接入时必须满足至少一项：

- Temporal workflow ID 固定为 `rule-run-{runId}`，依赖 Temporal 去重。
- 数据集服务增加 worker lease / claim token 和过期时间。
- 运行增加 `executionAttempt` 或 fencing token，节点上报必须携带。

在 lease 落地前，不要把当前 Worker 部署成多个并发消费者直接消费同一批运行。

节点上报本身是幂等的：重复相同终态返回成功；终态改成其他状态返回冲突。但业务输出仍需要自己的幂等键。

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

执行入口在 ClickHouse 后端接入前仍会 fail-closed；snapshot 校验已接通。

## 11. 解阻塞清单

1. 数据集服务提供 `READY` 校验接口。
2. 数据集服务已提供 execution snapshot，返回物理表/列、逻辑类型、ClickHouse 类型、哈希。
3. 已实现 `HttpExecutionPreflight`，通过 `ExecutionSnapshot.from_payload` 解析 snapshot；缓存后续再加。
4. 实现 `ClickHouseExecutionBackend` 和 10 类节点的 SQL 生成。
5. 接入 `clickhouse-connect`，配置只读账号和查询超时。
6. 实现样例行、指标、错误上报。
7. 实现 Parquet + manifest 输出和输出登记。
8. 加 Temporal workflow，固定 workflow ID，activity 只传 `runId`。
9. 补成功、节点失败、超时、取消、重试和输出幂等集成测试。

对应实现位置：

- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/plan.py`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/runner.py`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/client.py`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/ports.py`
- `ML-Based-Order-Delay-Prediction-System-for-Olist-backend/app/workers/rule_execution/snapshot.py`
