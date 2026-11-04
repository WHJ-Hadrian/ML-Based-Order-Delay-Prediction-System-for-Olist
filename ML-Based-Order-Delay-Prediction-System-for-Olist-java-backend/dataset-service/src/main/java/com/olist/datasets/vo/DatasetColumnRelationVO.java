package com.olist.datasets.vo;

import java.util.List;
import java.util.UUID;

public record DatasetColumnRelationVO(
        UUID datasetVersionId,
        UUID tableId,
        UUID xColumnId,
        UUID yColumnId,
        String xColumnName,
        String yColumnName,
        String chartType,
        String groupColumnName,
        String valueColumnName,
        List<PointVO> points) {

    public record PointVO(String label, Double x, Double y, long rowCount) {
    }
}
