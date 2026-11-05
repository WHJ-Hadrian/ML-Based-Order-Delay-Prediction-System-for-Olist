package com.olist.jobs.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperties({
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "jobs.runner", havingValue = "temporal", matchIfMissing = true),
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "jobs.temporal.worker.enabled", havingValue = "true")
})
public class WorkflowRunWorkerConfiguration {
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    WorkerFactory workflowWorkerFactory(
            WorkflowClient workflowClient,
            WorkflowRunActivity activity,
            @Value("${jobs.temporal.task-queue:seek-dataset-workflows}") String taskQueue) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        Worker worker = factory.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(WorkflowRunWorkflowImpl.class);
        worker.registerActivitiesImplementations(activity);
        return factory;
    }
}
