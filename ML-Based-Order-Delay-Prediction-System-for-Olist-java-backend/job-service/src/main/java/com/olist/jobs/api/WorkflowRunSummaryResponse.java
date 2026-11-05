package com.olist.jobs.api;

import com.olist.jobs.entity.WorkflowRun;
import java.time.Instant;
import java.util.UUID;

public record WorkflowRunSummaryResponse(
        UUID id,
        UUID workflowId,
        String workflowName,
        long workflowRevision,
        String status,
        int progress,
        String errorMessage,
        UUID retryOfId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {

    public static WorkflowRunSummaryResponse from(WorkflowRun run) {
        return new WorkflowRunSummaryResponse(run.getId(), run.getWorkflowId(), run.getWorkflowName(),
                run.getWorkflowRevision(), run.getStatus(), run.getProgress(), run.getErrorMessage(),
                run.getRetryOfId(), run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt(), run.getUpdatedAt());
    }
}
