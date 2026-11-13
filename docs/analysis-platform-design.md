# 通用分析平台设计（最终方案）

> 目标：把「延误分析」和「可视化分析」从两个写死的页面，收敛成**一套可配置的分析平台**：
> 用户选数据 → 选指标 → 选维度 → 加筛选 → 选图表 → 保存方案 → 导出报告。
> 延误分析不再是一套独立代码，而是平台里的一个**系统模板**。

本文是目标态设计，不是过渡方案；实现顺序在最后，但每一层都是最终形态，不需要推翻重来。

---

## 1. 目标与非目标

### 目标

1. **一套引擎**：指标（度量）+ 维度 + 筛选 + 时间粒度 + 排序/限制 → 一次聚合查询。
2. **语义层**：指标有名字、口径、格式（如「延误率 = 延误单 ÷ 订单量」），用户选指标而不是写 SQL。
3. **方案（Recipe）可保存、可版本化、可复现**：同一个方案 + 同一个数据版本 → 同一份结果。
4. **模板**：延误分析、订单分布、评分影响等作为系统模板，用户「复制为我的方案」后自由改。
5. **统一的展示与交付**：表格/柱状/折线/散点/KPI + 报告（PDF/Word/HTML）+ CSV。
6. **安全**：不开放自由 SQL；标识符只取数据快照，值参数化，所有算子白名单。

### 非目标

- 不做自助 ETL/数据准备（那属于「数据处理」模块，已有节点式工作流）。
- 不做拖拽式任意 SQL、不做实时流分析。
- 不在本期做多人协同编辑（方案按 owner 隔离，分享留扩展点）。

---

## 2. 总体架构

```
                    ┌──────────────────────────────┐
   浏览器 ──▶ API 网关（8080，JWT）                    │
                    └───────┬──────────────┬───────┘
                            │              │
              /api/v1/analytics/**   /api/v1/datasets/**
                            │              │
                    ┌───────▼───────┐  ┌───▼──────────────┐
                    │ analytics-    │  │ dataset-service  │
                    │ service (8086)│  │ (8082)           │
                    │ 方案/指标/模板 │  │ 数据集/快照/字段 │
                    │ 查询编译器     │  │ 数据处理/画像查询 │
                    └───┬───────┬───┘  └───┬──────────────┘
                        │       │          │
                  MySQL │       │ ClickHouse（只读）
              olist_analytics │   olist_datasets
                        │       │
                        │   ┌───▼────────────────────┐
                        │   │ model-serving (8090)   │
                        │   │ /internal/v1/report-   │
                        │   └───┬────────────────────┘
                        │       │ 通用文档渲染（PDF/Word/HTML）
                        └───────┘
```

职责边界：

| 服务 | 职责 | 不做什么 |
|---|---|---|
| `dataset-service` | 数据集/版本/表/列、字段定义、数据快照、逐字段画像（分布/关系）、数据处理 | 不做多指标聚合 |
| `analytics-service`（复用现有空的 `analytics-query-service` 模块） | 指标库、分析方案与版本、模板、DSL 校验与编译、聚合查询执行、运行记录、报告内容组装 | 不做数据上传/处理 |
| `model-serving` | 通用报告渲染（PDF/Word/HTML）、模型解释/试炼 | 不感知分析语义 |
| 前端 | 分析工作台（设计器/方案库/模板）、图表渲染、下载 | 不拼 SQL |

> 如果暂时不想多跑一个 Java 进程：可以把 `analytics-service` 的代码放进 `dataset-service` 的 `analytics` 包、接口路径不变、表也放同一个库；将来拆出去只改部署，不改契约。**契约按独立服务设计**。

---

## 3. 概念模型

