package com.olist.analytics.service;

import com.olist.analytics.client.SnapshotClient;
import com.olist.analytics.spec.AnalyticsSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 把分析规格编译成一条只读的 ClickHouse SQL。 */
public class QueryCompiler {

    private static final Pattern PHYSICAL_TABLE = Pattern.compile("ds_[0-9a-f]{32}_[0-9a-f]{32}");
    private static final Pattern PHYSICAL_COLUMN = Pattern.compile("c[0-9]{3,}");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> GRAINS = Set.of("hour", "day", "week", "month", "quarter", "year", "weekday");
    private static final Set<String> FILTER_OPS = Set.of(
            "EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "BETWEEN", "IS_NULL", "NOT_NULL",
            "IS_NOT_NULL", "CONTAINS");
    private static final Set<String> AGGREGATES = Set.of("SUM", "AVG", "MIN", "MAX", "COUNT", "COUNT_DISTINCT");
    private static final Map<String, String> GRAIN_LABELS = Map.of(
            "month", "月", "weekday", "星期", "day", "日", "week", "周", "hour", "小时", "quarter", "季度", "year", "年");

    public record OutputColumn(String key, String label, String type) {
    }

    public record CompiledQuery(String sql, List<OutputColumn> columns, int limit) {
    }

    private final int maxRows;
    private final int defaultLimit;
    private final int maxDimensions;
    private final int maxMetrics;
    private final int timeoutSeconds;
    private final String database;

    public QueryCompiler(
            String database, int maxRows, int defaultLimit, int maxDimensions, int maxMetrics, int timeoutSeconds) {
        this.database = database;
        this.maxRows = maxRows;
        this.defaultLimit = defaultLimit;
        this.maxDimensions = maxDimensions;
        this.maxMetrics = maxMetrics;
        this.timeoutSeconds = timeoutSeconds;
    }

