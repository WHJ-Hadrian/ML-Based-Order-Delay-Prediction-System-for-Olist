package com.olist.datasets.service;

import com.olist.datasets.clickhouse.ClickHouseClient;
import com.olist.datasets.clickhouse.ClickHouseClientFactory;
import com.olist.datasets.config.ClickHouseProperties;
import com.olist.datasets.exception.DatasetNotReadyException;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.vo.DatasetCatalogVO;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 物流延误分析：订单量、签收量、延误量、配送耗时、延误天数、
 * 提前/按时/延误占比，以及可配置维度的延误率。
 *
 * <p>支持两种数据形态：已加工宽表（is_delayed / delay_days / total_delivery_days），
 * 以及只有四个时间戳的原始订单表（现场推导延误）。</p>
 */
@Service
public class DelayAnalysisService {

    private static final Pattern PHYSICAL_TABLE = Pattern.compile("ds_[0-9a-f]{32}_[0-9a-f]{32}");
    private static final Pattern PHYSICAL_COLUMN = Pattern.compile("c[0-9]{3,}");
    private static final String[] WEEKDAY_LABELS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final int OPTION_LIMIT = 50;
    private static final int BUCKET_LIMIT = 15;

    private final DatasetService datasetService;
    private final ClickHouseProperties clickHouseProperties;
    private final ClickHouseClientFactory clickHouseClientFactory;

    public DelayAnalysisService(
            DatasetService datasetService,
            ClickHouseProperties clickHouseProperties,
            ClickHouseClientFactory clickHouseClientFactory) {
        this.datasetService = datasetService;
        this.clickHouseProperties = clickHouseProperties;
        this.clickHouseClientFactory = clickHouseClientFactory;
    }

    public Map<String, Object> analyze(UUID datasetId, UUID tableId) {
        return analyze(datasetId, tableId, DelayAnalysisQuery.empty());
    }

