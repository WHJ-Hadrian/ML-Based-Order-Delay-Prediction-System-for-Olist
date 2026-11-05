package com.olist.jobs.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRunResponse(
        UUID id,
        UUID workflowId,
        String workflowName,
        long workflowRevision,
        String status,
        int progress,
        Object output,
        String errorMessage,
        UUID retryOfId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt,
        List<WorkflowRunNodeResponse> nodes) { }