| 概念 | 说明 | 例子 |
|---|---|---|
| **数据来源 Source** | 数据集版本 + 数据表 | `olist_orders_ml_ready_v3` |
| **字段角色 Role** | 维度 / 度量 / 时间 / 标识（来自字段定义库 `semanticRole`） | `product_category` = 维度 |
| **指标 Metric** | 有名字、口径、格式的度量 | 延误率 = countIf(is_delayed=1) ÷ count() |
| **维度 Dimension** | 分组依据，可带时间粒度 | 月份 / 类目 / 卖家州 |
| **筛选 Filter** | 行级条件 | 时间 2018-01-01~2018-08-31；类目 IN (…) |
| **方案 Plan（Recipe）** | 一次分析的完整配置（指标+维度+筛选+图表+报告） | 「按类目看延误率」 |
| **方案版本 PlanVersion** | 方案的不可变快照 | v3 |
| **运行 Run** | 一次执行，记录 spec 快照、耗时、结果 | 2026-11-13 14:00 |
| **模板 Template** | 系统内置方案，只读，可复制 | 「延误分析」「订单分布」 |
| **画像 Profile** | 单字段的分布/统计（原「可视化分析」） | `distance_km` 直方图 |

关键原则：**执行只认 Run 里的 spec 快照**，不认「当前方案」。方案改了不影响历史结果。

---

## 4. 数据模型（`olist_analytics` 库，Flyway）

```sql
-- 指标库：owner_id 为 NULL 表示系统内置
CREATE TABLE metric_definitions (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    owner_id        VARCHAR(255) NULL,
    metric_key      VARCHAR(100) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(1000) NULL,
    value_type      VARCHAR(16)  NOT NULL,        -- number | percent | currency | duration
    expression_json TEXT         NOT NULL,        -- 表达式 AST，见 §5.2
    format_json     TEXT         NULL,            -- 小数位、单位、颜色阈值
    created_at      TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_metric_owner_key (owner_id, metric_key)
);

-- 分析方案
CREATE TABLE analysis_plans (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    owner_id        VARCHAR(255) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(1000) NULL,
    template_key    VARCHAR(100) NULL,            -- 从哪个模板复制而来
    current_version INT          NOT NULL DEFAULT 1,
    status          VARCHAR(16)  NOT NULL,        -- DRAFT | PUBLISHED | ARCHIVED
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    KEY idx_plans_owner (owner_id, updated_at)
);

-- 方案版本（不可变）
CREATE TABLE analysis_plan_versions (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    plan_id         VARCHAR(36)  NOT NULL,
    version_no      INT          NOT NULL,
    spec_json       LONGTEXT     NOT NULL,
    schema_hash     VARCHAR(64)  NULL,            -- 执行时数据表结构哈希
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_plan_version (plan_id, version_no)
);

-- 运行记录（可复现：spec 快照 + 数据版本 + schema 哈希）
CREATE TABLE analysis_runs (
    id                  VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_id            VARCHAR(255) NOT NULL,
    plan_id             VARCHAR(36) NULL,
    plan_version        INT         NULL,
    dataset_version_id  VARCHAR(36) NOT NULL,
    table_id            VARCHAR(36) NOT NULL,
    spec_json           LONGTEXT    NOT NULL,
    spec_hash           VARCHAR(64) NOT NULL,
    status              VARCHAR(16) NOT NULL,     -- QUEUED | RUNNING | SUCCEEDED | FAILED
    row_count           INT         NULL,
    elapsed_ms          INT         NULL,
    result_columns_json TEXT        NULL,
    result_rows_json    LONGTEXT    NULL,         -- ≤ 5,000 行直接存
    result_ref          VARCHAR(500) NULL,        -- 超过阈值存对象存储
    error_message       VARCHAR(1000) NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    finished_at         TIMESTAMP(6) NULL,
    KEY idx_runs_owner (owner_id, created_at),
    KEY idx_runs_plan (plan_id, created_at)
);

-- 模板（系统方案）
CREATE TABLE analysis_templates (
    template_key VARCHAR(100) NOT NULL PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    description  VARCHAR(1000) NULL,
    category     VARCHAR(64)  NULL,               -- delay | orders | rating | delivery
    spec_json    LONGTEXT     NOT NULL,
    created_at   TIMESTAMP(6) NOT NULL
);
```

结果缓存用现成 Redis：`key = sha256(spec + datasetVersionId + schemaHash)`，TTL 300s，只缓存成功结果。

---

## 5. 分析规格（DSL）

### 5.1 结构

