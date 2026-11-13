package com.olist.analytics.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 系统指标库（P1 内置；P2 起迁移到 metric_definitions 表）。
 * 表达式只引用逻辑字段名，由编译器映射到物理列。
 */
public final class MetricCatalog {

    public record Expr(String type, String column, String op, Object value, Expr left, Expr right) {
    }

    public record Metric(String key, String name, String valueType, Expr expr, Set<String> columns) {
    }

    private static final List<Metric> SYSTEM = List.of(
            metric("orders", "订单量", "number", new Expr("count", null, null, null, null, null),
                    Set.of()),
            metric("delivered_orders", "已签收订单量", "number",
                    new Expr("countIf", "total_delivery_days", "IS_NOT_NULL", null, null, null),
                    Set.of("total_delivery_days")),
            metric("delayed_orders", "延误订单量", "number",
                    new Expr("countIf", "is_delayed", "EQ", 1, null, null),
                    Set.of("is_delayed")),
            metric("delay_rate", "延误率", "percent",
                    new Expr("ratio", null, null, null,
                            new Expr("countIf", "is_delayed", "EQ", 1, null, null),
                            new Expr("count", null, null, null, null, null)),
                    Set.of("is_delayed")),
            metric("avg_delivery_days", "平均配送耗时", "duration",
                    new Expr("avg", "total_delivery_days", null, null, null, null),
                    Set.of("total_delivery_days")),
            metric("avg_delay_days", "平均延误天数", "duration",
                    new Expr("avgIf", "delay_days", "EQ", 1, null, null),
                    Set.of("delay_days", "is_delayed"))
    );

    private MetricCatalog() {
    }

    public static List<Metric> all() {
        return SYSTEM;
    }

    public static Metric byKey(String key) {
        return SYSTEM.stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
    }

    private static Metric metric(String key, String name, String valueType, Expr expr, Set<String> columns) {
        return new Metric(key, name, valueType, expr, columns);
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
}
