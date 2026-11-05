package com.olist.jobs.workflow;

import java.util.UUID;

public interface WorkflowRunStarter {
    void start(com.olist.jobs.entity.WorkflowRun run);

    void cancel(UUID runId);
}
