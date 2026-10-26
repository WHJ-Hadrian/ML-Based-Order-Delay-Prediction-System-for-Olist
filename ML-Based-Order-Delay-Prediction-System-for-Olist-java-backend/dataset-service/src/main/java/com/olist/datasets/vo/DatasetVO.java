package com.olist.datasets.vo;

import com.olist.datasets.enums.DatasetStatus;
import com.olist.datasets.enums.DatasetFormat;
import java.time.Instant;
import java.util.UUID;

public record DatasetVO(
        UUID id,
        String name,
        String description,
        String originalFilename,
        DatasetFormat format,
        long sizeBytes,
        String sha256,
        String contentType,
        DatasetStatus status,
        int tableCount,
        long rowCount,
        int fileCount,
        Instant createdAt,
        Instant updatedAt,
        UUID datasetId,
        UUID datasetVersionId) {
}
