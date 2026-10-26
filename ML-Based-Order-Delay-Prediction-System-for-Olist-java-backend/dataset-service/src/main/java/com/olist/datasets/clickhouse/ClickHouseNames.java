package com.olist.datasets.clickhouse;

import java.util.Locale;
import java.util.UUID;

public final class ClickHouseNames {

    private ClickHouseNames() {
    }

    public static String table(UUID datasetVersionId, UUID tableId) {
        return "ds_" + compact(datasetVersionId) + "_" + compact(tableId);
    }

    public static String column(int ordinal) {
        return "c" + String.format(Locale.ROOT, "%03d", ordinal);
    }

    public static String type(String logicalType, boolean nullable) {
        String baseType = switch (logicalType == null ? "" : logicalType.toUpperCase(Locale.ROOT)) {
            case "INTEGER", "LONG", "INT64" -> "Int64";
            case "DOUBLE", "FLOAT", "FLOAT64" -> "Float64";
            case "BOOLEAN", "BOOL" -> "Bool";
            default -> "String";
        };
        return nullable ? "Nullable(" + baseType + ")" : baseType;
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }
}