    public Map<String, Object> analyze(UUID datasetId, UUID tableId, DelayAnalysisQuery query) {
        if (!clickHouseProperties.isEnabled()) {
            throw new DatasetNotReadyException("分析查询未启用 ClickHouse 存储");
        }
        DatasetCatalogVO catalog = datasetService.catalog(datasetId);
        DatasetCatalogVO.CatalogTableVO catalogTable = catalog.tables().stream()
                .filter(table -> table.tableId().equals(tableId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("数据表不属于所选数据集版本"));
        ExecutionSnapshotVO snapshot = datasetService.executionSnapshot(datasetId);
        ExecutionSnapshotVO.SnapshotTableVO physicalTable = snapshot.tables().stream()
                .filter(table -> table.tableId().equals(tableId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("数据表缺少可查询的物理表"));

        Map<String, String> physicalByName = new LinkedHashMap<>();
        Map<String, String> physicalByColumnId = new LinkedHashMap<>();
        for (ExecutionSnapshotVO.SnapshotColumnVO column : physicalTable.columns()) {
            physicalByColumnId.put(column.columnId().toString(), column.physicalName());
            if (column.sourceName() != null) {
                physicalByName.put(column.sourceName(), column.physicalName());
            }
        }
        for (DatasetCatalogVO.CatalogColumnVO column : catalogTable.columns()) {
            String physical = physicalByColumnId.get(column.columnId().toString());
            if (physical != null) {
                physicalByName.putIfAbsent(column.name(), physical);
            }
        }
        requirePhysical(physicalTable.physicalTable());
        for (String physical : physicalByName.values()) {
            requirePhysicalColumn(physical);
        }

        String database = quote(clickHouseProperties.getDatabase());
        String tableSql = database + "." + quote(physicalTable.physicalTable());

        String deliveredAt = physicalByName.get("order_delivered_customer_date");
        String purchaseAt = physicalByName.get("order_purchase_timestamp");
        String estimatedAt = physicalByName.get("order_estimated_delivery_date");
        String category = physicalByName.get("product_category");
        String customerState = physicalByName.get("customer_state");
        String sellerState = physicalByName.get("seller_state");
        String review = physicalByName.get("review_score");
        String orderId = physicalByName.get("order_id");
        String purchaseMonth = physicalByName.get("purchase_month");
        String purchaseWeekday = physicalByName.get("purchase_weekday");
        String sameState = physicalByName.get("same_state");

        String deliveryDays = physicalByName.containsKey("total_delivery_days")
                ? "toFloat64OrNull(" + quote(physicalByName.get("total_delivery_days")) + ")"
                : textDiffDays(purchaseAt, deliveredAt);
        String delayDays = physicalByName.containsKey("delay_days")
                ? "toFloat64OrNull(" + quote(physicalByName.get("delay_days")) + ")"
                : textDiffDays(estimatedAt, deliveredAt);
        String isDelayed = physicalByName.containsKey("is_delayed")
                ? "toInt32OrNull(" + quote(physicalByName.get("is_delayed")) + ")"
                : (delayDays == null ? null : "if(" + delayDays + " > 0, 1, 0)");

        if (deliveryDays == null || delayDays == null || isDelayed == null) {
            throw new InvalidDatasetException(
                    "数据表缺少延误字段：需要 is_delayed/delay_days/total_delivery_days，"
                            + "或 order_purchase_timestamp、order_estimated_delivery_date、order_delivered_customer_date 三个时间戳");
        }

        String where = whereClause(query, purchaseAt, category, customerState, sellerState);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("datasetVersionId", catalog.datasetVersionId());
        result.put("tableId", tableId);
        result.put("tableName", catalogTable.tableName());
        result.put("rowCount", catalogTable.rowCount());
        result.put("derivedFromTimestamps", !physicalByName.containsKey("is_delayed"));
        result.put("columns", columns(physicalByName));
        result.put("filters", filterState(query, purchaseAt));

        try (ClickHouseClient client = clickHouseClientFactory.create(clickHouseProperties)) {
            String totalsSql = "SELECT count(*) AS orders,"
                    + " countIf(" + deliveryDays + " IS NOT NULL) AS delivered,"
                    + " countIf(" + isDelayed + " = 1) AS delayed,"
                    + " countIf(" + deliveryDays + " IS NOT NULL AND " + delayDays + " < 0) AS early,"
                    + " countIf(" + deliveryDays + " IS NOT NULL AND " + delayDays + " = 0) AS on_time,"
                    + " avgIf(" + deliveryDays + ", " + deliveryDays + " IS NOT NULL) AS avg_delivery_days,"
                    + " avgIf(" + delayDays + ", " + isDelayed + " = 1) AS avg_delay_days"
                    + " FROM " + tableSql + where;
            List<Object> totals = firstRow(client.query(totalsSql));

            long orders = longAt(totals, 0);
            long delivered = longAt(totals, 1);
            long delayed = longAt(totals, 2);
            long early = longAt(totals, 3);
            long onTime = longAt(totals, 4);

            Map<String, Object> totalsMap = new LinkedHashMap<>();
            totalsMap.put("orders", orders);
            totalsMap.put("delivered", delivered);
            totalsMap.put("delayed", delayed);
            totalsMap.put("delayRate", ratio(delayed, orders));
            result.put("totals", totalsMap);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("avgDeliveryDays", doubleAt(totals, 5));
            metrics.put("delayRate", ratio(delayed, orders));
            metrics.put("avgDelayDays", doubleAt(totals, 6));
            result.put("metrics", metrics);

            Map<String, Object> outcomes = new LinkedHashMap<>();
            outcomes.put("early", early);
            outcomes.put("onTime", onTime);
            outcomes.put("delayed", delayed);
            outcomes.put("earlyRate", ratio(early, delivered));
            outcomes.put("onTimeRate", ratio(onTime, delivered));
            outcomes.put("delayedRate", ratio(delayed, delivered));
            result.put("outcomes", outcomes);

            List<Map<String, Object>> dimensions = availableDimensions(
                    purchaseAt, purchaseMonth, purchaseWeekday, category, customerState, sellerState, sameState);
            result.put("availableDimensions", dimensions);
            result.put("dimensionOptions", dimensionOptions(client, tableSql, category, customerState, sellerState));
            result.put("groupBy", groupBy(client, tableSql, where, isDelayed, query, purchaseAt, purchaseMonth,
                    purchaseWeekday, category, customerState, sellerState, sameState, dimensions));

            String monthExpression = purchaseAt != null
                    ? "toMonth(parseDateTimeBestEffortOrNull(" + quote(purchaseAt) + "))"
                    : (purchaseMonth == null ? null : "toInt32OrNull(" + quote(purchaseMonth) + ")");
            String weekdayExpression = purchaseAt != null
                    ? "toDayOfWeek(parseDateTimeBestEffortOrNull(" + quote(purchaseAt) + "))"
                    : (purchaseWeekday == null ? null : "toInt32OrNull(" + quote(purchaseWeekday) + ") + 1");
            result.put("byMonth", monthExpression == null ? List.of()
                    : buckets(client, "SELECT " + monthExpression + " AS bucket,"
                            + " count(*) AS orders, countIf(" + isDelayed + " = 1) AS delayed"
                            + " FROM " + tableSql + andWhere(where, monthExpression + " IS NOT NULL")
                            + " GROUP BY bucket ORDER BY bucket", "month", null));
            result.put("byWeekday", weekdayExpression == null ? List.of()
                    : buckets(client, "SELECT " + weekdayExpression + " AS bucket,"
                            + " count(*) AS orders, countIf(" + isDelayed + " = 1) AS delayed"
                            + " FROM " + tableSql + andWhere(where, weekdayExpression + " IS NOT NULL")
                            + " GROUP BY bucket ORDER BY bucket", "weekday", WEEKDAY_LABELS));
            result.put("byCategory", category == null ? List.of()
                    : buckets(client, "SELECT " + quote(category) + " AS bucket, count(*) AS orders,"
                            + " countIf(" + isDelayed + " = 1) AS delayed"
                            + " FROM " + tableSql + where
                            + " GROUP BY bucket ORDER BY orders DESC LIMIT 12", "category", null));

            result.put("correlation", correlation(
                    client, database, physicalTable, snapshot, tableSql, deliveryDays,
                    purchaseAt, deliveredAt, review, orderId));
        }
        return result;
    }

    // ------------------------------------------------------------------ dimensions

    private List<Map<String, Object>> availableDimensions(
            String purchaseAt, String purchaseMonth, String purchaseWeekday,
            String category, String customerState, String sellerState, String sameState) {
        List<Map<String, Object>> dimensions = new ArrayList<>();
        if (purchaseAt != null || purchaseMonth != null) {
            dimensions.add(dimension("month", "按月份", "time"));
        }
        if (purchaseAt != null || purchaseWeekday != null) {
            dimensions.add(dimension("weekday", "按星期", "time"));
        }
        if (category != null) {
            dimensions.add(dimension("category", "按商品类目", "category"));
        }
        if (sellerState != null) {
            dimensions.add(dimension("seller_state", "按卖家州", "category"));
        }
        if (customerState != null) {
            dimensions.add(dimension("customer_state", "按客户州", "category"));
        }
        if (sameState != null) {
            dimensions.add(dimension("same_state", "按是否同州发货", "category"));
        }
        return dimensions;
    }

    private Map<String, Object> dimension(String key, String label, String type) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("label", label);
        item.put("type", type);
        return item;
    }

    private Map<String, Object> dimensionOptions(
            ClickHouseClient client, String tableSql, String category, String customerState, String sellerState) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("category", optionValues(client, tableSql, category));
        options.put("sellerState", optionValues(client, tableSql, sellerState));
        options.put("customerState", optionValues(client, tableSql, customerState));
        return options;
    }

