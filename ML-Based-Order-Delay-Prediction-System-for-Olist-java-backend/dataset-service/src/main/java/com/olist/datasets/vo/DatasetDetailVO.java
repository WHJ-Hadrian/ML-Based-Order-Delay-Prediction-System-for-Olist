package com.olist.datasets.vo;

import com.olist.datasets.enums.DatasetFormat;
import com.olist.datasets.enums.DatasetStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DatasetDetailVO(
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
        List<DatasetFileVO> files,
        List<DatasetTableVO> tables,
        UUID datasetId,
        UUID datasetVersionId) {
}
