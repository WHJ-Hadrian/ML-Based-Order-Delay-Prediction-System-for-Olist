package com.olist.models.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TrainingRunResponse(
        UUID id,
        String status,
        String stage,
        int progress,
        String statusUrl,
        UUID datasetId,
        UUID datasetVersionId,
        UUID tableId,
        String name,
        String task,
        String algorithm,
        String targetColumn,
        List<String> featureColumns,
        double testSize,
        int randomState,
        UUID modelVersionId,
        String artifactId,
        Map<String, Object> result,
        String errorMessage,
        int attempts,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {
}