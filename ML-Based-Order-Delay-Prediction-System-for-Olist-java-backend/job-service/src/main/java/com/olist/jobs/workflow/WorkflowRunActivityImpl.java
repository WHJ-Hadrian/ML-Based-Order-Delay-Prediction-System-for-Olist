package com.olist.jobs.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import io.temporal.activity.Activity;
import com.olist.jobs.workflow.WorkflowDatasetClient.ExecutionSnapshot;
import com.olist.jobs.workflow.WorkflowDatasetClient.TablePreview;
import com.olist.jobs.workflow.WorkflowDatasetClient.TableSnapshot;

@Component
public class WorkflowRunActivityImpl implements WorkflowRunActivity {
    private final WorkflowRunExecutionStore store;
    private final ObjectMapper mapper;
    private final WorkflowDatasetClient datasetClient;

    public WorkflowRunActivityImpl(
            WorkflowRunExecutionStore store,
            ObjectMapper mapper,
            WorkflowDatasetClient datasetClient) {
        this.store = store;
        this.mapper = mapper;
        this.datasetClient = datasetClient;
    }

    @Override
    public void execute(String runIdText, String ownerId, String snapshotJson) {
        execute(runIdText, ownerId, snapshotJson, null);
    }

    public void executeLocal(LocalWorkflowRunQueue.ClaimedRun claim) {
        execute(claim.id().toString(), claim.ownerId(), claim.snapshotJson(), claim);
    }

    private void execute(String runIdText, String ownerId, String snapshotJson,
            LocalWorkflowRunQueue.ClaimedRun claim) {
        UUID runId = UUID.fromString(runIdText);
        if (!store.start(runId, claim)) return;
        String activeNodeId = null;
        try {
            JsonNode document = mapper.readTree(snapshotJson);
            ArrayNode outputNodes = mapper.createArrayNode();
            List<JsonNode> orderedNodes = topologicalNodes(document);
            Map<String, ObjectNode> resultsByNode = new HashMap<>();
            int total = orderedNodes.size();
            int completed = 0;
            for (JsonNode node : orderedNodes) {
                if (claim == null) {
                    Activity.getExecutionContext().heartbeat("node:" + node.path("id").asText());
                } else if (store.cancelIfRequested(runId, claim)) {
                    return;
                }
                String nodeId = node.path("id").asText();
                activeNodeId = nodeId;
                String nodeType = node.path("type").asText();
                String startMessage = "DATA_SOURCE".equals(nodeType)
                        ? "校验固定数据集版本并读取有界样本" : "根据配置筛选上游样本";
                if (!store.startNode(runId, claim, nodeId, startMessage)) return;
                ObjectNode result;
                String summary;
                if ("DATA_SOURCE".equals(nodeType)) {
                    result = inspectDataSource(ownerId, node);
                    summary = "已读取 " + result.path("sampleRows").size()
                            + " 行样本、" + result.path("sampleColumns").size() + " 个字段";
                } else {
                    result = executeFilter(document, node, resultsByNode);
                    summary = "筛选完成，保留 "
                            + result.path("sampleMatchedRows").asInt() + " / "
                            + result.path("sampleInputRows").asInt() + " 行样本";
                }
                outputNodes.add(result);
                resultsByNode.put(nodeId, result);
                completed++;
                if (!store.completeNode(runId, claim, nodeId, mapper.writeValueAsString(result),
                        summary, (completed * 100) / total, mapper.writeValueAsString(outputNodes))) return;
                activeNodeId = null;
            }
            store.succeed(runId, claim, mapper.writeValueAsString(outputNodes));
        } catch (Exception exception) {
            if (!store.fail(runId, claim, activeNodeId, safeMessage(exception))) return;
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
        if (source == null || !("DATA_SOURCE".equals(source.path("nodeType").asText())
                || "FILTER".equals(source.path("nodeType").asText()))) {
            throw new IllegalStateException("筛选节点没有可用的上游样本结果");
        }
        JsonNode config = node.path("data").path("config");
        String columnName = config.path("columnName").asText();
        String operator = config.path("operator").asText();
        String expected = config.path("value").asText("");
        JsonNode columns = source.path("sampleColumns");
        int columnIndex = -1;
        String logicalType = "";
        for (int index = 0; index < columns.size(); index++) {
            if (columnName.equals(columns.get(index).asText())) {
                columnIndex = index;
                break;
            }
        }
        if (columnIndex < 0) throw new IllegalStateException("筛选字段不在输入样本 schema 中");
        for (JsonNode column : source.path("columns")) {
            if (columnName.equals(column.path("name").asText())) {
                logicalType = column.path("type").asText("");
                break;
            }
        }
        if (WorkflowSampleFilter.usesNumericComparison(operator) && !WorkflowSampleFilter.isNumericType(logicalType)) {
            throw new IllegalStateException("数值筛选条件只能用于数值类型字段");
        }
        String upperBound = config.path("valueTo").asText(null);
        ArrayNode matchedRows = mapper.createArrayNode();
        int inputSampleRows = 0;
        for (JsonNode row : source.path("sampleRows")) {
            inputSampleRows++;
            String actual = columnIndex < row.size() && !row.get(columnIndex).isNull()
                    ? row.get(columnIndex).asText() : "";
            boolean matches = WorkflowSampleFilter.matches(operator, actual, expected, upperBound);
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
        if (!operator.startsWith("IS_")) result.put("value", expected);
        if ("BETWEEN".equals(operator)) result.put("valueTo", config.path("valueTo").asText());
        result.put("previewScope", "SOURCE_SAMPLE");
        result.put("inputRowCount", source.path("rowCount").asLong());
        result.put("rowCount", source.path("rowCount").asLong());
        result.put("sampleInputRows", inputSampleRows);
        result.put("sampleMatchedRows", matchedRows.size());
        result.put("sampleTruncated", source.path("sampleTruncated").asBoolean());
        result.set("columns", source.path("columns").deepCopy());
        result.set("sampleColumns", columns.deepCopy());
        result.set("sampleRows", matchedRows);
        return result;
    }

    @Override
    public void cancel(String runIdText) {
        store.cancel(UUID.fromString(runIdText));
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

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "工作流节点执行失败";
        return message.length() > 1900 ? message.substring(0, 1900) : message;
    }

}
