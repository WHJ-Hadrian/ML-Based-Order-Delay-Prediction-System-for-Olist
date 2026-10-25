package com.olist.datasets.vo;

import com.olist.datasets.enums.FieldDataType;
import com.olist.datasets.enums.FieldStatus;
import java.time.Instant;
import java.util.UUID;

public record FieldDefinitionVO(
        UUID id,
        String name,
        String displayName,
        FieldDataType dataType,
        String semanticRole,
        String sourceTable,
        String description,
        FieldStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
