package com.olist.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 指标库：系统指标（owner_id IS NULL）+ 当前用户自定义指标。 */
@Service
public class MetricService {

    public record Expr(String type, String column, String op, Object value, Expr left, Expr right) {
    }

    public record Metric(String key, String name, String description, String valueType, Expr expr, Set<String> columns) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetricService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Metric> list(String ownerId) {
        String sql = "SELECT metric_key, name, description, value_type, expression_json"
                + " FROM metric_definitions WHERE owner_id IS NULL OR owner_id = ?"
                + " ORDER BY owner_id IS NULL DESC, metric_key";
        return jdbcTemplate.query(sql, (rs, index) -> {
            String expression = rs.getString("expression_json");
            Expr expr = parse(expression);
            return new Metric(
                    rs.getString("metric_key"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("value_type"),
                    expr,
                    requiredColumns(expr));
        }, ownerId);
    }

    private Expr parse(String json) {
        try {
            return objectMapper.readValue(json, Expr.class);
        } catch (Exception error) {
            throw new IllegalStateException("指标表达式解析失败: " + error.getMessage(), error);
        }
    }

    public static Set<String> requiredColumns(Expr expr) {
        Set<String> columns = new LinkedHashSet<>();
        walk(expr, columns);
        return columns;
    }

    private static void walk(Expr expr, Set<String> columns) {
        if (expr == null) {
            return;
        }
        if (expr.column() != null) {
            columns.add(expr.column());
        }
        walk(expr.left(), columns);
        walk(expr.right(), columns);
    }

    public static Map<String, Metric> index(List<Metric> metrics) {
        Map<String, Metric> map = new java.util.LinkedHashMap<>();
        metrics.forEach(metric -> map.put(metric.key(), metric));
        return map;
    }
}
