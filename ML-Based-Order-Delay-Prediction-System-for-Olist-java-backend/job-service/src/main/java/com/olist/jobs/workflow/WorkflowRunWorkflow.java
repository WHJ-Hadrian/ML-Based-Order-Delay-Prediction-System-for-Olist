package com.olist.jobs.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface WorkflowRunWorkflow {
    @WorkflowMethod
    void execute(String runId, String ownerId, String snapshotJson);
}
