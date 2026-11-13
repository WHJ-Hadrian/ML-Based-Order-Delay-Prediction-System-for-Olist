package com.olist.models.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ModelPredictionResponse(
        UUID id,
        UUID modelId,
        String modelName,
        String status,
        Map<String, Object> input,
        Map<String, Object> result,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {
}
