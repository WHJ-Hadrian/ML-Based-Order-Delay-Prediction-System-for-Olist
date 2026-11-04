package com.olist.jobs.api;

import java.time.Instant;
import java.util.UUID;

public record WorkflowSummaryResponse(UUID id, String name, long revision, Instant createdAt, Instant updatedAt) { }
