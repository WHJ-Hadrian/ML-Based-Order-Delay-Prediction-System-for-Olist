package com.olist.datasets.vo;

import java.util.List;
import java.util.UUID;

public record DatasetColumnAnalysisVO(
        UUID datasetVersionId,
        UUID tableId,
        UUID columnId,
        String tableName,
        String columnName,
        String logicalType,
        String chartType,
        long rowCount,
        long missingCount,
        long uniqueCountEstimate,
        Double minimum,
        Double maximum,
        List<PointVO> points) {

    public record PointVO(String label, long value) {
    }
}