```json
{
  "schemaVersion": 1,
  "source": {"datasetVersionId": "…", "tableId": "…"},
  "metrics": [
    {"ref": "orders"},
    {"ref": "delayed_orders"},
    {"ref": "delay_rate"},
    {"type": "aggregate", "op": "AVG", "column": "total_delivery_days", "alias": "平均配送耗时"}
  ],
  "dimensions": [
    {"column": "product_category", "alias": "类目", "limit": 20,
     "sort": {"by": "metric:订单量", "dir": "desc"}}
  ],
  "time": {
    "column": "order_purchase_timestamp",
    "grain": "month",
    "range": {"from": "2018-01-01", "to": "2018-08-31"}
  },
  "filters": [
    {"column": "customer_state", "op": "IN", "values": ["SP", "RJ"]}
  ],
  "having": [{"metric": "延误率", "op": "GT", "value": 0.08}],
  "visuals": [
    {"type": "bar", "x": "月份", "y": "延误率", "series": null},
    {"type": "table", "columns": ["月份", "订单量", "延误率"]}
  ],
  "report": {"title": "Olist 订单延误分析报告", "sections": ["kpi", "table", "chart", "notes"]}
}
```

### 5.2 指标表达式 AST

```json
{"type": "ratio",
 "numerator":   {"type": "countIf", "column": "is_delayed", "op": "EQ", "value": 1},
 "denominator": {"type": "count"}}
```

允许的节点（全部白名单）：

| 类型 | 参数 | 说明 |
|---|---|---|
| `count` | – | 行数 |
| `countIf` | column, op, value | 条件行数 |
| `sum` / `avg` / `min` / `max` | column | 数值聚合 |
| `countDistinct` | column | 去重计数 |
| `ratio` / `safeDivide` | numerator, denominator | 比率，分母 0 返回 null |
| `dateDiffDays` | fromColumn, toColumn | 天数差 |
| `percentile` | column, p | 分位数（p ∈ 0.5/0.9/0.95/0.99） |
| `literal` | value | 常量（用于加权、偏移） |

嵌套深度 ≤ 5，节点数 ≤ 50；未知类型直接拒绝。

### 5.3 维度与时间

- `dimension.grain ∈ {hour, day, week, month, quarter, year, weekday}`，编译器把 `weekday` 映射为 1–7（周一=1）并附中文标签。
- `time` 是「维度 + 筛选」的语法糖：等价于一个 grain 维度 + `range` 条件；`time.column` 必须是 DATETIME 字段。
- 维度最多 3 个、指标最多 40 个、`limit` ≤ 5,000（默认 500）。

---

## 6. 查询编译器

### 6.1 流程

```
spec ─▶ 1 取快照（dataset-service 内部接口：物理表名、列名、逻辑类型、schema_hash）
     ─▶ 2 校验（列存在、算子白名单、AST 深度、维度/指标数量、时间字段类型）
     ─▶ 3 编译 SQL（标识符只来自快照；值走参数绑定）
     ─▶ 4 执行（只读连接 + 超时 + 行数上限）
     ─▶ 5 组装结果（列元数据 + 行 + 耗时 + spec_hash）
```

### 6.2 编译示例

```sql
SELECT
  `c012` AS `类目`,
  count(*) AS `订单量`,
  countIf(toInt32OrNull(`c015`) = 1) AS `延误量`,
  round(countIf(toInt32OrNull(`c015`) = 1) / nullIf(count(*), 0), 6) AS `延误率`,
  avg(toFloat64OrNull(`c016`)) AS `平均配送耗时`
FROM `olist_datasets`.`ds_..._...`
WHERE parseDateTimeBestEffortOrNull(`c003`) >= toDate({from:String})
  AND parseDateTimeBestEffortOrNull(`c003`) <= toDate({to:String})
  AND `c007` IN ({state0:String}, {state1:String})
GROUP BY `类目`
HAVING `延误率` > {having0:Float64}
ORDER BY `订单量` DESC
LIMIT 20
SETTINGS max_execution_time = 30, max_rows_to_read = 5000000
```

规则：

1. 标识符（表/列）**只能**来自快照映射，且用反引号 + 正则二次校验（`ds_[0-9a-f]{32}_[0-9a-f]{32}`、`c\d{3,}`）。
2. 所有字面量通过 ClickHouse 参数 `{pN:Type}` 绑定，禁止字符串拼接。
3. `ratio` 一律编译为 `x / nullIf(y, 0)`，避免除零。
4. 维度/指标别名使用用户别名，但**输出列 key 由服务端重新生成**（`m1/d1`），显示名另存，避免同名冲突与注入。
5. 一次请求只允许一条 SQL；禁止 UNION、子查询（后续如需对比再扩展）。