    public CompiledQuery compile(AnalyticsSpec spec, SnapshotClient.Table table) {
        if (spec == null) {
            throw new IllegalArgumentException("分析规格不能为空");
        }
        if (table == null) {
            throw new IllegalArgumentException("数据表不存在");
        }
        String physicalTable = table.physicalTable();
        if (physicalTable == null || !PHYSICAL_TABLE.matcher(physicalTable).matches()) {
            throw new IllegalArgumentException("数据表物理标识不合法");
        }
        for (SnapshotClient.Column column : table.columns()) {
            if (column.physicalName() == null || !PHYSICAL_COLUMN.matcher(column.physicalName()).matches()) {
                throw new IllegalArgumentException("字段物理标识不合法: " + column.sourceName());
            }
        }
        Map<String, SnapshotClient.Column> columns = new LinkedHashMap<>();
        for (SnapshotClient.Column column : table.columns()) {
            columns.put(column.sourceName(), column);
        }

        List<String> selectParts = new ArrayList<>();
        List<OutputColumn> outputs = new ArrayList<>();
        List<String> groupBy = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        List<String> havingParts = new ArrayList<>();

        List<AnalyticsSpec.DimensionSpec> dimensions = new ArrayList<>();
        if (spec.time() != null && spec.time().grain() != null && !spec.time().grain().isBlank()) {
            String alias = defaultTimeAlias(spec.time());
            dimensions.add(new AnalyticsSpec.DimensionSpec(
                    spec.time().column(), alias, spec.time().grain(), null, null));
        }
        if (spec.dimensions() != null) {
            dimensions.addAll(spec.dimensions());
        }
        if (dimensions.size() > maxDimensions) {
            throw new IllegalArgumentException("维度最多 " + maxDimensions + " 个");
        }
        int dimIndex = 0;
        for (AnalyticsSpec.DimensionSpec dimension : dimensions) {
            SnapshotClient.Column column = requireColumn(columns, dimension.column());
            String expression = dimensionExpression(column, dimension.grain());
            String key = "d" + (++dimIndex);
            String label = dimension.alias() == null || dimension.alias().isBlank()
                    ? defaultDimensionLabel(column, dimension.grain())
                    : dimension.alias().trim();
            selectParts.add(expression + " AS " + key);
            groupBy.add(key);
            outputs.add(new OutputColumn(key, label, column.logicalType()));
        }

        if (spec.metrics() == null || spec.metrics().isEmpty()) {
            throw new IllegalArgumentException("至少需要一个指标");
        }
        if (spec.metrics().size() > maxMetrics) {
            throw new IllegalArgumentException("指标最多 " + maxMetrics + " 个");
        }
        int metricIndex = 0;
        for (AnalyticsSpec.MetricSpec metric : spec.metrics()) {
            String key = "m" + (++metricIndex);
            String expression;
            String type;
            String label;
            MetricCatalog.Metric system = metric.ref() == null ? null : MetricCatalog.byKey(metric.ref());
            if (system != null) {
                expression = compileExpression(system.expr(), columns);
                type = system.valueType();
                label = metric.alias() == null || metric.alias().isBlank() ? system.name() : metric.alias().trim();
            } else {
                if (metric.op() == null || metric.column() == null) {
                    throw new IllegalArgumentException("指标需要 ref，或 op + column");
                }
                SnapshotClient.Column column = requireColumn(columns, metric.column());
                String op = metric.op().trim().toUpperCase(Locale.ROOT);
                if (!AGGREGATES.contains(op)) {
                    throw new IllegalArgumentException("不支持的聚合算子: " + metric.op());
                }
                expression = aggregateExpression(op, column);
                type = "AVG".equals(op) || "SUM".equals(op) ? "number" : "number";
                label = metric.alias() == null || metric.alias().isBlank()
                        ? op + "(" + column.sourceName() + ")"
                        : metric.alias().trim();
            }
            selectParts.add(expression + " AS " + key);
            outputs.add(new OutputColumn(key, label, type));
        }

        if (spec.time() != null && spec.time().range() != null) {
            String column = quote(requireColumn(columns, spec.time().column()).physicalName());
            String parsed = "parseDateTimeBestEffortOrNull(" + column + ")";
            AnalyticsSpec.Range range = spec.time().range();
            if (range.from() != null && !range.from().isBlank()) {
                conditions.add(parsed + " >= toDate(" + dateLiteral(range.from()) + ")");
            }
            if (range.to() != null && !range.to().isBlank()) {
                conditions.add(parsed + " <= toDate(" + dateLiteral(range.to()) + ")");
            }
        }
        if (spec.filters() != null) {
            for (AnalyticsSpec.FilterSpec filter : spec.filters()) {
                SnapshotClient.Column column = requireColumn(columns, filter.column());
                conditions.add(condition(column, filter));
            }
        }

        if (spec.having() != null) {
            for (AnalyticsSpec.HavingSpec having : spec.having()) {
                String key = resolveMetricKey(having.metric(), outputs, spec);
                String op = having.op() == null ? "GT" : having.op().trim().toUpperCase(Locale.ROOT);
                if (!Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE").contains(op)) {
                    throw new IllegalArgumentException("不支持的 having 比较符: " + having.op());
                }
                if (having.value() == null) {
                    throw new IllegalArgumentException("having 需要比较值");
                }
                havingParts.add(key + " " + sqlOperator(op) + " " + having.value());
            }
        }

        List<String> orderParts = new ArrayList<>();
        if (spec.orderBy() != null && !spec.orderBy().isEmpty()) {
            for (AnalyticsSpec.OrderSpec order : spec.orderBy()) {
                String key = resolveKey(order.key(), outputs, spec);
                orderParts.add(key + ("asc".equalsIgnoreCase(order.dir()) ? " ASC" : " DESC"));
            }
        } else if (!groupBy.isEmpty()) {
            orderParts.add("m1 DESC");
        }

        int limit = spec.limit() == null ? defaultLimit : Math.min(Math.max(spec.limit(), 1), maxRows);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", selectParts))
                .append(" FROM ").append(quote(database)).append('.').append(quote(physicalTable));
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        if (!groupBy.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groupBy));
        }
        if (!havingParts.isEmpty()) {
            sql.append(" HAVING ").append(String.join(" AND ", havingParts));
        }
        if (!orderParts.isEmpty()) {
            sql.append(" ORDER BY ").append(String.join(", ", orderParts));
        }
        sql.append(" LIMIT ").append(limit)
                .append(" SETTINGS max_execution_time = ").append(timeoutSeconds)
                .append(", max_rows_to_read = 5000000");
        return new CompiledQuery(sql.toString(), outputs, limit);
    }

    // ------------------------------------------------------------------ expressions

    private String compileExpression(MetricCatalog.Expr expr, Map<String, SnapshotClient.Column> columns) {
        if (expr == null) {
            throw new IllegalArgumentException("指标表达式为空");
        }
        String type = expr.type();
        switch (type) {
            case "count" -> {
                return "count(*)";
            }
            case "countIf" -> {
                SnapshotClient.Column column = requireColumn(columns, expr.column());
                return "countIf(" + condition(column, expr.op() == null ? "EQ" : expr.op(), expr.value()) + ")";
            }
            case "avg", "sum", "min", "max" -> {
                SnapshotClient.Column column = requireColumn(columns, expr.column());
                String fn = type.toUpperCase(Locale.ROOT);
                return fn + "(toFloat64OrNull(" + quote(column.physicalName()) + "))";
            }
            case "avgIf" -> {
                SnapshotClient.Column column = requireColumn(columns, expr.column());
                SnapshotClient.Column flag = requireColumn(columns, "is_delayed");
                String condition = condition(flag, expr.op() == null ? "EQ" : expr.op(), expr.value());
                return "avgIf(toFloat64OrNull(" + quote(column.physicalName()) + "), " + condition + ")";
            }
            case "ratio" -> {
                String left = compileExpression(expr.left(), columns);
                String right = compileExpression(expr.right(), columns);
                return "(" + left + ") / nullIf(" + right + ", 0)";
            }
            case "countDistinct" -> {
                SnapshotClient.Column column = requireColumn(columns, expr.column());
                return "uniqExact(" + quote(column.physicalName()) + ")";
            }
            default -> throw new IllegalArgumentException("不支持的指标表达式: " + type);
        }
    }

    private String aggregateExpression(String op, SnapshotClient.Column column) {
        String physical = quote(column.physicalName());
        return switch (op) {
            case "COUNT" -> "count(" + physical + ")";
            case "COUNT_DISTINCT" -> "uniqExact(" + physical + ")";
            case "SUM" -> "sum(toFloat64OrNull(" + physical + "))";
            case "AVG" -> "avg(toFloat64OrNull(" + physical + "))";
            case "MIN" -> "min(toFloat64OrNull(" + physical + "))";
            case "MAX" -> "max(toFloat64OrNull(" + physical + "))";
            default -> throw new IllegalArgumentException("不支持的聚合算子: " + op);
        };
    }

    private String dimensionExpression(SnapshotClient.Column column, String grain) {
        String physical = quote(column.physicalName());
        if (grain == null || grain.isBlank()) {
            return physical;
        }
        String normalized = grain.trim().toLowerCase(Locale.ROOT);
        if (!GRAINS.contains(normalized)) {
            throw new IllegalArgumentException("不支持的时间粒度: " + grain);
        }
        String parsed = "parseDateTimeBestEffortOrNull(" + physical + ")";
        return switch (normalized) {
            case "hour" -> "toHour(" + parsed + ")";
            case "day" -> "toDate(" + parsed + ")";
            case "week" -> "toStartOfWeek(" + parsed + ", 1)";
            case "month" -> "toMonth(" + parsed + ")";
            case "quarter" -> "toQuarter(" + parsed + ")";
            case "year" -> "toYear(" + parsed + ")";
            case "weekday" -> "toDayOfWeek(" + parsed + ")";
            default -> physical;
        };
    }

    private String condition(SnapshotClient.Column column, AnalyticsSpec.FilterSpec filter) {
        return condition(column, filter.op(), filter.values() == null || filter.values().isEmpty()
                ? filter.value() : filter.values());
    }

    private String condition(SnapshotClient.Column column, String rawOp, Object value) {
        String op = rawOp == null ? "EQ" : rawOp.trim().toUpperCase(Locale.ROOT);
        if ("IS_NOT_NULL".equals(op)) {
            op = "NOT_NULL";
        }
        if (!FILTER_OPS.contains(op)) {
            throw new IllegalArgumentException("不支持的筛选算子: " + rawOp);
        }
        String physical = quote(column.physicalName());
        if ("IS_NULL".equals(op)) {
            return physical + " IS NULL";
        }
        if ("NOT_NULL".equals(op)) {
            return physical + " IS NOT NULL";
        }
        if ("CONTAINS".equals(op)) {
            return "positionCaseInsensitive(" + physical + ", " + literal(String.valueOf(scalar(value))) + ") > 0";
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new IllegalArgumentException("筛选值不能为空");
            }
            String cast = numeric(list.get(0)) ? "toFloat64OrNull(" + physical + ")" : physical;
            String joined = String.join(", ", list.stream().map(item -> literal(item)).toList());
            if ("BETWEEN".equals(op)) {
                if (list.size() != 2) {
                    throw new IllegalArgumentException("BETWEEN 需要两个值");
                }
                return cast + " BETWEEN " + literal(list.get(0)) + " AND " + literal(list.get(1));
            }
            return cast + ("NOT_IN".equals(op) ? " NOT IN (" : " IN (") + joined + ")";
        }
        boolean isNumeric = numeric(value);
        String left = isNumeric ? "toFloat64OrNull(" + physical + ")" : physical;
        return left + " " + sqlOperator(op) + " " + literal(value);
    }

    private static String sqlOperator(String op) {
        return switch (op) {
            case "EQ" -> "=";
            case "NE" -> "!=";
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            default -> throw new IllegalArgumentException("不支持的比较符: " + op);
        };
    }

    // ------------------------------------------------------------------ helpers

    private SnapshotClient.Column requireColumn(Map<String, SnapshotClient.Column> columns, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("字段名不能为空");
        }
        SnapshotClient.Column column = columns.get(name.trim());
        if (column == null) {
            throw new IllegalArgumentException("字段不存在: " + name);
        }
        return column;
    }

    private String resolveMetricKey(
            String metric, List<OutputColumn> outputs, AnalyticsSpec spec) {
        return resolveKey(metric, outputs, spec);
    }

    private String resolveKey(String name, List<OutputColumn> outputs, AnalyticsSpec spec) {
        if (name == null || name.isBlank()) {
            for (OutputColumn column : outputs) {
                if (column.key().startsWith("m")) {
                    return column.key();
                }
            }
            throw new IllegalArgumentException("缺少排序或 having 依据");
        }
        String needle = name.trim();
        for (OutputColumn column : outputs) {
            if (column.label().equals(needle) || column.key().equals(needle)) {
                return column.key();
            }
        }
        if (spec.metrics() != null) {
            int index = 0;
            for (AnalyticsSpec.MetricSpec metric : spec.metrics()) {
                index++;
                if ((metric.ref() != null && metric.ref().equals(needle))
                        || (metric.alias() != null && metric.alias().equals(needle))) {
                    return "m" + index;
                }
            }
        }
        throw new IllegalArgumentException("找不到指标或维度: " + name);
    }

    private String defaultDimensionLabel(SnapshotClient.Column column, String grain) {
        String base = column.sourceName();
        if (grain == null || grain.isBlank()) {
            return base;
        }
        return base + "（" + GRAIN_LABELS.getOrDefault(grain.toLowerCase(Locale.ROOT), grain) + "）";
    }

    private String defaultTimeAlias(AnalyticsSpec.TimeSpec time) {
        return time.column() + "（" + GRAIN_LABELS.getOrDefault(
                time.grain() == null ? "" : time.grain().toLowerCase(Locale.ROOT), "时间") + "）";
    }

    private static boolean numeric(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Number) {
            return true;
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && text.matches("-?\\d+(\\.\\d+)?");
    }

    private static Object scalar(Object value) {
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : list.get(0);
        }
        return value;
    }

    private static String dateLiteral(String value) {
        if (!DATE.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("时间范围必须是 yyyy-MM-dd");
        }
        return literal(value.trim());
    }

    /** ClickHouse 字符串字面量：单引号与反斜杠转义，拒绝控制字符。 */
    private static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        String text = String.valueOf(value);
        if (text.chars().anyMatch(character -> character < 0x20)) {
            throw new IllegalArgumentException("筛选值包含非法字符");
        }
        String escaped = text.replace("\\", "\\\\").replace("'", "\\'");
        return "'" + escaped + "'";
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
