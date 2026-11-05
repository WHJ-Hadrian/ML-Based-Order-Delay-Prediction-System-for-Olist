package com.olist.jobs.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.olist.jobs.entity.WorkflowRun;
import com.olist.jobs.entity.WorkflowRunNode;
import com.olist.jobs.repository.WorkflowRunNodeRepository;
import com.olist.jobs.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.transaction.annotation.Transactional;
import io.temporal.activity.Activity;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Component
public class WorkflowRunActivityImpl implements WorkflowRunActivity {
    private final WorkflowRunRepository runs;
    private final WorkflowRunNodeRepository nodes;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final String datasetServiceUrl;
    private final String workerToken;

    public WorkflowRunActivityImpl(
            WorkflowRunRepository runs,
            WorkflowRunNodeRepository nodes,
            ObjectMapper mapper,
            RestClient.Builder restClientBuilder,
            @Value("${jobs.dataset-service-url:http://localhost:8082}") String datasetServiceUrl,
            @Value("${jobs.workflow-worker.internal-token:}") String workerToken) {
        this.runs = runs;
        this.nodes = nodes;
        this.mapper = mapper;
        this.restClient = restClientBuilder.build();
        this.datasetServiceUrl = datasetServiceUrl;
        this.workerToken = workerToken;
    }

    @Override
    public void execute(String runIdText, String ownerId, String snapshotJson) {
        UUID runId = UUID.fromString(runIdText);
        markStarted(runId);
        try {
            JsonNode document = mapper.readTree(snapshotJson);
            ArrayNode outputNodes = mapper.createArrayNode();
            int total = document.path("nodes").size();
            int completed = 0;
            for (JsonNode node : document.path("nodes")) {
                Activity.getExecutionContext().heartbeat("node:" + node.path("id").asText());
                String nodeId = node.path("id").asText();
                WorkflowRunNode runNode = nodes.findByRunIdAndNodeId(runId, nodeId)
                        .orElseThrow(() -> new IllegalStateException("运行节点记录不存在"));
                runNode.start(Instant.now());
                nodes.save(runNode);
                try {
                    ObjectNode result = inspectDataSource(ownerId, node);
                    runNode.succeed(mapper.writeValueAsString(result), Instant.now());
                    outputNodes.add(result);
                    nodes.save(runNode);
                    completed++;
                    markProgress(runId, (completed * 100) / total, mapper.writeValueAsString(outputNodes));
                } catch (RuntimeException exception) {
                    runNode.fail(safeMessage(exception), Instant.now());
                    nodes.save(runNode);
                    throw exception;
                }
            }
            markSucceeded(runId, mapper.writeValueAsString(outputNodes));
        } catch (Exception exception) {
            markFailed(runId, safeMessage(exception));
            if (exception instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("工作流运行结果无法保存", exception);
        }
    }

    @Override
    @Transactional
    public void cancel(String runIdText) {
        WorkflowRun run = requireRun(UUID.fromString(runIdText));
        run.cancel("运行已取消", Instant.now());
        runs.save(run);
    }

    private void markStarted(UUID runId) {
        WorkflowRun run = requireRun(runId);
        run.start(Instant.now());
        runs.save(run);
    }

    private void markFailed(UUID runId, String message) {
        WorkflowRun run = requireRun(runId);
        run.fail(message, Instant.now());
        runs.save(run);
    }

    private void markProgress(UUID runId, int progress, String outputJson) {
        WorkflowRun run = requireRun(runId);
        run.progress(progress, outputJson, Instant.now());
        runs.save(run);
    }

    private void markSucceeded(UUID runId, String outputJson) {
        WorkflowRun run = requireRun(runId);
        run.succeed(outputJson, Instant.now());
        runs.save(run);
    }

    private ObjectNode inspectDataSource(String ownerId, JsonNode node) throws Exception {
        JsonNode config = node.path("data").path("config");
        String versionId = config.path("datasetVersionId").asText();
        String tableId = config.path("tableId").asText();
        ExecutionSnapshot snapshot = restClient.get()
                .uri(datasetServiceUrl + "/internal/workflow-datasets/{versionId}/execution-snapshot", versionId)
                .header("X-Workflow-Worker-Token", workerToken)
                .header("X-Workflow-Owner-Id", ownerId)
                .retrieve()
                .body(ExecutionSnapshot.class);
        if (snapshot == null || !"READY".equals(snapshot.status())) {
            throw new IllegalStateException("数据集版本未就绪或快照不可用");
        }
        TableSnapshot table = snapshot.tables().stream()
                .filter(candidate -> tableId.equals(candidate.tableId().toString()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("固定的数据表已不属于此数据集版本"));
        ObjectNode result = mapper.createObjectNode();
        result.put("nodeId", node.path("id").asText());
        result.put("nodeType", "DATA_SOURCE");
        result.put("datasetVersionId", snapshot.datasetVersionId().toString());
        result.put("tableId", table.tableId().toString());
        result.put("tableName", table.tableName());
        result.put("rowCount", table.rowCount());
        result.put("columnCount", table.columns().size());
        result.put("schemaHash", snapshot.schemaHash());
        ArrayNode columns = result.putArray("columns");
        table.columns().forEach(column -> {
            ObjectNode field = columns.addObject();
            field.put("columnId", column.columnId().toString());
            if (column.fieldId() != null) field.put("fieldId", column.fieldId().toString());
            field.put("name", column.sourceName());
            field.put("type", column.logicalType());
            field.put("nullable", column.nullable());
        });
        return result;
    }

    private WorkflowRun requireRun(UUID id) {
        return runs.findById(id).orElseThrow(() -> new IllegalStateException("工作流运行记录不存在"));
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "工作流节点执行失败";
        return message.length() > 1900 ? message.substring(0, 1900) : message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExecutionSnapshot(UUID datasetVersionId, String status, String schemaHash,
            java.util.List<TableSnapshot> tables) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TableSnapshot(UUID tableId, String tableName, long rowCount,
            java.util.List<ColumnSnapshot> columns) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ColumnSnapshot(UUID columnId, UUID fieldId, String sourceName,
            String logicalType, boolean nullable) { }
}
