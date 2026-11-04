package com.olist.models.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ModelWorkflowResponse(
        UUID id,
        String name,
        long revision,
        JsonNode document,
        Instant createdAt,
        Instant updatedAt) {
}
