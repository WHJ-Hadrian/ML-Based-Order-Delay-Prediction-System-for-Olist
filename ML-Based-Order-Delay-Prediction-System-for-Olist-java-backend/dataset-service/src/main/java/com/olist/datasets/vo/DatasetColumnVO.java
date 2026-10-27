package com.olist.datasets.vo;

import java.util.UUID;

public record DatasetColumnVO(
        UUID id,
        UUID datasetVersionId,
        UUID tableId,
        String tableName,
        String columnName,
        int ordinal,
        String logicalType,
        String physicalName,
        String clickhouseType,
        boolean nullable) {
}
