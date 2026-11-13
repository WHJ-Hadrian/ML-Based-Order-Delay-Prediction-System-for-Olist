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
        List<Map<String, Object>> fields = new ArrayList<>();
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
            fields.add(Map.of("name", column.sourceName(), "type", type));
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
        result.put("fields", fields);
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
        java.util.List<Map<String, Object>> columnPayload = compiled.columns().stream()
                .map(column -> Map.<String, Object>of(
                        "key", column.key(), "label", column.label(), "type", column.type()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columnPayload);
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


    /** 一次执行多个可视化：每个可视化派生一个查询，互不影响。 */
    public Map<String, Object> execute(
            UUID datasetVersionId, UUID tableId, AnalyticsSpec spec, String ownerId, String authorization) {
        SnapshotClient.Snapshot snapshot = snapshotClient.fetch(datasetVersionId, authorization);
        SnapshotClient.Table table = requireTable(snapshot, tableId);
        Map<String, Metric> catalog = MetricService.index(metricService.list(ownerId));
        List<AnalyticsSpec.VisualSpec> visuals = spec.visuals() == null || spec.visuals().isEmpty()
                ? defaultVisuals(spec)
                : spec.visuals();
        List<Map<String, Object>> results = new ArrayList<>();
        for (AnalyticsSpec.VisualSpec visual : visuals) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", visual.id() == null ? java.util.UUID.randomUUID().toString() : visual.id());
            item.put("type", visual.type() == null ? "table" : visual.type());
            item.put("title", visual.title());
            item.put("metric", visual.metric());
            item.put("dimension", visual.dimension());
            item.put("width", visual.width() == null ? "half" : visual.width());
            try {
                if ("scatter".equals(visual.type())) {
                    Map<String, Object> corr = correlation(datasetVersionId, tableId,
                            visual.dimension(), visual.metric(), 2000, authorization);
                    item.putAll(corr);
                    item.put("rowCount", ((java.util.List<?>) corr.get("points")).size());
                    results.add(item);
                    continue;
                }
                AnalyticsSpec effective = derive(spec, visual);
                QueryCompiler.CompiledQuery compiled = compiler().compile(effective, table, catalog);
                long started = System.nanoTime();
                List<List<Object>> rows = runRows(compiled);
                item.put("columns", compiled.columns().stream()
                        .map(column -> Map.<String, Object>of(
                                "key", column.key(), "label", column.label(), "type", column.type()))
                        .toList());
                item.put("rows", rows);
                item.put("rowCount", rows.size());
                item.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException error) {
                item.put("error", error.getMessage() == null ? "查询失败" : error.getMessage());
                item.put("columns", List.of());
                item.put("rows", List.of());
            }
            results.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaHash", snapshot.schemaHash());
        result.put("visuals", results);
        return result;
    }

    private List<AnalyticsSpec.VisualSpec> defaultVisuals(AnalyticsSpec spec) {
        List<AnalyticsSpec.VisualSpec> visuals = new ArrayList<>();
        visuals.add(new AnalyticsSpec.VisualSpec("kpi", "kpi", "核心指标", null, null, "full",
                null, null, null, null));
        if (spec.time() != null && spec.time().column() != null) {
            visuals.add(new AnalyticsSpec.VisualSpec("trend", "line", "趋势", null, "__time__", "half",
                    null, null, null, null));
        }
        if (spec.dimensions() != null && !spec.dimensions().isEmpty()) {
            visuals.add(new AnalyticsSpec.VisualSpec("distribution", "bar", "分布", null,
                    spec.dimensions().get(0).column(), "half", null, null, null, null));
        }
        visuals.add(new AnalyticsSpec.VisualSpec("detail", "table", "明细", null, null, "full",
                null, null, null, null));
        return visuals;
    }

    private AnalyticsSpec derive(AnalyticsSpec spec, AnalyticsSpec.VisualSpec visual) {
        String type = visual.type() == null ? "table" : visual.type().toLowerCase();
        List<AnalyticsSpec.MetricSpec> metrics = visual.metric() == null || visual.metric().isBlank()
                ? spec.metrics()
                : List.of(new AnalyticsSpec.MetricSpec(visual.metric(), null, null, null, null));
        String dimension = visual.dimension();
        boolean timeDimension = "__time__".equals(dimension) || (dimension != null && spec.time() != null
                && dimension.equals(spec.time().column()));
        List<AnalyticsSpec.DimensionSpec> dimensions = dimension == null || dimension.isBlank() || timeDimension
                ? List.of()
                : List.of(new AnalyticsSpec.DimensionSpec(dimension, null, null, null, null));
        String orderKey = dimensions.isEmpty() ? "d1" : "m1";
        String orderDir = dimensions.isEmpty() ? "asc" : "desc";
        return switch (type) {
            case "kpi" -> new AnalyticsSpec(spec.schemaVersion(), spec.source(), metrics, List.of(), null, null,
                    null, null, 1, spec.visuals(), spec.report());
            case "line", "trend" -> new AnalyticsSpec(spec.schemaVersion(), spec.source(), metrics, List.of(),
                    spec.time(), spec.filters(), null, List.of(new AnalyticsSpec.OrderSpec("d1", "asc")),
                    Math.min(spec.limit() == null ? 500 : spec.limit(), 500), spec.visuals(), spec.report());
            case "bar", "pie", "donut" -> new AnalyticsSpec(spec.schemaVersion(), spec.source(), metrics,
                    dimensions, timeDimension ? spec.time() : null, spec.filters(), null,
                    List.of(new AnalyticsSpec.OrderSpec(orderKey, orderDir)),
                    "pie".equals(type) || "donut".equals(type) ? 12 : 20, spec.visuals(), spec.report());
            default -> spec;
        };
    }

    private List<List<Object>> runRows(QueryCompiler.CompiledQuery compiled) {
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
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "查询失败：" + error.getMessage(), error);
        }
        return rows;
    }


    /** 两个字段的相关性：皮尔逊系数 + 散点样本 + 结论。 */
    public Map<String, Object> correlation(
            UUID datasetVersionId, UUID tableId, String xColumn, String yColumn, int limit, String authorization) {
        if (xColumn == null || yColumn == null || xColumn.equals(yColumn)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "请选择两个不同的字段");
        }
        SnapshotClient.Snapshot snapshot = snapshotClient.fetch(datasetVersionId, authorization);
        SnapshotClient.Table table = requireTable(snapshot, tableId);
        String x = requirePhysical(columns(table), xColumn);
        String y = requirePhysical(columns(table), yColumn);
        String database = clickHouseProperties.getDatabase();
        String source = "SELECT toFloat64OrNull(" + quote(x) + ") AS x, toFloat64OrNull(" + quote(y) + ") AS y"
                + " FROM " + quote(database) + "." + quote(table.physicalTable());
        String filtered = "SELECT x, y FROM (" + source + ") WHERE x IS NOT NULL AND y IS NOT NULL";
        int sample = Math.min(Math.max(limit, 100), 5000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("xColumn", xColumn);
        result.put("yColumn", yColumn);
        try (Connection connection = clickHouseConnections.open(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            long count = 0;
            Double r = null;
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT count(*) AS n, corr(x, y) AS r FROM (" + filtered + ")")) {
                if (resultSet.next()) {
                    count = resultSet.getLong(1);
                    double value = resultSet.getDouble(2);
                    if (!resultSet.wasNull() && Double.isFinite(value)) {
                        r = value;
                    }
                }
            }
            List<List<Object>> points = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT x, y FROM (" + filtered + ") LIMIT " + sample)) {
                while (resultSet.next()) {
                    points.add(List.of(resultSet.getDouble(1), resultSet.getDouble(2)));
                }
            }
            result.put("n", count);
            result.put("r", r);
            result.put("points", points);
            result.put("strength", strength(r));
            result.put("direction", r == null ? "未知" : (r > 0 ? "正相关" : r < 0 ? "负相关" : "无方向"));
            result.put("interpretation", interpretation(xColumn, yColumn, count, r));
        } catch (java.sql.SQLException error) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "相关性计算失败：" + error.getMessage(), error);
        }
        return result;
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static Map<String, String> columns(SnapshotClient.Table table) {
        Map<String, String> map = new LinkedHashMap<>();
        for (SnapshotClient.Column column : table.columns()) {
            map.put(column.sourceName(), column.physicalName());
        }
        return map;
    }

    private static String requirePhysical(Map<String, String> columns, String name) {
        String physical = columns.get(name);
        if (physical == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "字段不存在: " + name);
        }
        return physical;
    }

    private static String strength(Double r) {
        if (r == null) {
            return "无法判断";
        }
        double value = Math.abs(r);
        if (value < 0.1) return "几乎不相关";
        if (value < 0.3) return "弱相关";
        if (value < 0.5) return "中等相关";
        if (value < 0.7) return "较强相关";
        return "强相关";
    }

    private static String interpretation(String x, String y, long n, Double r) {
        if (r == null || n < 3) {
            return "有效样本不足（" + n + " 条），无法计算相关性。";
        }
        String direction = r > 0 ? "正" : "负";
        double abs = Math.abs(r);
        String level;
        if (abs < 0.1) level = "基本可以认为两者没有线性关系";
        else if (abs < 0.3) level = "两者存在较弱的" + direction + "相关";
        else if (abs < 0.5) level = "两者存在中等程度的" + direction + "相关";
        else if (abs < 0.7) level = "两者存在较强的" + direction + "相关";
        else level = "两者存在很强的" + direction + "相关";
        return String.format(java.util.Locale.ROOT,
                "在 %d 条有效样本上，%s 与 %s 的皮尔逊相关系数 r = %.3f，%s。"
                        + "相关系数只描述线性关联，不代表因果关系；样本量和异常值都会影响结果。",
                n, x, y, r, level);
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