### 6.3 错误模型

| 场景 | HTTP | 错误码 |
|---|---|---|
| spec 不合法（字段不存在/算子不允许） | 422 | `SPEC_INVALID` |
| 数据版本非 READY | 409 | `SOURCE_NOT_READY` |
| 查询超时/超限 | 422 | `QUERY_LIMIT` |
| ClickHouse 不可用 | 502 | `ENGINE_UNAVAILABLE` |

---

## 7. 服务接口

所有接口走网关 `/api/v1/analytics/**`，JWT 必填，按 owner 隔离。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/capabilities?datasetVersionId&tableId` | 返回可用维度、时间字段、指标库（含系统指标）、行数上限 |
| POST | `/validate` | 只校验不执行，返回 issues |
| POST | `/query` | 执行一次性查询（不落库），返回列+行+耗时 |
| GET/POST | `/plans` | 方案列表 / 新建 |
| GET/PUT/DELETE | `/plans/{id}` | 详情 / 保存（自动产生新版本）/ 删除 |
| GET | `/plans/{id}/versions` | 版本列表 |
| POST | `/plans/{id}/run` | 执行并落 `analysis_runs`，返回 runId |
| GET | `/runs/{id}` | 运行详情（含结果） |
| GET | `/runs?planId&limit` | 运行历史 |
| POST | `/plans/{id}/report?format=pdf|docx|html` | 用最近一次 run 或指定 run 生成报告 |
| GET | `/templates` | 系统模板列表 |
| POST | `/templates/{key}/copy` | 复制模板为我的方案 |
| GET/POST | `/metrics` | 指标库（系统 + 我的） |

响应统一包一层：

```json
{"data": {...}, "meta": {"elapsedMs": 42, "cached": true, "specHash": "…"}}
```

---

## 8. 权限、版本与可复现

- **权限**：方案/指标/运行记录按 `owner_id` 隔离；系统模板与系统指标只读；后续分享通过 `analysis_plan_shares(plan_id, subject)` 扩展，不改现有表。
- **版本**：每次保存产生新 `version_no`；`current_version` 指向最新。执行时把 spec 快照写入 run，历史结果永远可复现。
- **可复现三要素**：`spec_json` + `dataset_version_id` + `schema_hash`（表结构变了会显式提示「数据已变更，请重新运行」）。
- **审计**：run 记录谁、何时、用什么 spec 执行；报表导出同样记录（复用现有 report export 表）。

---

## 9. 性能与容量

| 项 | 策略 |
|---|---|
| 单次查询 | `max_execution_time=30s`，`max_rows_to_read=5,000,000`，结果行 ≤ 5,000 |
| 缓存 | Redis，key = sha256(spec+version+schemaHash)，TTL 300s，命中直接返回 |
| 大结果 | > 5,000 行走对象存储，run 里存 `result_ref`，前端下载 CSV |
| 慢查询 | 记录 `elapsed_ms`，超过 3s 打 WARN，供后续预聚合决策 |
| 并发 | 每用户同时执行 ≤ 2，超出排队（返回 429） |
| 预聚合 | 预留：`preagg_*` 表 + spec 里的 `engine: "auto"`，本期不实现，但 DSL 保留字段 |

---

## 10. 前端信息架构

```
数据
 ├─ 数据集
 ├─ 数据处理
 ├─ 字段定义
 └─ 分析工作台（新，替换「可视化分析」入口 /analysis）
      ├─ 左：方案库（我的 / 模板 / 最近运行）
      ├─ 中：设计器
      │    ① 数据源（数据集→版本→表）
      │    ② 指标（从指标库勾选，可加聚合指标）
      │    ③ 维度（1–3 个，可设粒度/排序/取前 N）
      │    ④ 筛选（时间范围、类目、州…，由字段角色决定控件）
      │    ⑤ 图表（表格/柱/折线/散点/KPI）
      └─ 右：预览（表格 + 图）+ 运行/保存/复制模板/导出报告/下载 CSV
