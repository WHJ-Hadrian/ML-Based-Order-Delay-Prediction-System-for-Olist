package com.olist.jobs.workflow;

import com.olist.jobs.entity.WorkflowRun;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jobs.runner", havingValue = "local")
public class LocalWorkflowRunStarter implements WorkflowRunStarter {
    @Override
    public void start(WorkflowRun run) {
        // Persisting the QUEUED run is the durable submission. The local worker claims it independently.
    }

    @Override
    public void cancel(UUID runId) {
        // Cancellation is persisted by WorkflowRunService and observed between worker nodes.
    }
}
