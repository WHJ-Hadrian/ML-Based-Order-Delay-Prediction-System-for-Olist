package com.olist.jobs.api;

import java.time.Instant;
import java.util.List;

public record WorkflowRunNodeResponse(
        String nodeId,
        String nodeLabel,
        String nodeType,
        String status,
        Object result,
        String errorMessage,
        List<WorkflowRunLogResponse> logs,
        Instant startedAt,
        Instant finishedAt) { }
