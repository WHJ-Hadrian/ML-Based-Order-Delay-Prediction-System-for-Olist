package com.olist.datasets.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.olist.datasets.enums.RuleWorkbookStatus;
import java.time.Instant;
import java.util.UUID;

public record RuleWorkbookDetailVO(
        UUID id,
        String name,
        String description,
        UUID datasetVersionId,
        RuleWorkbookStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        JsonNode document) {
}