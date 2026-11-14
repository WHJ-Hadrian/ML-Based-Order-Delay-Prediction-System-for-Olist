# 本地（IDEA）启动说明

所有 Java 服务都支持 **`ide` profile**：在 IDEA 的 Run Configuration 里加一个 VM/Program 参数
`--spring.profiles.active=ide`，就会自动连本机的 MySQL / ClickHouse / LocalStack，不需要再填一堆环境变量。

> 为什么不用 `local`：dataset-service 里 `local` / `test` 是保留给单元测试的（会关闭 JWT、使用固定的本地 owner），
> 用它启动会让数据归属错乱，所以统一用 `ide`。

## 启动顺序

| 顺序 | 服务 | 端口 | 启动参数 |
|---|---|---|---|
| 1 | dataset-service | 8082 | `--spring.profiles.active=ide` |
| 2 | job-service | 8083 | `--spring.profiles.active=ide` |
| 3 | model-management-service | 8085 | `--spring.profiles.active=ide` |
| 4 | analytics-query-service | 8086 | `--spring.profiles.active=ide` |
| 5 | model-serving（Python） | 8090 | `uvicorn app.main:app --host 127.0.0.1 --port 8090` |
| 6 | api-gateway | 8080 | 直接启动（默认就连本机） |
| 7 | 前端 | 5173 | `pnpm dev` |

依赖的容器：MySQL 3308、ClickHouse 8123、Keycloak 8180、LocalStack 4566、Redis 6379。

## 各服务 ide profile 里预置了什么

| 服务 | 数据库 | 其它 |
|---|---|---|
| dataset-service | `olist_datasets` | ClickHouse 开启、S3 指向 localhost:4566、内部令牌 |
| job-service | `olist_jobs` | 本地工作流执行器、dataset-service 地址、内部令牌 |
| model-management-service | `olist_models` | model-serving 8090、dataset-service 8082、训练令牌 |
| analytics-query-service | `olist_analytics` | ClickHouse、报告服务 8090、内部令牌 |

环境变量仍然优先于配置文件，需要临时切换数据库时照常设 `SPRING_DATASOURCE_URL` 即可。

## 常见现象

| 现象 | 原因 |
|---|---|
| 预测页模型下拉为空 / 预测历史消失 | 启动时没带 `ide` profile，服务用了 H2 内存库，重启即清空 |
| `/api/v1/workflows` 500 | job-service（8083）没启动 |
| `/api/v1/analytics/**` 404 | analytics-query-service（8086）没启动或版本旧 |