    private List<String> optionValues(ClickHouseClient client, String tableSql, String column) {
        if (column == null) {
            return List.of();
        }
        String sql = "SELECT " + quote(column) + " AS value, count(*) AS orders"
                + " FROM " + tableSql
                + " WHERE " + quote(column) + " IS NOT NULL AND " + quote(column) + " != ''"
                + " GROUP BY value ORDER BY orders DESC LIMIT " + OPTION_LIMIT;
        List<String> values = new ArrayList<>();
        for (List<Object> row : client.query(sql)) {
            if (!row.isEmpty() && row.get(0) != null) {
                values.add(String.valueOf(row.get(0)));
            }
        }
        return values;
    }

    private Map<String, Object> groupBy(
            ClickHouseClient client, String tableSql, String where, String isDelayed,
            DelayAnalysisQuery query, String purchaseAt, String purchaseMonth, String purchaseWeekday,
            String category, String customerState, String sellerState, String sameState,
            List<Map<String, Object>> dimensions) {
        String requested = query.groupBy();
        boolean known = requested != null && !requested.isBlank()
                && dimensions.stream().anyMatch(item -> item.get("key").equals(requested));
        String key = known
                ? requested
                : (dimensions.isEmpty() ? null : String.valueOf(dimensions.get(0).get("key")));
        final String groupKey = key;
        Map<String, Object> block = new LinkedHashMap<>();
        if (groupKey == null) {
            block.put("key", null);
            block.put("label", null);
            block.put("buckets", List.of());
            return block;
        }
        String expression;
        String[] labels = null;
        int limit = BUCKET_LIMIT;
        switch (groupKey) {
            case "month" -> {
                expression = purchaseAt != null
                        ? "toMonth(parseDateTimeBestEffortOrNull(" + quote(purchaseAt) + "))"
                        : "toInt32OrNull(" + quote(purchaseMonth) + ")";
                limit = 12;
            }
            case "weekday" -> {
                expression = purchaseAt != null
                        ? "toDayOfWeek(parseDateTimeBestEffortOrNull(" + quote(purchaseAt) + "))"
                        : "toInt32OrNull(" + quote(purchaseWeekday) + ") + 1";
                labels = WEEKDAY_LABELS;
                limit = 7;
            }
            case "seller_state" -> expression = quote(sellerState);
            case "customer_state" -> expression = quote(customerState);
            case "same_state" -> expression = "if(toInt32OrNull(" + quote(sameState) + ") = 1, '同州', '跨州')";
            default -> expression = quote(category);
        }
        String sql = "SELECT " + expression + " AS bucket, count(*) AS orders,"
                + " countIf(" + isDelayed + " = 1) AS delayed"
                + " FROM " + tableSql + where
                + " GROUP BY bucket ORDER BY orders DESC LIMIT " + limit;
        if (groupKey.equals("month") || groupKey.equals("weekday")) {
            sql = sql.replace("ORDER BY orders DESC", "ORDER BY bucket ASC");
        }
        List<Map<String, Object>> buckets = buckets(client, sql, groupKey, labels);
        block.put("key", groupKey);
        block.put("label", dimensions.stream()
                .filter(item -> item.get("key").equals(groupKey))
                .map(item -> item.get("label"))
                .findFirst().orElse(groupKey));
        block.put("buckets", buckets);
        return block;
    }

