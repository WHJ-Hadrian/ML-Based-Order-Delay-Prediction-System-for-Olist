# 本地（IDEA）启动说明

所有 Java 服务都**默认使用 `ide` profile**（`spring.profiles.default: ide`）：IDEA 里直接 Run 就行，
不需要配置任何环境变量，也不需要填 Active profiles。需要显式指定时可以用 `-Dspring.profiles.active=ide`。

Docker 部署时 compose 会设置 `SPRING_PROFILES_ACTIVE=docker`，走容器内的主机名和环境变量，不受影响。

> 为什么不用 `local`：dataset-service 里 `local` / `test` 是保留给单元测试的（会关闭 JWT、使用固定的本地 owner），
> 用它启动会让数据归属错乱，所以统一用 `ide`。

## 全部服务与端口

| 服务 | 端口 | profile | 连的库 |
|---|---|---|---|
| api-gateway | 8080 | 默认 `ide` | Redis 6379 / Keycloak 8180 |
| order-service | 8081 | 默认 `ide` | `olist_orders` |
| dataset-service | 8082 | 默认 `ide` | `olist_datasets` + ClickHouse + LocalStack S3 |
| job-service | 8083 | 默认 `ide` | `olist_jobs` |
| prediction-service | 8084 | 默认 `ide` | `olist_predictions` |
| model-management-service | 8085 | 默认 `ide` | `olist_models` |
| analytics-query-service | 8086 | 默认 `ide` | `olist_analytics` + ClickHouse |
| model-serving（Python） | 8090 | 不需要 | 自己的环境变量 |
| 前端 | 5173 | 不需要 | `pnpm dev` |

## 启动顺序

1. 容器：MySQL 3308、ClickHouse 8123、Keycloak 8180、LocalStack 4566、Redis 6379
2. `dataset-service`（8082）
3. `job-service`（8083）
4. `model-management-service`（8085）
5. `analytics-query-service`（8086）
6. `model-serving`（8090，Python）
7. `api-gateway`（8080）
8. 前端 `pnpm dev`（5173）

## 各服务 ide profile 里预置了什么

| 服务 | 预置内容 |
|---|---|
| dataset-service | ClickHouse 开启、S3→localhost:4566（test/test）、三个内部令牌 |
| job-service | 本地工作流执行器、dataset-service 地址、内部令牌 |
| model-management-service | model-serving 8090、dataset-service 8082、训练令牌 |
| analytics-query-service | ClickHouse、报告服务 8090、dataset-service 8082、令牌 |
| order-service / prediction-service | MySQL 连接（库不存在会自动建） |
| api-gateway | Redis、Keycloak、邮件（MailHog 1025） |

环境变量优先级仍然高于配置文件，需要临时切换时照常设 `SPRING_DATASOURCE_URL` 即可。

## 常见现象

| 现象 | 原因 |
|---|---|
| 预测页模型下拉为空 / 预测历史消失 | 启动时没带 `ide`，服务用了 H2 内存库，重启即清空 |
| `/api/v1/workflows` 500 | job-service（8083）没启动 |
| `/api/v1/analytics/**` 404 | analytics-query-service（8086）没启动或版本旧 |
| `/api/v1/datasets` 401/403 | dataset-service 用了 `local` profile（测试模式），应改为 `ide` |
