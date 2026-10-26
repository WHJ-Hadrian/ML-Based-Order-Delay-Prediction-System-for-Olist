package com.olist.datasets.vo;

import java.util.List;
import java.util.UUID;

public record ExecutionSnapshotVO(
        UUID datasetVersionId,
        String schemaHash,
        String manifestHash,
        String clickhouseDatabase,
        List<SnapshotTableVO> tables) {

    public record SnapshotTableVO(
            UUID tableId,
            String physicalTable,
            List<SnapshotColumnVO> columns) {
    }

    public record SnapshotColumnVO(
            UUID columnId,
            String physicalName,
            String clickhouseType,
            boolean nullable) {
    }
}