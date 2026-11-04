package com.olist.models.api;

import java.time.Instant;
import java.util.UUID;

public record ModelWorkflowSummaryResponse(
        UUID id,
        String name,
        long revision,
        Instant createdAt,
        Instant updatedAt) {
}