```

- 「延误分析」入口保留，打开的是模板 `delay_analysis` 的只读视图，右上角「复制为我的方案」。
- 原「可视化分析」的字段画像变成设计器里的 `profile` 视觉类型（单字段分布/双字段关系），底层仍可复用 dataset-service 的画像接口。
- 设计器不允许空方案保存：至少 1 个指标或 1 个 profile 视觉。

---

## 11. 报告与导出

- 渲染下沉到 `model-serving` 的通用接口：`POST /internal/v1/report-renders`，入参是**通用文档**：

```json
{
  "title": "…", "format": "pdf",
  "meta": [["数据表", "…"], ["行数", "96,476"]],
  "sections": [
    {"heading": "一、总体指标", "paragraphs": [], "table": [["指标","数值"],["订单量","96,476"]]},
    {"heading": "二、维度分析", "chart": {"type": "bar", "rows": [...]}, "table": [...]}
  ]
}
```

- 现有模型报告继续用自己的 builder，最终都调用同一个渲染接口；分析报告的章节由 `spec.report.sections` 决定，不再写死「延误」。
- 导出格式：PDF / Word / HTML / CSV（CSV 直接导 run 结果）。

---

## 12. 现有功能迁移映射

| 现状 | 迁移后 | 处理方式 |
|---|---|---|
| `DelayAnalysisService`（硬编码指标/维度） | 系统模板 `delay_analysis` 的 spec | 指标落 `metric_definitions`，维度/筛选落 spec；代码删除 |
| `DelayAnalysisController` / `DelayReportController` | `/api/v1/analytics/**` | 前端切到新接口后下线 |
| 可视化分析的「字段分布/关系」 | `profile` 视觉类型 | 复用 dataset-service 画像接口，作为设计器的一种卡片 |
| `analysis_workspaces`（卡片列表） | `analysis_plans` + `analysis_plan_versions` | 一次性迁移脚本：每张卡片 → 一个方案版本；保留只读兼容期 |
| `analysis_reports.py`（延误专用） | 通用 `report-renders` | 文档结构通用化，模型报告复用 |
| 前端「延误分析」页 | 模板视图（只读）+ 复制 | 页面骨架复用设计器，减少重复代码 |

---

## 13. 实施顺序（按层，不返工）

| 阶段 | 交付 | 验收 |
|---|---|---|
| P1 引擎 | `analytics-service` 骨架 + Flyway + 快照客户端 + 编译器 + `/capabilities`、`/validate`、`/query` | 用手写 spec 能查出与现有延误分析一致的数字（96,476 / 6.77% / 12.50 天） |
| P2 语义 | 指标库（系统 6 个指标：订单量、签收量、延误量、延误率、平均配送耗时、平均延误天数）+ 模板 `delay_analysis` | 模板 spec 跑出的结果与 P1 一致；指标可在别的表复用 |
| P3 方案 | `/plans` CRUD + 版本 + `/run` + `/runs` | 保存→改→再存→历史版本可查；run 可复现 |
| P4 前端 | 分析工作台（设计器 + 方案库 + 预览）+ 延误分析改为模板视图 + 可视化分析并入 | 用户从零建一个「类目 × 月份」方案并导出 |
| P5 报告 | 通用 `report-renders` + 前端导出 | PDF/Word/HTML 内容与预览一致 |
| P6 收尾 | 下线旧接口、迁移 `analysis_workspaces`、文档与权限回归 | 旧入口 404；迁移后方案可打开 |

每阶段都直接落到最终表结构与 API，不做临时脚手架。

---

## 14. 需要拍板的三个决策

1. **是否独立服务**：建议独立（`analytics-service`，复用现有空模块），查询压力与数据目录分离；若坚持单进程，可先并入 dataset-service，但**表结构/接口/包结构按本文**，将来拆分不改契约。
2. **指标是否允许用户自定义表达式**：建议第一版只允许「从指标库选 + 简单聚合（sum/avg/min/max/countDistinct）」；表达式编辑器留到 V2，避免普通用户写出错误口径。
3. **图表渲染位置**：建议前端渲染（服务端只回表格数据），复用现有 echarts，报告里的图由 Python 渲染。

---

## 15. 风险

| 风险 | 应对 |
|---|---|
| 任意维度组合导致慢查询 | 行数/超时上限 + 缓存 + 慢查询日志，后续预聚合 |
| 口径漂移（每个人算的延误率不同） | 指标集中定义在指标库，系统指标只读 |
| 旧方案数据迁移丢失 | 迁移脚本 + 兼容读取 + 迁移前后条数校验 |
| 服务拆分带来运维复杂度 | 先并入 dataset-service 也可，契约不变 |
| 用户不会配 | 默认从模板进入，指标/维度都有中文名和说明 |

---

## 16. 实现进度

### P1（已完成）

- `analytics-query-service` 模块：8086，独立库 `olist_analytics`（Flyway V1 建表）。
- 已实现接口：
  - `GET /api/v1/analytics/capabilities?datasetVersionId&tableId`
  - `POST /api/v1/analytics/validate`
  - `POST /api/v1/analytics/query`
- 指标库（系统 6 个）：订单量、已签收订单量、延误订单量、延误率、平均配送耗时、平均延误天数。
- 编译器支持：维度（含 hour/day/week/month/quarter/year/weekday 粒度）、筛选（EQ/NE/GT/GTE/LT/LTE/IN/NOT_IN/BETWEEN/IS_NULL/NOT_NULL/CONTAINS）、having、排序、limit。
- 安全：标识符只取快照、值转义、只读连接、30s 超时、5000 行上限。
- 验收结果（`olist_orders_ml_ready_v3`，96,476 行）：
  `orders=96476, delivered=96476, delayed=6535, delay_rate=0.067737, avg_delivery_days=12.497, avg_delay_days=10.620`，
  与旧「延误分析」完全一致；按月分组 12 组、组合筛选（月 × 类目 + 州过滤 + having）均通过。

### 待办

- P6：下线旧接口 + 迁移 `analysis_workspaces`。

### P5（已完成）

- Python：新增通用渲染入口 `POST /internal/v1/report-renders`（`app/api/report_renders.py`），复用 PDF/Word/HTML 渲染器，入参是通用文档 `{title, format, meta, sections:[{heading, paragraphs, table}]}`。
- Java：`ReportService` 把一次分析结果组装成文档（分析口径 / 结果表 / 说明），调用渲染服务；接口：
  - `POST /api/v1/analytics/report?format=pdf|docx|html`（按当前 spec 实时分析并出报告）
  - `POST /api/v1/analytics/plans/{id}/report?format=...`（按已保存方案出报告）
- 前端：分析工作台结果区增加 CSV / PDF / Word / HTML 四个导出按钮。
- 实测：PDF 4.1KB（`%PDF-`）、Word 37KB（zip）、HTML 2.1KB（12 行数据）；模板复制（带数据表绑定）→ 运行 → 报告全链路 201/200。
- 模板复制现在支持 `?datasetVersionId=&tableId=` 绑定数据源；未绑定的方案运行/报告会返回 422 并提示重新保存。


### P2 / P3 / P4（已完成）

- **P2 指标库**：`metric_definitions` 落库（V2 种子 6 个系统指标，表达式为 JSON AST），`/capabilities` 从库里读指标；新增系统模板 `delay_analysis`（`analysis_templates`）。
- **P3 方案**：`POST/GET/PUT/DELETE /api/v1/analytics/plans`、`POST /plans/{id}/run`、`GET /plans/{id}/runs`、`GET /runs`、`GET /templates`、`POST /templates/{key}/copy`；方案每次保存产生新版本，运行记录落 `analysis_runs`（可复现）。
- **P4 前端**：新增「数据 → 分析工作台」`/analytics`（`AnalyticsWorkbenchView.vue`）：选数据集/表 → 勾指标 → 选维度/时间粒度/时间范围 → 加筛选（取值下拉走 `/values`）→ 运行 → 条形图 + 表格 + 导出 CSV；左侧方案库与系统模板，可保存/复制/删除/重跑。
- 实测：`/capabilities` 返回 6 个指标；`/values` 返回类目取值；建方案→运行→历史→复制模板全链路 200/201；前端 `pnpm build` 通过。


