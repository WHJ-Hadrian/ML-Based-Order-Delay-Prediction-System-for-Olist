package com.olist.datasets.vo;

import java.util.List;
import java.util.UUID;

public record ExecutionSnapshotVO(
        UUID datasetVersionId,
        String status,
        String schemaHash,
        String manifestHash,
        String clickhouseDatabase,
        long rowCount,
        List<SnapshotTableVO> tables) {

    public record SnapshotTableVO(
            UUID tableId,
            String tableKey,
            String tableName,
            String physicalTable,
            long rowCount,
            List<SnapshotColumnVO> columns) {
    }

    public record SnapshotColumnVO(
            UUID columnId,
            UUID fieldId,
            String sourceName,
            String physicalName,
            String logicalType,
            String clickhouseType,
            boolean nullable) {
    }
}
