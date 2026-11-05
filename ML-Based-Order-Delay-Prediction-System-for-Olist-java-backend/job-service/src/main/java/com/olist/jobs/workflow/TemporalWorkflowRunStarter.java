package com.olist.jobs.workflow;

import com.olist.jobs.entity.WorkflowRun;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jobs.runner", havingValue = "temporal", matchIfMissing = true)
public class TemporalWorkflowRunStarter implements WorkflowRunStarter {
    private final WorkflowClient workflowClient;
    private final String taskQueue;

    public TemporalWorkflowRunStarter(
            WorkflowClient workflowClient,
            @Value("${jobs.temporal.task-queue:seek-dataset-workflows}") String taskQueue) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
    }

    @Override
    public void start(WorkflowRun run) {
        WorkflowRunWorkflow workflow = workflowClient.newWorkflowStub(
                WorkflowRunWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(run.getId().toString()).setTaskQueue(taskQueue).build());
        WorkflowClient.start(workflow::execute, run.getId().toString(), run.getOwnerId(), run.getSnapshotJson());
    }

    @Override
    public void cancel(UUID runId) {
        workflowClient.newUntypedWorkflowStub(runId.toString()).cancel();
    }
}
