package com.olist.jobs.api;

import java.time.Instant;

public record WorkflowRunNodeResponse(
        String nodeId,
        String nodeType,
        String status,
        Object result,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt) { }
