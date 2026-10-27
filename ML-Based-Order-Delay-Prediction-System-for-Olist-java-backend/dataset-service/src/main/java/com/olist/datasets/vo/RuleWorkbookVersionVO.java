package com.olist.datasets.vo;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record RuleWorkbookVersionVO(
        UUID id,
        UUID workbookId,
        int versionNumber,
        UUID datasetVersionId,
        String status,
        String contentHash,
        Instant createdAt,
        JsonNode document,
        JsonNode compiledPlan) {
}