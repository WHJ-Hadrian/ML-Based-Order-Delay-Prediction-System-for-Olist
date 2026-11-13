package com.olist.datasets.service;

import com.olist.datasets.clickhouse.ClickHouseClient;
import com.olist.datasets.clickhouse.ClickHouseClientFactory;
import com.olist.datasets.config.ClickHouseProperties;
import com.olist.datasets.exception.DatasetNotReadyException;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.vo.DatasetCatalogVO;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 物流延误分析：订单量、签收量、延误量、配送耗时、延误天数、
 * 提前/按时/延误占比，以及按月份、星期、商品类目的延误率。
 */
@Service
public class DelayAnalysisService {

    private static final Pattern PHYSICAL_TABLE = Pattern.compile("ds_[0-9a-f]{32}_[0-9a-f]{32}");
    private static final Pattern PHYSICAL_COLUMN = Pattern.compile("c[0-9]{3,}");
    private static final String[] WEEKDAY_LABELS = {
            "周一", "周二", "周三", "周四", "周五", "周六", "周日"
    };

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

        Map<String, String> physicalByColumnId = new LinkedHashMap<>();
        for (ExecutionSnapshotVO.SnapshotColumnVO column : physicalTable.columns()) {
            physicalByColumnId.put(column.columnId().toString(), column.physicalName());
        }
        Map<String, String> physicalByName = new LinkedHashMap<>();
        for (DatasetCatalogVO.CatalogColumnVO column : catalogTable.columns()) {
            String physical = physicalByColumnId.get(column.columnId().toString());
            if (physical != null) {
                physicalByName.put(column.name(), physical);
            }
        }
        requirePhysical(physicalTable.physicalTable());
        for (String physical : physicalByName.values()) {
            requirePhysicalColumn(physical);
        }

        String tableSql = quote(clickHouseProperties.getDatabase()) + "." + quote(physicalTable.physicalTable());
        String isDelayed = integerExpression(physicalByName.get("is_delayed"), "is_delayed");
        String deliveryDays = doubleExpression(physicalByName.get("total_delivery_days"), "total_delivery_days");
        String delayDays = doubleExpression(physicalByName.get("delay_days"), "delay_days");
        String purchase = physicalByName.get("order_purchase_timestamp");
        String category = physicalByName.get("product_category");
        String review = physicalByName.get("review_score");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("datasetVersionId", catalog.datasetVersionId());
        result.put("tableId", tableId);
        result.put("tableName", catalogTable.tableName());
        result.put("rowCount", catalogTable.rowCount());
        result.put("columns", columns(physicalByName));

