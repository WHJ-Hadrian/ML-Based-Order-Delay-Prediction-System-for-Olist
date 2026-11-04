package com.olist.models.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ModelVersionResponse(
        UUID id,
        String name,
        String task,
        String algorithm,
        UUID datasetId,
        UUID datasetVersionId,
        int datasetVersionNo,
        UUID tableId,
        String targetColumn,
        List<String> featureColumns,
        Map<String, String> featureTypes,
        double testSize,
        int randomState,
        Map<String, Double> metrics,
        int trainRows,
        int testRows,
        int droppedTargetRows,
        String artifactId,
        Map<String, Object> confusionMatrix,
        List<Map<String, Object>> evaluationSamples,
        Instant createdAt) {
}
