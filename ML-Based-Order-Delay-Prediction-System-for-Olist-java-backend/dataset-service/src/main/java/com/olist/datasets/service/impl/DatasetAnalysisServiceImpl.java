package com.olist.datasets.service.impl;

import com.olist.datasets.clickhouse.ClickHouseClient;
import com.olist.datasets.clickhouse.ClickHouseClientFactory;
import com.olist.datasets.config.ClickHouseProperties;
import com.olist.datasets.exception.DatasetNotReadyException;
import com.olist.datasets.exception.InvalidDatasetException;
import com.olist.datasets.service.DatasetAnalysisService;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.DatasetCatalogVO;
import com.olist.datasets.vo.DatasetColumnAnalysisVO;
import com.olist.datasets.vo.DatasetColumnRelationVO;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DatasetAnalysisServiceImpl implements DatasetAnalysisService {

    private static final int HISTOGRAM_BUCKETS = 12;
    private static final int TOP_CATEGORY_LIMIT = 12;
    private static final int RELATION_SAMPLE_LIMIT = 250;
    private static final int RELATION_MAX_ROWS_TO_READ = 5_000_000;
    private static final long RELATION_MAX_BYTES_TO_READ = 1_000_000_000L;
    private static final Pattern PHYSICAL_TABLE = Pattern.compile("ds_[0-9a-f]{32}_[0-9a-f]{32}");
    private static final Pattern PHYSICAL_COLUMN = Pattern.compile("c[0-9]{3,}");

    private final DatasetService datasetService;
    private final ClickHouseProperties clickHouseProperties;
    private final ClickHouseClientFactory clickHouseClientFactory;

    public DatasetAnalysisServiceImpl(
            DatasetService datasetService,
            ClickHouseProperties clickHouseProperties,
            ClickHouseClientFactory clickHouseClientFactory) {
        this.datasetService = datasetService;
        this.clickHouseProperties = clickHouseProperties;
        this.clickHouseClientFactory = clickHouseClientFactory;
    }

    @Override
    public DatasetColumnAnalysisVO analyzeColumn(UUID datasetId, UUID tableId, UUID columnId) {
        DatasetCatalogVO catalog = datasetService.catalog(datasetId);
        if (!clickHouseProperties.isEnabled()) {
            throw new DatasetNotReadyException("分析查询未启用 ClickHouse 存储");
        }
        DatasetCatalogVO.CatalogTableVO catalogTable = catalog.tables().stream()
                .filter(table -> table.tableId().equals(tableId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("数据表不属于所选数据集版本"));
        DatasetCatalogVO.CatalogColumnVO catalogColumn = catalogTable.columns().stream()
                .filter(column -> column.columnId().equals(columnId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("字段不属于所选数据表"));

        ExecutionSnapshotVO snapshot = datasetService.executionSnapshot(datasetId);
        ExecutionSnapshotVO.SnapshotTableVO physicalTable = snapshot.tables().stream()
                .filter(table -> table.tableId().equals(tableId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("数据表缺少可查询的物理表"));
        ExecutionSnapshotVO.SnapshotColumnVO physicalColumn = physicalTable.columns().stream()
                .filter(column -> column.columnId().equals(columnId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("字段缺少可查询的物理列"));
        requireGeneratedIdentifiers(physicalTable.physicalTable(), physicalColumn.physicalName());

        String tableSql = quoteIdentifier(clickHouseProperties.getDatabase())
                + "." + quoteIdentifier(physicalTable.physicalTable());
        String columnSql = quoteIdentifier(physicalColumn.physicalName());
        boolean numeric = isNumericType(physicalColumn.clickhouseType());
        String type = physicalColumn.logicalType() == null
                ? catalogColumn.logicalType()
                : physicalColumn.logicalType();

        try (ClickHouseClient client = clickHouseClientFactory.create(clickHouseProperties)) {
            List<List<Object>> statsRows = client.query(withQueryLimits(statsSql(tableSql, columnSql, numeric)));
            List<Object> stats = statsRows.isEmpty() ? List.of() : statsRows.get(0);
            long missingCount = longAt(stats, 0);
            long uniqueCount = longAt(stats, 1);
            Double minimum = numeric ? doubleAt(stats, 2) : null;
            Double maximum = numeric ? doubleAt(stats, 3) : null;
            List<DatasetColumnAnalysisVO.PointVO> points = numeric
                    ? histogram(client, tableSql, columnSql, minimum, maximum)
                    : categories(client, tableSql, columnSql);
            return new DatasetColumnAnalysisVO(
                    catalog.datasetVersionId(),
                    tableId,
                    columnId,
                    catalogTable.tableName(),
                    catalogColumn.name(),
                    type,
                    numeric ? "histogram" : "categories",
                    catalogTable.rowCount(),
                    missingCount,
                    uniqueCount,
                    minimum,
                    maximum,
                    points);
        }
    }

    @Override
    public DatasetColumnRelationVO analyzeRelation(
            UUID datasetId, UUID tableId, UUID xColumnId, UUID yColumnId) {
        if (xColumnId.equals(yColumnId)) {
            throw new InvalidDatasetException("请选择两个不同的字段进行关系分析");
        }
        DatasetCatalogVO catalog = datasetService.catalog(datasetId);
        if (!clickHouseProperties.isEnabled()) {
            throw new DatasetNotReadyException("分析查询未启用 ClickHouse 存储");
        }
        DatasetCatalogVO.CatalogTableVO catalogTable = catalog.tables().stream()
                .filter(table -> table.tableId().equals(tableId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("数据表不属于所选数据集版本"));
        DatasetCatalogVO.CatalogColumnVO xCatalogColumn = catalogTable.columns().stream()
                .filter(column -> column.columnId().equals(xColumnId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("X 字段不属于所选数据表"));
        DatasetCatalogVO.CatalogColumnVO yCatalogColumn = catalogTable.columns().stream()
                .filter(column -> column.columnId().equals(yColumnId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("Y 字段不属于所选数据表"));

        ExecutionSnapshotVO snapshot = datasetService.executionSnapshot(datasetId);
        ExecutionSnapshotVO.SnapshotTableVO physicalTable = snapshot.tables().stream()
                .filter(table -> table.tableId().equals(tableId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("数据表缺少可查询的物理表"));
        ExecutionSnapshotVO.SnapshotColumnVO xColumn = physicalTable.columns().stream()
                .filter(column -> column.columnId().equals(xColumnId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("X 字段缺少可查询的物理列"));
        ExecutionSnapshotVO.SnapshotColumnVO yColumn = physicalTable.columns().stream()
                .filter(column -> column.columnId().equals(yColumnId))
                .findFirst()
                .orElseThrow(() -> new InvalidDatasetException("Y 字段缺少可查询的物理列"));
        requireGeneratedIdentifiers(physicalTable.physicalTable(), xColumn.physicalName());
        requireGeneratedIdentifiers(physicalTable.physicalTable(), yColumn.physicalName());
        String tableSql = quoteIdentifier(clickHouseProperties.getDatabase())
                + "." + quoteIdentifier(physicalTable.physicalTable());
        String xSql = quoteIdentifier(xColumn.physicalName());
        String ySql = quoteIdentifier(yColumn.physicalName());
        boolean xNumeric = isNumericType(xColumn.clickhouseType()) || isNumericType(xColumn.logicalType());
        boolean yNumeric = isNumericType(yColumn.clickhouseType()) || isNumericType(yColumn.logicalType());
        if (!xNumeric && !yNumeric) {
            throw new InvalidDatasetException("关系分析至少需要一个数值字段");
        }

        try (ClickHouseClient client = clickHouseClientFactory.create(clickHouseProperties)) {
            if (xNumeric && yNumeric) {
                String sql = "SELECT toFloat64OrNull(" + xSql + ") AS x_value, toFloat64OrNull("
                        + ySql + ") AS y_value FROM " + tableSql
                        + " WHERE isNotNull(toFloat64OrNull(" + xSql + "))"
                        + " AND isNotNull(toFloat64OrNull(" + ySql + "))"
                        + " ORDER BY cityHash64(toString(" + xSql + "), toString(" + ySql + "))"
                        + " LIMIT " + RELATION_SAMPLE_LIMIT;
                List<DatasetColumnRelationVO.PointVO> points = new ArrayList<>();
                for (List<Object> row : client.query(withRelationQueryLimits(sql))) {
                    if (row.size() >= 2 && row.get(0) instanceof Number x && row.get(1) instanceof Number y) {
                        points.add(new DatasetColumnRelationVO.PointVO(
                                Integer.toString(points.size() + 1), x.doubleValue(), y.doubleValue(), 1));
                    }
                }
                return new DatasetColumnRelationVO(catalog.datasetVersionId(), tableId,
                        xColumnId, yColumnId, xCatalogColumn.name(), yCatalogColumn.name(),
                        "scatter", null, null, points);
            }

            String groupSql = xNumeric ? ySql : xSql;
            String valueSql = xNumeric ? xSql : ySql;
            String sql = "SELECT if(isNull(" + groupSql + "), '(空值)', toString(" + groupSql
                    + ")) AS category, avg(toFloat64OrNull(" + valueSql + ")) AS average_value"
                    + ", countIf(isNotNull(toFloat64OrNull(" + valueSql + "))) AS value_count"
                    + " FROM " + tableSql + " GROUP BY " + groupSql
                    + " ORDER BY value_count DESC LIMIT " + TOP_CATEGORY_LIMIT;
            List<DatasetColumnRelationVO.PointVO> points = new ArrayList<>();
            for (List<Object> row : client.query(withRelationQueryLimits(sql))) {
                if (row.size() >= 3 && row.get(1) instanceof Number average) {
                    points.add(new DatasetColumnRelationVO.PointVO(
                            String.valueOf(row.get(0)), null, average.doubleValue(), numberAt(row, 2)));
                }
            }
            return new DatasetColumnRelationVO(catalog.datasetVersionId(), tableId,
                    xColumnId, yColumnId, xCatalogColumn.name(), yCatalogColumn.name(), "category-average",
                    xNumeric ? yCatalogColumn.name() : xCatalogColumn.name(),
                    xNumeric ? xCatalogColumn.name() : yCatalogColumn.name(), points);
        }
    }

    private String statsSql(String tableSql, String columnSql, boolean numeric) {
        String numericStats = numeric
                ? ", minOrNull(toFloat64OrNull(" + columnSql + ")) AS min_value"
                        + ", maxOrNull(toFloat64OrNull(" + columnSql + ")) AS max_value"
                : "";
        return "SELECT countIf(isNull(" + columnSql + ")) AS missing_count"
                + ", uniq(" + columnSql + ") AS unique_count"
                + numericStats
                + " FROM " + tableSql;
    }

    private List<DatasetColumnAnalysisVO.PointVO> histogram(
            ClickHouseClient client,
            String tableSql,
            String columnSql,
            Double minimum,
            Double maximum) {
        if (minimum == null || maximum == null) {
            return List.of();
        }

        Map<Integer, Long> counts = new HashMap<>();
        if (Double.compare(minimum, maximum) == 0) {
            String sql = "SELECT toUInt8(0) AS bucket, count() AS point_count FROM " + tableSql
                    + " WHERE isNotNull(toFloat64OrNull(" + columnSql + "))";
            addBucketCounts(counts, client.query(withQueryLimits(sql)));
        } else {
            double width = (maximum - minimum) / HISTOGRAM_BUCKETS;
            String minLiteral = decimalLiteral(minimum);
            String widthLiteral = decimalLiteral(width);
            String bucket = "least(" + (HISTOGRAM_BUCKETS - 1)
                    + ", greatest(0, toInt32(floor((toFloat64OrNull(" + columnSql + ") - "
                    + minLiteral + ") / " + widthLiteral + "))))";
            String sql = "SELECT " + bucket + " AS bucket, count() AS point_count FROM " + tableSql
                    + " WHERE isNotNull(toFloat64OrNull(" + columnSql + "))"
                    + " GROUP BY bucket ORDER BY bucket LIMIT " + HISTOGRAM_BUCKETS;
            addBucketCounts(counts, client.query(withQueryLimits(sql)));
        }

        List<DatasetColumnAnalysisVO.PointVO> points = new ArrayList<>(HISTOGRAM_BUCKETS);
        double width = (maximum - minimum) / HISTOGRAM_BUCKETS;
        for (int bucket = 0; bucket < HISTOGRAM_BUCKETS; bucket++) {
            double start = minimum + width * bucket;
            double end = bucket == HISTOGRAM_BUCKETS - 1 ? maximum : minimum + width * (bucket + 1);
            String label = Double.compare(minimum, maximum) == 0
                    ? decimalLabel(minimum)
                    : decimalLabel(start) + " - " + decimalLabel(end);
            points.add(new DatasetColumnAnalysisVO.PointVO(label, counts.getOrDefault(bucket, 0L)));
        }
        return points;
    }

    private List<DatasetColumnAnalysisVO.PointVO> categories(
            ClickHouseClient client, String tableSql, String columnSql) {
        String sql = "SELECT if(isNull(" + columnSql + "), '(空值)', toString(" + columnSql
                + ")) AS category, count() AS point_count FROM " + tableSql
                + " GROUP BY " + columnSql
                + " ORDER BY point_count DESC LIMIT " + TOP_CATEGORY_LIMIT;
        List<DatasetColumnAnalysisVO.PointVO> points = new ArrayList<>();
        for (List<Object> row : client.query(withQueryLimits(sql))) {
            if (row.size() >= 2) {
                points.add(new DatasetColumnAnalysisVO.PointVO(
                        String.valueOf(row.get(0)), numberAt(row, 1)));
            }
        }
        return points;
    }

    private void addBucketCounts(Map<Integer, Long> counts, List<List<Object>> rows) {
        for (List<Object> row : rows) {
            if (row.size() >= 2) {
                counts.put((int) numberAt(row, 0), numberAt(row, 1));
            }
        }
    }

    private boolean isNumericType(String clickhouseType) {
        if (clickhouseType == null) {
            return false;
        }
        String type = clickhouseType.replaceFirst("^Nullable\\((.*)\\)$", "$1");
        return type.matches("(?:U?Int[0-9]+|Float[0-9]+|Decimal(?:[0-9]+)?(?:\\(.*\\))?|INTEGER|LONG|DECIMAL|DOUBLE|FLOAT|FLOAT64|INT64)");
    }

    private void requireGeneratedIdentifiers(String tableName, String columnName) {
        if (tableName == null || !PHYSICAL_TABLE.matcher(tableName).matches()
                || columnName == null || !PHYSICAL_COLUMN.matcher(columnName).matches()) {
            throw new DatasetNotReadyException("数据集物理结构不符合平台生成规则，无法分析");
        }
    }

    private String quoteIdentifier(String value) {
        if (value == null || value.isBlank()) {
            throw new DatasetNotReadyException("ClickHouse 数据库配置无效");
        }
        return "`" + value.replace("`", "``") + "`";
    }

    private long longAt(List<Object> values, int index) {
        return index < values.size() && values.get(index) instanceof Number number
                ? number.longValue()
                : 0L;
    }

    private Double doubleAt(List<Object> values, int index) {
        return index < values.size() && values.get(index) instanceof Number number
                ? number.doubleValue()
                : null;
    }

    private long numberAt(List<Object> values, int index) {
        return values.get(index) instanceof Number number ? number.longValue() : 0L;
    }

    private String decimalLiteral(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private String decimalLabel(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private String withQueryLimits(String sql) {
        return sql + " SETTINGS max_execution_time = 30, max_result_rows = 32, result_overflow_mode = 'throw'";
    }

    private String withRelationQueryLimits(String sql) {
        return sql + " SETTINGS max_execution_time = 30, max_rows_to_read = " + RELATION_MAX_ROWS_TO_READ
                + ", max_bytes_to_read = " + RELATION_MAX_BYTES_TO_READ
                + ", max_result_rows = " + RELATION_SAMPLE_LIMIT
                + ", result_overflow_mode = 'throw'";
    }
}
