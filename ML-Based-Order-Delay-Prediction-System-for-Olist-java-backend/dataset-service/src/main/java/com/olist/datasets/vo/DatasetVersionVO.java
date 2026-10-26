package com.olist.datasets.vo;

import com.olist.datasets.enums.DatasetStatus;
import java.time.Instant;
import java.util.UUID;

public record DatasetVersionVO(
        UUID datasetVersionId,
        UUID datasetId,
        int versionNo,
        UUID parentVersionId,
        DatasetStatus status,
        String originalFilename,
        int fileCount,
        int tableCount,
        long rowCount,
        String manifestHash,
        String schemaHash,
        Instant readyAt,
        Instant createdAt,
        Instant updatedAt) {
}