        try (ClickHouseClient client = clickHouseClientFactory.create(clickHouseProperties)) {
            String totalsSql = "SELECT count(*) AS orders,"
                    + " countIf(" + deliveryDays + " IS NOT NULL) AS delivered,"
                    + " countIf(" + isDelayed + " = 1) AS delayed,"
                    + " countIf(" + deliveryDays + " IS NOT NULL AND " + delayDays + " < 0) AS early,"
                    + " countIf(" + deliveryDays + " IS NOT NULL AND " + delayDays + " = 0) AS on_time,"
                    + " avgIf(" + deliveryDays + ", " + deliveryDays + " IS NOT NULL) AS avg_delivery_days,"
                    + " avgIf(" + delayDays + ", " + isDelayed + " = 1) AS avg_delay_days"
                    + " FROM " + tableSql;
            List<Object> totals = firstRow(client.query(totalsSql));

            long orders = longAt(totals, 0);
            long delivered = longAt(totals, 1);
            long delayed = longAt(totals, 2);
            long early = longAt(totals, 3);
            long onTime = longAt(totals, 4);
            Double avgDeliveryDays = doubleAt(totals, 5);
            Double avgDelayDays = doubleAt(totals, 6);

            Map<String, Object> totalsMap = new LinkedHashMap<>();
            totalsMap.put("orders", orders);
            totalsMap.put("delivered", delivered);
            totalsMap.put("delayed", delayed);
            totalsMap.put("delayRate", ratio(delayed, orders));
            result.put("totals", totalsMap);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("avgDeliveryDays", avgDeliveryDays);
            metrics.put("delayRate", ratio(delayed, orders));
            metrics.put("avgDelayDays", avgDelayDays);
            result.put("metrics", metrics);

            Map<String, Object> outcomes = new LinkedHashMap<>();
            outcomes.put("early", early);
            outcomes.put("onTime", onTime);
            outcomes.put("delayed", delayed);
            outcomes.put("earlyRate", ratio(early, delivered));
            outcomes.put("onTimeRate", ratio(onTime, delivered));
            outcomes.put("delayedRate", ratio(delayed, delivered));
            result.put("outcomes", outcomes);

            result.put("byMonth", purchase == null ? List.of()
                    : grouped(client, tableSql, "\"month\"", "toMonth(parseDateTimeBestEffortOrNull("
                            + quote(purchase) + "))", isDelayed, purchase,
                            "SELECT toMonth(parseDateTimeBestEffortOrNull(" + quote(purchase) + ")) AS \"month\","
                                    + " count(*) AS orders, countIf(" + isDelayed + " = 1) AS delayed"
                                    + " FROM " + tableSql
                                    + " WHERE parseDateTimeBestEffortOrNull(" + quote(purchase) + ") IS NOT NULL"
                                    + " GROUP BY \"month\" ORDER BY \"month\"", "month", null));
            result.put("byWeekday", purchase == null ? List.of()
                    : grouped(client, tableSql, "\"weekday\"", "toDayOfWeek(parseDateTimeBestEffortOrNull("
                            + quote(purchase) + "))", isDelayed, purchase,
                            "SELECT toDayOfWeek(parseDateTimeBestEffortOrNull(" + quote(purchase) + ")) AS \"weekday\","
                                    + " count(*) AS orders, countIf(" + isDelayed + " = 1) AS delayed"
                                    + " FROM " + tableSql
                                    + " WHERE parseDateTimeBestEffortOrNull(" + quote(purchase) + ") IS NOT NULL"
                                    + " GROUP BY \"weekday\" ORDER BY \"weekday\"", "weekday", WEEKDAY_LABELS));
            result.put("byCategory", category == null ? List.of()
                    : grouped(client, tableSql, "\"category\"", quote(category), isDelayed, category,
                            "SELECT " + quote(category) + " AS \"category\", count(*) AS orders,"
                                    + " countIf(" + isDelayed + " = 1) AS delayed"
                                    + " FROM " + tableSql
                                    + " GROUP BY \"category\" ORDER BY orders DESC LIMIT 12",
                            "category", null));

            Map<String, Object> correlation = new LinkedHashMap<>();
            if (review != null && deliveryDays != null) {
                String sql = "SELECT corr(" + deliveryDays + ", " + doubleExpression(review, "review_score") + ")"
                        + " FROM " + tableSql
                        + " WHERE " + deliveryDays + " IS NOT NULL";
                Double value = doubleAt(firstRow(client.query(sql)), 0);
                correlation.put("available", value != null);
                correlation.put("deliveryVsRating", value);
                correlation.put("reason", value == null ? "样本不足，无法计算相关系数" : null);
            } else {
                correlation.put("available", false);
                correlation.put("deliveryVsRating", null);
                correlation.put("reason", "当前数据表没有 review_score 字段（评分在原始评价表里，需要先关联）");
            }
            result.put("correlation", correlation);
        }
        return result;
    }

    private List<Map<String, Object>> grouped(
            ClickHouseClient client,
            String tableSql,
            String alias,
            String keyExpression,
            String isDelayed,
            String keyColumn,
            String sql,
            String keyName,
            String[] labels) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Object> row : client.query(sql)) {
            long orders = longAt(row, 1);
            long delayed = longAt(row, 2);
            Map<String, Object> item = new LinkedHashMap<>();
            Object key = row.isEmpty() ? null : row.get(0);
            item.put(keyName, key == null ? null : String.valueOf(key));
            if (labels != null && key != null) {
                int index = (int) numeric(key) - 1;
                item.put("label", index >= 0 && index < labels.length ? labels[index] : String.valueOf(key));
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
                "order_purchase_timestamp", "product_category", "review_score")) {
            columns.put(name, physicalByName.get(name));
        }
        return columns;
    }

    private String integerExpression(String physical, String logical) {
        if (physical == null) {
            throw new InvalidDatasetException("数据表缺少字段：" + logical);
        }
        return "toInt32OrNull(" + quote(physical) + ")";
    }

    private String doubleExpression(String physical, String logical) {
        if (physical == null) {
            throw new InvalidDatasetException("数据表缺少字段：" + logical);
        }
        return "toFloat64OrNull(" + quote(physical) + ")";
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
                .divide(BigDecimal.valueOf(denominator), 6, java.math.RoundingMode.HALF_UP)
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

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
