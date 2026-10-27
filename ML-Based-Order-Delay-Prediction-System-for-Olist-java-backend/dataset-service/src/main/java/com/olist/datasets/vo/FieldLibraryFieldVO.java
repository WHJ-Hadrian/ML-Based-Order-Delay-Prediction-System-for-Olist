package com.olist.datasets.vo;

import com.olist.datasets.enums.FieldDataType;
import com.olist.datasets.enums.FieldStatus;
import java.time.Instant;
import java.util.UUID;

public record FieldLibraryFieldVO(
        UUID id,
        UUID libraryId,
        String name,
        String displayName,
        FieldDataType dataType,
        String semanticRole,
        String sourceTable,
        String description,
        FieldStatus status,
        UUID sourceDatasetId,
        String sourceDatasetName,
        String sourceTableName,
        String sourceColumnName,
        Instant createdAt,
        Instant updatedAt) {
}
