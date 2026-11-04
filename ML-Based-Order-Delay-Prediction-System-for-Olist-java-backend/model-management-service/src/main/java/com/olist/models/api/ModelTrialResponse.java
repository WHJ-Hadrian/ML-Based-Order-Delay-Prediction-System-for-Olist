package com.olist.models.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ModelTrialResponse(
        UUID id,
        UUID comparisonId,
        UUID modelId,
        UUID datasetId,
        UUID datasetVersionId,
        int datasetVersionNo,
        UUID tableId,
        String targetColumn,
        String task,
        int predictedRows,
        int evaluatedRows,
        int ignoredTargetRows,
        Map<String, Double> metrics,
        Map<String, Object> confusionMatrix,
        List<Map<String, Object>> evaluationSamples,
        Instant createdAt) {
}
