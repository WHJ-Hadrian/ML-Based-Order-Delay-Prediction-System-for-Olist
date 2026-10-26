package com.olist.datasets.vo;

import com.olist.datasets.enums.DatasetStatus;
import java.time.Instant;
import java.util.UUID;

public record DatasetReadinessVO(
        UUID datasetId,
        UUID datasetVersionId,
        DatasetStatus status,
        Instant readyAt,
        String manifestHash,
        String schemaHash,
        int fileCount,
        int tableCount,
        long rowCount) {
}
