package com.olist.analytics.service;

import com.olist.analytics.client.SnapshotClient;
import com.olist.analytics.config.ClickHouseConnectionFactory;
import com.olist.analytics.config.ClickHouseProperties;
import com.olist.analytics.service.MetricService.Metric;
import com.olist.analytics.spec.AnalyticsSpec;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 分析能力查询、规格校验与执行。 */
@Service
public class AnalyticsService {

    private final SnapshotClient snapshotClient;
    private final MetricService metricService;
    private final ClickHouseConnectionFactory clickHouseConnections;
    private final ClickHouseProperties clickHouseProperties;
    private final int maxRows;
    private final int defaultLimit;
    private final int maxDimensions;
    private final int maxMetrics;
    private final int timeoutSeconds;

    public AnalyticsService(
            SnapshotClient snapshotClient,
            MetricService metricService,
            ClickHouseConnectionFactory clickHouseConnections,
            ClickHouseProperties clickHouseProperties,
            @Value("${olist.analytics.max-rows:5000}") int maxRows,
            @Value("${olist.analytics.default-limit:500}") int defaultLimit,
            @Value("${olist.analytics.max-dimensions:3}") int maxDimensions,
            @Value("${olist.analytics.max-metrics:40}") int maxMetrics,
            @Value("${olist.analytics.query-timeout-seconds:30}") int timeoutSeconds) {
        this.snapshotClient = snapshotClient;
        this.metricService = metricService;
        this.clickHouseConnections = clickHouseConnections;
        this.clickHouseProperties = clickHouseProperties;
        this.maxRows = maxRows;
        this.defaultLimit = defaultLimit;
        this.maxDimensions = maxDimensions;
        this.maxMetrics = maxMetrics;
        this.timeoutSeconds = timeoutSeconds;
    }

    private QueryCompiler compiler() {
        return new QueryCompiler(clickHouseProperties.getDatabase(), maxRows, defaultLimit,
                maxDimensions, maxMetrics, timeoutSeconds);
    }

