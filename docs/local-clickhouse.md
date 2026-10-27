# 本地 ClickHouse 联调

本地 `dataset-service` 使用 `local` profile 时，元数据仍保存在 H2，数据集文件保存在 `./data/datasets`；数据集进入 `READY` 后，物理表会写入 Docker 中的 ClickHouse。

## 1. 启动 ClickHouse

在仓库根目录执行：

```powershell
docker compose up -d clickhouse
docker compose ps clickhouse
```

容器应显示 `healthy`，并暴露：

- HTTP：`http://localhost:8123`
- Native TCP：`localhost:9002`

默认本地连接信息：

```text
database: olist_datasets
username: olist
password: local-clickhouse-only
```

## 2. 验证容器

```powershell
docker exec olist-platform-clickhouse-1 clickhouse-client `
  --user olist --password local-clickhouse-only `
  --query "SHOW TABLES FROM olist_datasets"
```

初始化脚本会创建 `olist_datasets` 和 `olist_rule_staging`；`olist_analytics` 由 ClickHouse 容器初始化参数创建。

## 3. 启动 dataset-service

`application-local.yml` 已默认启用本地 ClickHouse，因此直接重启 IntelliJ 中的 `DatasetServiceApplication` 即可。命令行方式：

```powershell
cd ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend
.\mvnw.cmd -pl dataset-service -am -DskipTests package
java -jar dataset-service/target/dataset-service-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=local
```

健康检查：`http://localhost:8082/actuator/health`。

如需覆盖默认值，设置以下环境变量，然后重启服务：

```powershell
$env:CLICKHOUSE_ENABLED = 'true'
$env:CLICKHOUSE_URL = 'jdbc:clickhouse://localhost:8123?compress=0'
$env:CLICKHOUSE_DATABASE = 'olist_datasets'
$env:CLICKHOUSE_USER = 'olist'
$env:CLICKHOUSE_PASSWORD = 'local-clickhouse-only'
```

## 4. 上传并确认写入

上传数据集并标记 `READY` 后，执行：

```powershell
docker exec olist-platform-clickhouse-1 clickhouse-client `
  --user olist --password local-clickhouse-only `
  --query "SHOW TABLES FROM olist_datasets"

docker exec olist-platform-clickhouse-1 clickhouse-client `
  --user olist --password local-clickhouse-only `
  --query "SELECT * FROM olist_datasets.<physical_table> LIMIT 10"
```

物理表名由 dataset-service 生成，格式类似 `ds_<datasetId>_<tableKey>`；以 `SHOW TABLES` 的结果为准。

## 5. 常见问题

如果 READY 报 `Magic is not correct` 或 `ClickHouse 插入语句准备失败`，说明 ClickHouse JDBC 响应压缩与当前服务端不兼容。连接 URL 必须包含 `?compress=0`；代码会在未显式配置时自动追加该参数。

Docker Compose 中的 `dataset-service` 使用容器内地址 `jdbc:clickhouse://clickhouse:8123?compress=0`；在 Windows/IntelliJ 直连时使用 `jdbc:clickhouse://localhost:8123?compress=0`，两者不要混用。
