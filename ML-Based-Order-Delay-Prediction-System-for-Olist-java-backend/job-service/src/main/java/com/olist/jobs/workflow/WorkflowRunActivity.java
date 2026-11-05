package com.olist.jobs.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface WorkflowRunActivity {
    @ActivityMethod
    void execute(String runId, String ownerId, String snapshotJson);

    @ActivityMethod
    void cancel(String runId);
}
