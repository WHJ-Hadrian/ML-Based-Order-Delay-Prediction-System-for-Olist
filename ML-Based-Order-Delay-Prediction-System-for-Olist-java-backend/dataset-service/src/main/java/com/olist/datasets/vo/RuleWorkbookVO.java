package com.olist.datasets.vo;

import com.olist.datasets.enums.RuleWorkbookStatus;
import java.time.Instant;
import java.util.UUID;

public record RuleWorkbookVO(
        UUID id,
        String name,
        String description,
        UUID datasetVersionId,
        RuleWorkbookStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
}