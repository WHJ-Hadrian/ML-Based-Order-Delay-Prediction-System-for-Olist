package com.olist.jobs.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WorkflowDatasetClient {
    private final RestClient restClient;
    private final String datasetServiceUrl;
    private final String workerToken;

    public WorkflowDatasetClient(
            RestClient.Builder restClientBuilder,
            @Value("${jobs.dataset-service-url:http://localhost:8082}") String datasetServiceUrl,
            @Value("${jobs.workflow-worker.internal-token:}") String workerToken) {
        this.restClient = restClientBuilder.build();
        this.datasetServiceUrl = datasetServiceUrl;
        this.workerToken = workerToken;
    }

    public ExecutionSnapshot executionSnapshot(UUID versionId, String ownerId) {
        return restClient.get()
                .uri(datasetServiceUrl + "/internal/workflow-datasets/{versionId}/execution-snapshot", versionId)
                .header("X-Workflow-Worker-Token", workerToken)
                .header("X-Workflow-Owner-Id", ownerId)
                .retrieve()
                .body(ExecutionSnapshot.class);
    }

    public TablePreview preview(UUID versionId, UUID tableId, String ownerId) {
        return restClient.get()
                .uri(datasetServiceUrl + "/internal/workflow-datasets/{versionId}/tables/{tableId}/preview",
                        versionId, tableId)
                .header("X-Workflow-Worker-Token", workerToken)
                .header("X-Workflow-Owner-Id", ownerId)
                .retrieve()
                .body(TablePreview.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionSnapshot(UUID datasetVersionId, String status, String schemaHash,
            List<TableSnapshot> tables) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableSnapshot(UUID tableId, String tableName, long rowCount,
            List<ColumnSnapshot> columns) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnSnapshot(UUID columnId, UUID fieldId, String sourceName,
            String logicalType, boolean nullable) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TablePreview(UUID tableId, String name, int limit,
            List<String> columns, List<List<String>> rows,
            List<List<String>> tailRows, long totalRows, boolean truncated) { }
}
