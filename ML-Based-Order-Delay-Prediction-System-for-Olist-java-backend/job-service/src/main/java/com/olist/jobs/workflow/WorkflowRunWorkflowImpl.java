package com.olist.jobs.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;

public class WorkflowRunWorkflowImpl implements WorkflowRunWorkflow {
    private final WorkflowRunActivity activity = Workflow.newActivityStub(
            WorkflowRunActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    @Override
    public void execute(String runId, String ownerId, String snapshotJson) {
        try {
            activity.execute(runId, ownerId, snapshotJson);
        } catch (CanceledFailure canceled) {
            Workflow.newDetachedCancellationScope(() -> activity.cancel(runId)).run();
            throw canceled;
        }
    }
}
