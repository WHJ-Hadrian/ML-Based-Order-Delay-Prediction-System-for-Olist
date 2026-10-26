# Olist Order Delay Prediction Platform

这个仓库从第一天就按独立部署的微服务组织；当前搭的是服务、依赖和运行约定，不实现订单、预测或训练业务接口。

服务职责、运行入口、完整启动方式和当前实现状态见[架构设计文档](docs/architecture.md)。

## 服务边界

| 部署单元 | 技术 | 责任 |
| --- | --- | --- |
| `api-gateway` | Spring Cloud Gateway | 前端统一入口、JWT 校验和路由 |
| `order-service` | Spring Boot | 订单与订单状态，独立拥有订单库 |
| `dataset-service` | Spring Boot | 上传元数据、数据集版本、校验记录，以及字段库和清洗规则库 |
| `job-service` | Spring Boot + Temporal Client | 长任务状态和工作流启动 |
| `prediction-service` | Spring Boot | 在线预测业务、审计和模型服务调用 |
| `model-management-service` | Spring Boot | 模型审核、上线、回滚和版本元数据 |
| `analytics-query-service` | Spring Boot | 面向看板的分析查询 API |
| `model-serving` | FastAPI | 在线模型推理，与批量计算分开扩容 |
| Python Worker pools | Python + Temporal SDK | 数据集处理、训练、批量预测 |
| Spark / Flink jobs | PySpark / Flink SQL or Java | 历史批处理 / 持续事件流处理 |

Java 应用位于 `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend`，是一个 Maven 聚合工程；每个服务单独构建成镜像。Python 运行时位于 `ML-Based-Order-Delay-Prediction-System-for-Olist-backend`。Vue 前端只访问 Gateway。

## 数据和消息

- MySQL 按服务隔离数据库和写入权；本地可共用一个 MySQL 实例，生产按容量独立部署。
- LocalStack 提供本地 S3 兼容对象存储；初始化时创建 `olist-artifacts` 和 `olist-lakehouse` bucket。原始文件、模型产物和 Iceberg 数据表不经 API 或消息队列传输。
- Kafka 保存可回放的订单领域事件；事件契约在 `contracts/asyncapi.yaml`。
- Temporal 编排导入、训练和批量预测工作流，负责持久化状态、重试、超时和取消。Kafka 不是长任务队列。
- Spark 执行离线批处理，Flink 执行持续流计算；两者是计算运行时，不是业务微服务。
- Redis 为在线特征存储提供本地基础设施；ClickHouse 服务分析查询；MLflow 管理实验和模型注册。
- Prometheus 和 Grafana 通过 `observability` profile 启动，收集 Java Actuator 与 Python FastAPI 指标。
- Keycloak 提供 OIDC 登录。生产环境需替换所有本地开发凭据和域名。

## 本地开发

需要 Java 17、Docker Desktop、Node.js 22 和 Python 3.11+。

1. 在仓库根目录复制 `.env.example` 为 `.env`，仅使用其中的本地开发凭据。
2. 启动核心基础设施：`docker compose up -d`。
3. 启动全部 Java 服务：`docker compose --profile apps up --build`。
4. 运行 Java 构建和测试：进入 Java 后端目录运行 `.\mvnw.cmd test`。
5. 启动 Python 模型服务：进入 Python 目录后运行 `python -m uvicorn app.main:app --reload --port 8090`。
6. 前端目录运行 `pnpm install` 和 `pnpm dev`；Vite 将 `/api` 转发到 Gateway 的 `8080` 端口。

### 登录和注册的最小启动组合

登录、注册由 `api-gateway` 和 Keycloak 提供，不是 `dataset-service` 的功能。只启动前端和
`dataset-service` 时，`/api/v1/auth/accounts/lookup` 会因为拿不到 Keycloak 服务账号令牌而返回 503。

第一次登录或注册前至少需要启动：

```powershell
docker compose up -d mysql redis mailpit keycloak
```

然后启动 `api-gateway`（IDE 里运行，或使用 `docker compose --profile apps up -d api-gateway`）和前端。
验证码存放在 Redis，登录和注册的用户由 Keycloak 管理。

全新数据卷启动 Keycloak 时，`infra/keycloak/olist-realm.json` 会导入 `olist-web` 和
`olist-registration-service` 两个客户端。若本机之前已经启动过 Keycloak，realm 导入不会覆盖已有数据，
需要补建客户端并授予 `manage-users`、`query-users`、`view-users`：

```powershell
$env:KEYCLOAK_ADMIN = 'admin'
$env:KEYCLOAK_ADMIN_PASSWORD = 'local-admin-change-me'
node infra/keycloak/configure-registration-client.mjs
```

把脚本输出的 `KEYCLOAK_REGISTRATION_CLIENT_SECRET` 写入根目录 `.env`，并确保
`REGISTRATION_OTP_SECRET` 至少有 32 个字符，然后重启 `api-gateway`。`.env.example` 故意不包含这些密钥；
实际密钥仅保存在未跟踪的本地 `.env` 中，不要提交或分发。

Windows 上也可以直接运行一键修复脚本（推荐）：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\fix-auth.ps1
```

脚本会启动缺失的 MySQL、Redis、Mailpit 和 Keycloak，补建注册服务客户端与角色，并验证服务账号。
如果 Keycloak 已经启动，脚本不会重复启动它。

如果 `api-gateway` 直接运行在 Windows 上，使用 Mailpit 时把 `.env` 中的 `SMTP_HOST` 改为
`localhost`；如果运行在 Compose 容器里，则保持 `mailpit`。

默认 Compose 不启动高资源计算集群。需要调试时可显式启用 `batch`（Spark）、`stream`（Flink）和 `observability`（Prometheus/Grafana）profile。`apps` profile 会同时启动 MLflow 与各应用。Python 的 `features` 依赖组提供 Feast/Redis 集成，但不进入默认模型服务镜像。上述计算与监控配置是运行时选型，不代表服务边界留待以后再设计。

## 健康检查

- Gateway：`http://localhost:8080/actuator/health`
- Java 服务均暴露 Actuator 健康与 Prometheus 指标端点。
- Python 模型服务：`http://localhost:8090/health/live` 和 `/health/ready`
- Keycloak：`http://localhost:8180`
- Temporal UI：`http://localhost:8233`
- S3 API：`http://localhost:4566`（本地开发凭据见 `.env.example`）
- ClickHouse HTTP：`http://localhost:8123`
- Prometheus：`http://localhost:9090`（`observability` profile）
- Grafana：`http://localhost:3000`（`observability` profile）

Olist 原始数据是历史 CSV。实时链路具备事件处理能力；要演示实时效果，需要把历史记录回放成 Kafka 事件，或接入真实订单状态事件源。
