package com.olist.datasets.vo;

import com.olist.datasets.enums.FieldLibrarySourceType;
import java.time.Instant;
import java.util.UUID;

public record FieldLibraryVO(
        UUID id,
        String name,
        String description,
        FieldLibrarySourceType sourceType,
        long fieldCount,
        int datasetCount,
        Instant createdAt,
        Instant updatedAt) {
}