    public Map<String, Object> capabilities(UUID datasetVersionId, UUID tableId, String ownerId, String authorization) {
        SnapshotClient.Snapshot snapshot = snapshotClient.fetch(datasetVersionId, authorization);
        SnapshotClient.Table table = requireTable(snapshot, tableId);
        List<Map<String, Object>> dimensions = new ArrayList<>();
        List<Map<String, Object>> timeColumns = new ArrayList<>();
        for (SnapshotClient.Column column : table.columns()) {
            String type = column.logicalType() == null ? "STRING" : column.logicalType().toUpperCase();
            boolean timeLike = type.equals("DATE") || type.equals("DATETIME") || looksLikeTime(column.sourceName());
            if (timeLike) {
                timeColumns.add(Map.of(
                        "name", column.sourceName(),
                        "type", type.equals("STRING") ? "DATETIME" : type,
                        "grains", List.of("hour", "day", "week", "month", "quarter", "year", "weekday")));
            } else if (type.equals("STRING") || type.equals("BOOLEAN")) {
                dimensions.add(Map.of("name", column.sourceName(), "type", type));
            }
        }
        Set<String> available = columnNames(table);
        List<Map<String, Object>> metrics = new ArrayList<>();
        List<Metric> catalog = metricService.list(ownerId);
        for (Metric metric : catalog) {
            List<String> missing = metric.columns().stream()
                    .filter(name -> !available.contains(name))
                    .toList();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", metric.key());
            item.put("name", metric.name());
            item.put("description", metric.description());
            item.put("valueType", metric.valueType());
            item.put("available", missing.isEmpty());
            item.put("missingColumns", missing);
            metrics.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetVersionId", datasetVersionId);
        result.put("tableId", tableId);
        result.put("tableName", table.tableName());
        result.put("rowCount", table.rowCount());
        result.put("schemaHash", snapshot.schemaHash());
        result.put("dimensions", dimensions);
        result.put("timeColumns", timeColumns);
        result.put("metrics", metrics);
        result.put("limits", Map.of(
                "maxRows", maxRows,
                "defaultLimit", defaultLimit,
                "maxDimensions", maxDimensions,
                "maxMetrics", maxMetrics));
        return result;
    }

    public Map<String, Object> validate(UUID datasetVersionId, UUID tableId, AnalyticsSpec spec, String ownerId, String authorization) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            SnapshotClient.Snapshot snapshot = snapshotClient.fetch(datasetVersionId, authorization);
            QueryCompiler.CompiledQuery compiled = compiler().compile(
                    spec, requireTable(snapshot, tableId), MetricService.index(metricService.list(ownerId)));
            result.put("valid", true);
            result.put("columns", compiled.columns());
            result.put("limit", compiled.limit());
            result.put("issues", List.of());
        } catch (IllegalArgumentException error) {
            result.put("valid", false);
            result.put("columns", List.of());
            result.put("limit", 0);
            result.put("issues", List.of(error.getMessage()));
        }
        return result;
    }

    public Map<String, Object> query(UUID datasetVersionId, UUID tableId, AnalyticsSpec spec, String ownerId, String authorization) {
        if (datasetVersionId == null || tableId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "必须指定数据版本和数据表");
        }
        SnapshotClient.Snapshot snapshot = snapshotClient.fetch(datasetVersionId, authorization);
        SnapshotClient.Table table = requireTable(snapshot, tableId);
        QueryCompiler.CompiledQuery compiled = compiler().compile(
                spec, table, MetricService.index(metricService.list(ownerId)));
        long started = System.nanoTime();
        List<List<Object>> rows = new ArrayList<>();
        try (Connection connection = clickHouseConnections.open();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery(compiled.sql())) {
                ResultSetMetaData meta = resultSet.getMetaData();
                int columnCount = meta.getColumnCount();
                while (resultSet.next() && rows.size() < maxRows) {
                    List<Object> row = new ArrayList<>(columnCount);
                    for (int index = 1; index <= columnCount; index++) {
                        row.add(jsonValue(resultSet.getObject(index)));
                    }
                    rows.add(row);
                }
            }
        } catch (java.sql.SQLException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "查询失败：" + error.getMessage(), error);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", compiled.columns());
        result.put("rows", rows);
        result.put("rowCount", rows.size());
        result.put("limit", compiled.limit());
        result.put("elapsedMs", elapsedMs);
        result.put("specHash", Integer.toHexString(compiled.sql().hashCode()));
        result.put("schemaHash", snapshot.schemaHash());
        return result;
    }


    /** 维度取值列表（筛选下拉用）。 */
    public java.util.List<Map<String, Object>> values(
            UUID datasetVersionId, UUID tableId, String column, int limit, String authorization) {
        SnapshotClient.Snapshot snapshot = snapshotClient.fetch(datasetVersionId, authorization);
        SnapshotClient.Table table = requireTable(snapshot, tableId);
        SnapshotClient.Column target = table.columns().stream()
                .filter(item -> item.sourceName() != null && item.sourceName().equals(column))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "字段不存在: " + column));
        int size = Math.min(Math.max(limit, 1), 200);
        String sql = "SELECT `" + target.physicalName() + "` AS value, count(*) AS orders"
                + " FROM `" + clickHouseProperties.getDatabase() + "`.`" + table.physicalTable() + "`"
                + " WHERE `" + target.physicalName() + "` IS NOT NULL AND `" + target.physicalName() + "` != ''"
                + " GROUP BY value ORDER BY orders DESC LIMIT " + size;
        java.util.List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = clickHouseConnections.open();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    rows.add(Map.of("value", String.valueOf(resultSet.getObject(1)),
                            "count", resultSet.getLong(2)));
                }
            }
        } catch (java.sql.SQLException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "查询失败：" + error.getMessage(), error);
        }
        return rows;
    }

    private SnapshotClient.Table requireTable(SnapshotClient.Snapshot snapshot, UUID tableId) {
        if (snapshot == null || snapshot.tables() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据集快照不存在");
        }
        return snapshot.tables().stream()
                .filter(table -> table.tableId().equals(tableId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据表不存在"));
    }

    private static Set<String> columnNames(SnapshotClient.Table table) {
        Set<String> names = new java.util.LinkedHashSet<>();
        table.columns().forEach(column -> names.add(column.sourceName()));
        return names;
    }

    /** CSV 导入的时间列通常是 STRING，用字段名兜底识别。 */
    private static boolean looksLikeTime(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith("_timestamp") || lower.endsWith("_date") || lower.endsWith("_at");
    }

    private static Object jsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof TemporalAccessor temporal) {
            return temporal.toString();
        }
        return String.valueOf(value);
    }
}
