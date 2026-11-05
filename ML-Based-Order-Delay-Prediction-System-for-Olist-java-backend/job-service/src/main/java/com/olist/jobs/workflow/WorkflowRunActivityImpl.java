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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import io.temporal.activity.Activity;
import com.olist.jobs.workflow.WorkflowDatasetClient.ExecutionSnapshot;
import com.olist.jobs.workflow.WorkflowDatasetClient.TablePreview;
import com.olist.jobs.workflow.WorkflowDatasetClient.TableSnapshot;

@Component
public class WorkflowRunActivityImpl implements WorkflowRunActivity {
    private final WorkflowRunRepository runs;
    private final WorkflowRunNodeRepository nodes;
    private final ObjectMapper mapper;
    private final WorkflowDatasetClient datasetClient;

    public WorkflowRunActivityImpl(
            WorkflowRunRepository runs,
            WorkflowRunNodeRepository nodes,
            ObjectMapper mapper,
            WorkflowDatasetClient datasetClient) {
        this.runs = runs;
        this.nodes = nodes;
        this.mapper = mapper;
        this.datasetClient = datasetClient;
    }

    @Override
    public void execute(String runIdText, String ownerId, String snapshotJson) {
        UUID runId = UUID.fromString(runIdText);
        markStarted(runId);
        try {
            JsonNode document = mapper.readTree(snapshotJson);
            ArrayNode outputNodes = mapper.createArrayNode();
            List<JsonNode> orderedNodes = topologicalNodes(document);
            Map<String, ObjectNode> resultsByNode = new HashMap<>();
            int total = orderedNodes.size();
            int completed = 0;
            for (JsonNode node : orderedNodes) {
                Activity.getExecutionContext().heartbeat("node:" + node.path("id").asText());
                String nodeId = node.path("id").asText();
                WorkflowRunNode runNode = nodes.findByRunIdAndNodeId(runId, nodeId)
                        .orElseThrow(() -> new IllegalStateException("运行节点记录不存在"));
                runNode.start(Instant.now());
                nodes.save(runNode);
                try {
                    ObjectNode result = "DATA_SOURCE".equals(node.path("type").asText())
                            ? inspectDataSource(ownerId, node)
                            : executeFilter(document, node, resultsByNode);
                    runNode.succeed(mapper.writeValueAsString(result), Instant.now());
                    outputNodes.add(result);
                    resultsByNode.put(nodeId, result);
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

    private List<JsonNode> topologicalNodes(JsonNode document) {
        Map<String, JsonNode> byId = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (JsonNode node : document.path("nodes")) {
            String id = node.path("id").asText();
            byId.put(id, node);
            outgoing.put(id, new ArrayList<>());
            indegree.put(id, 0);
        }
        for (JsonNode edge : document.path("edges")) {
            String source = edge.path("source").asText();
            String target = edge.path("target").asText();
            if (!outgoing.containsKey(source) || !indegree.containsKey(target)) {
                throw new IllegalStateException("工作流连线引用了不存在的节点");
            }
            outgoing.get(source).add(target);
            indegree.compute(target, (id, degree) -> degree + 1);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.addLast(id); });
        List<JsonNode> ordered = new ArrayList<>(byId.size());
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            ordered.add(byId.get(id));
            for (String target : outgoing.get(id)) {
                int next = indegree.compute(target, (key, degree) -> degree - 1);
                if (next == 0) ready.addLast(target);
            }
        }
        if (ordered.size() != byId.size()) throw new IllegalStateException("工作流包含循环依赖");
        return ordered;
    }

    private ObjectNode executeFilter(
            JsonNode document, JsonNode node, Map<String, ObjectNode> resultsByNode) {
        String nodeId = node.path("id").asText();
        String sourceNodeId = null;
        for (JsonNode edge : document.path("edges")) {
            if (nodeId.equals(edge.path("target").asText())) {
                sourceNodeId = edge.path("source").asText();
                break;
            }
        }
        ObjectNode source = sourceNodeId == null ? null : resultsByNode.get(sourceNodeId);
        if (source == null || !"DATA_SOURCE".equals(source.path("nodeType").asText())) {
            throw new IllegalStateException("筛选节点没有可用的数据源样本");
        }
        JsonNode config = node.path("data").path("config");
        String columnName = config.path("columnName").asText();
        String operator = config.path("operator").asText();
        String expected = config.path("value").asText("");
        JsonNode columns = source.path("sampleColumns");
        int columnIndex = -1;
        for (int index = 0; index < columns.size(); index++) {
            if (columnName.equals(columns.get(index).asText())) {
                columnIndex = index;
                break;
            }
        }
        if (columnIndex < 0) throw new IllegalStateException("筛选字段不在输入样本 schema 中");
        ArrayNode matchedRows = mapper.createArrayNode();
        int inputSampleRows = 0;
        for (JsonNode row : source.path("sampleRows")) {
            inputSampleRows++;
            String actual = columnIndex < row.size() && !row.get(columnIndex).isNull()
                    ? row.get(columnIndex).asText() : "";
            boolean matches = switch (operator) {
                case "EQUALS" -> actual.equals(expected);
                case "NOT_EQUALS" -> !actual.equals(expected);
                case "CONTAINS" -> actual.contains(expected);
                case "IS_EMPTY" -> actual.isBlank();
                case "IS_NOT_EMPTY" -> !actual.isBlank();
                default -> throw new IllegalStateException("筛选条件不受支持");
            };
            if (matches) matchedRows.add(row.deepCopy());
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("nodeId", nodeId);
        result.put("nodeType", "FILTER");
        result.put("inputNodeId", sourceNodeId);
        result.put("tableName", source.path("tableName").asText());
        result.put("datasetVersionId", source.path("datasetVersionId").asText());
        result.put("columnName", columnName);
        result.put("operator", operator);
        result.put("previewScope", "SOURCE_SAMPLE");
        result.put("inputRowCount", source.path("rowCount").asLong());
        result.put("sampleInputRows", inputSampleRows);
        result.put("sampleMatchedRows", matchedRows.size());
        result.put("sampleTruncated", source.path("sampleTruncated").asBoolean());
        result.set("sampleColumns", columns.deepCopy());
        result.set("sampleRows", matchedRows);
        return result;
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
        ExecutionSnapshot snapshot = datasetClient.executionSnapshot(UUID.fromString(versionId), ownerId);
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
        TablePreview preview = datasetClient.preview(snapshot.datasetVersionId(), table.tableId(), ownerId);
        if (preview == null) {
            throw new IllegalStateException("数据源样本预览不可用");
        }
        result.put("sampleLimit", preview.limit());
        result.put("sampleTotalRows", preview.totalRows());
        result.put("sampleTruncated", preview.truncated());
        result.set("sampleColumns", mapper.valueToTree(preview.columns()));
        result.set("sampleRows", mapper.valueToTree(preview.rows()));
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

}
