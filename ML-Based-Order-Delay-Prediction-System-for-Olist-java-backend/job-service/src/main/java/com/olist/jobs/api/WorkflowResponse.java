package com.olist.jobs.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record WorkflowResponse(UUID id, String name, long revision, JsonNode document,
        Instant createdAt, Instant updatedAt) { }