    // ------------------------------------------------------------------ filters

    private String whereClause(
            DelayAnalysisQuery query, String purchaseAt, String category,
            String customerState, String sellerState) {
        List<String> conditions = new ArrayList<>();
        List<String> categories = clean(query.categories());
        if (!categories.isEmpty() && category != null) {
            conditions.add(quote(category) + " IN (" + quotedList(categories) + ")");
        }
        List<String> sellers = clean(query.sellerStates());
        if (!sellers.isEmpty() && sellerState != null) {
            conditions.add(quote(sellerState) + " IN (" + quotedList(sellers) + ")");
        }
        List<String> customers = clean(query.customerStates());
        if (!customers.isEmpty() && customerState != null) {
            conditions.add(quote(customerState) + " IN (" + quotedList(customers) + ")");
        }
        if (purchaseAt != null) {
            String parsed = "parseDateTimeBestEffortOrNull(" + quote(purchaseAt) + ")";
            if (query.from() != null && !query.from().isBlank()) {
                conditions.add(parsed + " >= toDate('" + escape(query.from()) + "')");
            }
            if (query.to() != null && !query.to().isBlank()) {
                conditions.add(parsed + " <= toDate('" + escape(query.to()) + "')");
            }
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private String andWhere(String where, String condition) {
        return where.isBlank() ? " WHERE " + condition : where + " AND " + condition;
    }

    private Map<String, Object> filterState(DelayAnalysisQuery query, String purchaseAt) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("groupBy", query.groupBy());
        filters.put("from", query.from());
        filters.put("to", query.to());
        filters.put("categories", clean(query.categories()));
        filters.put("sellerStates", clean(query.sellerStates()));
        filters.put("customerStates", clean(query.customerStates()));
        filters.put("timeRangeSupported", purchaseAt != null);
        return filters;
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String quotedList(List<String> values) {
        return String.join(",", values.stream().map(value -> "'" + escape(value) + "'").toList());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("'", "''");
    }

    // ------------------------------------------------------------------ correlation

    private Map<String, Object> correlation(
            ClickHouseClient client,
            String database,
            ExecutionSnapshotVO.SnapshotTableVO table,
            ExecutionSnapshotVO snapshot,
            String tableSql,
            String deliveryDays,
            String purchaseAt,
            String deliveredAt,
            String review,
            String orderId) {
        Map<String, Object> correlation = new LinkedHashMap<>();
        String days = (purchaseAt != null && deliveredAt != null)
                ? textDiffDays(purchaseAt, deliveredAt)
                : deliveryDays;
        String source = null;
        String scope = null;
        if (review != null && days != null) {
            source = "SELECT " + days + " AS days, toFloat64OrNull(" + quote(review) + ") AS score"
                    + " FROM " + tableSql
                    + " WHERE " + days + " IS NOT NULL AND toFloat64OrNull(" + quote(review) + ") IS NOT NULL";
            scope = "same-table";
        } else if (orderId != null && days != null) {
            ExecutionSnapshotVO.SnapshotTableVO reviews = findSibling(snapshot, table, "review_score");
            if (reviews != null) {
                String reviewOrder = sourceColumn(reviews, "order_id");
                String reviewScore = sourceColumn(reviews, "review_score");
                if (reviewOrder != null && reviewScore != null) {
                    source = "SELECT " + days + " AS days, toFloat64OrNull(r." + quote(reviewScore) + ") AS score"
                            + " FROM " + tableSql + " AS o"
                            + " INNER JOIN " + database + "." + quote(reviews.physicalTable()) + " AS r"
                            + " ON r." + quote(reviewOrder) + " = o." + quote(orderId)
                            + " WHERE " + days + " IS NOT NULL"
                            + " AND toFloat64OrNull(r." + quote(reviewScore) + ") IS NOT NULL";
                    scope = "joined:" + reviews.tableName();
                }
            }
        }
        if (source == null) {
            correlation.put("available", false);
            correlation.put("deliveryVsRating", null);
            correlation.put("ratingBuckets", List.of());
            correlation.put("reason", "当前数据表没有 review_score 字段，且同一数据集里没有可关联的评价表");
            return correlation;
        }
        Double value = doubleAt(firstRow(client.query("SELECT corr(days, score) FROM (" + source + ")")), 0);
        correlation.put("available", value != null);
        correlation.put("deliveryVsRating", value);
        correlation.put("scope", scope);
        correlation.put("reason", value == null ? "样本不足，无法计算相关系数" : null);
        correlation.put("ratingBuckets", buckets(client,
                "SELECT toInt32(round(assumeNotNull(score))) AS bucket, count(*) AS orders, avg(days) AS avg_days"
                        + " FROM (" + source + ")"
                        + " WHERE score BETWEEN 1 AND 5 GROUP BY bucket ORDER BY bucket", "score", null));
        return correlation;
    }

    private ExecutionSnapshotVO.SnapshotTableVO findSibling(
            ExecutionSnapshotVO snapshot,
            ExecutionSnapshotVO.SnapshotTableVO self,
            String columnName) {
        for (ExecutionSnapshotVO.SnapshotTableVO candidate : snapshot.tables()) {
            if (candidate.tableId().equals(self.tableId())) {
                continue;
            }
            if (PHYSICAL_TABLE.matcher(candidate.physicalTable()).matches()
                    && sourceColumn(candidate, columnName) != null
                    && sourceColumn(candidate, "order_id") != null) {
                return candidate;
            }
        }
        return null;
    }

    private String sourceColumn(ExecutionSnapshotVO.SnapshotTableVO table, String name) {
        for (ExecutionSnapshotVO.SnapshotColumnVO column : table.columns()) {
            if (name.equals(column.sourceName()) && PHYSICAL_COLUMN.matcher(column.physicalName()).matches()) {
                return column.physicalName();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private String textDiffDays(String from, String to) {
        if (from == null || to == null) {
            return null;
        }
        return "dateDiff('second', parseDateTimeBestEffortOrNull(" + quote(from) + "),"
                + " parseDateTimeBestEffortOrNull(" + quote(to) + ")) / 86400.0";
    }

    private List<Map<String, Object>> buckets(ClickHouseClient client, String sql, String keyName, String[] labels) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Object> row : client.query(sql)) {
            long orders = longAt(row, 1);
            long delayed = longAt(row, 2);
            Object key = row.isEmpty() ? null : row.get(0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(keyName, key == null ? null : String.valueOf(key));
            if (labels != null && key != null) {
                int index = (int) numeric(key) - 1;
                item.put("label", index >= 0 && index < labels.length ? labels[index] : String.valueOf(key));
            } else {
                item.put("label", key == null ? null : String.valueOf(key));
            }
            item.put("orders", orders);
            item.put("delayed", delayed);
            item.put("delayRate", ratio(delayed, orders));
            rows.add(item);
        }
        return rows;
    }

    private Map<String, Object> columns(Map<String, String> physicalByName) {
        Map<String, Object> columns = new LinkedHashMap<>();
        for (String name : List.of("is_delayed", "total_delivery_days", "delay_days",
                "order_purchase_timestamp", "order_estimated_delivery_date",
                "order_delivered_customer_date", "product_category", "customer_state",
                "seller_state", "review_score")) {
            columns.put(name, physicalByName.get(name));
        }
        return columns;
    }

    private static List<Object> firstRow(List<List<Object>> rows) {
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    private static long longAt(List<Object> row, int index) {
        return index < row.size() ? (long) numeric(row.get(index)) : 0L;
    }

    private static Double doubleAt(List<Object> row, int index) {
        if (index >= row.size() || row.get(index) == null) {
            return null;
        }
        double value = numeric(row.get(index));
        return Double.isFinite(value) ? value : null;
    }

    private static double numeric(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static Double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static void requirePhysical(String name) {
        if (name == null || !PHYSICAL_TABLE.matcher(name).matches()) {
            throw new InvalidDatasetException("数据表物理标识不合法");
        }
    }

    private static void requirePhysicalColumn(String name) {
        if (name == null || !PHYSICAL_COLUMN.matcher(name).matches()) {
            throw new InvalidDatasetException("数据表字段物理标识不合法");
        }
    }
}
