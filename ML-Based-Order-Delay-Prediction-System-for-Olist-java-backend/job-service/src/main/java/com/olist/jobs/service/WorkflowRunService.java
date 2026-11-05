package com.olist.jobs.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.jobs.api.WorkflowRunNodeResponse;
import com.olist.jobs.api.WorkflowRunResponse;
import com.olist.jobs.api.WorkflowRunRequest;
import com.olist.jobs.entity.WorkflowDefinition;
import com.olist.jobs.entity.WorkflowRun;
import com.olist.jobs.entity.WorkflowRunNode;
import com.olist.jobs.repository.WorkflowDefinitionRepository;
import com.olist.jobs.repository.WorkflowRunNodeRepository;
import com.olist.jobs.repository.WorkflowRunRepository;
import com.olist.jobs.workflow.WorkflowRunStarter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkflowRunService {
    private static final int MAX_SOURCE_NODES = 20;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private final WorkflowDefinitionRepository workflows;
    private final WorkflowRunRepository runs;
    private final WorkflowRunNodeRepository nodes;
    private final WorkflowRunStarter starter;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public WorkflowRunService(
            WorkflowDefinitionRepository workflows,
            WorkflowRunRepository runs,
            WorkflowRunNodeRepository nodes,
            WorkflowRunStarter starter,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.workflows = workflows;
        this.runs = runs;
        this.nodes = nodes;
        this.starter = starter;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public WorkflowRunResponse start(String ownerId, UUID workflowId, WorkflowRunRequest request) {
        WorkflowDefinition definition = workflows.findByIdAndOwnerId(workflowId, ownerId)
                .orElseThrow(this::notFound);
        if (request.expectedRevision() != definition.getRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工作流版本已变化，请重新载入后再运行");
        }
        JsonNode snapshot = readSnapshot(definition.getDocumentJson());
        validateExecutable(snapshot);
        WorkflowRun run = createRun(definition, ownerId, snapshot, null);
        try {
            starter.start(run);
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> {
                WorkflowRun persisted = requireOwned(ownerId, run.getId());
                persisted.fail("无法提交到工作流运行服务，请检查 Temporal Worker 状态", Instant.now());
                runs.save(persisted);
            });
        }
        return get(ownerId, run.getId());
    }

    public WorkflowRunResponse retry(String ownerId, UUID runId) {
        WorkflowRun original = requireOwned(ownerId, runId);
        if (!"FAILED".equals(original.getStatus()) && !"CANCELED".equals(original.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有失败或已取消的运行可以重试");
        }
        JsonNode snapshot = readSnapshot(original.getSnapshotJson());
        WorkflowRun retry = createRun(original, ownerId, snapshot, original.getId());
        try {
            starter.start(retry);
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> {
                WorkflowRun persisted = requireOwned(ownerId, retry.getId());
                persisted.fail("无法提交到工作流运行服务，请检查 Temporal Worker 状态", Instant.now());
                runs.save(persisted);
            });
        }
        return get(ownerId, retry.getId());
    }

    public WorkflowRunResponse cancel(String ownerId, UUID runId) {
        WorkflowRun run = requireOwned(ownerId, runId);
        if (!"QUEUED".equals(run.getStatus()) && !"RUNNING".equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "此运行当前无法取消");
        }
        starter.cancel(runId);
        transactions.executeWithoutResult(status -> {
            WorkflowRun persisted = requireOwned(ownerId, runId);
            persisted.requestCancellation(Instant.now());
            runs.save(persisted);
        });
        return get(ownerId, runId);
    }

    public List<WorkflowRunResponse> list(String ownerId, UUID workflowId) {
        requireWorkflow(ownerId, workflowId);
        return runs.findTop50ByWorkflowIdAndOwnerIdOrderByCreatedAtDesc(workflowId, ownerId)
                .stream().map(this::response).toList();
    }

    public WorkflowRunResponse get(String ownerId, UUID runId) {
        return response(requireOwned(ownerId, runId));
    }

    public void validateOwner(UUID runId, String ownerId) {
        requireOwned(ownerId, runId);
    }

    private WorkflowRun createRun(
            WorkflowDefinition definition, String ownerId, JsonNode snapshot, UUID retryOfId) {
        return createRun(definition.getId(), definition.getName(), definition.getRevision(),
                ownerId, snapshot, retryOfId);
    }

    private WorkflowRun createRun(
            WorkflowRun original, String ownerId, JsonNode snapshot, UUID retryOfId) {
        return createRun(original.getWorkflowId(), original.getWorkflowName(), original.getWorkflowRevision(),
                ownerId, snapshot, retryOfId);
    }

    private WorkflowRun createRun(
            UUID workflowId, String workflowName, long revision, String ownerId,
            JsonNode snapshot, UUID retryOfId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String snapshotJson = writeJson(snapshot);
        WorkflowRun run = new WorkflowRun(id, workflowId, ownerId, workflowName, revision,
                snapshotJson, retryOfId, now);
        transactions.executeWithoutResult(status -> {
            runs.saveAndFlush(run);
            for (JsonNode node : snapshot.path("nodes")) {
                nodes.save(new WorkflowRunNode(UUID.randomUUID(), id,
                        node.path("id").asText(), node.path("type").asText(), now));
            }
            nodes.flush();
        });
        return runs.findByIdAndOwnerId(id, ownerId).orElseThrow(this::notFound);
    }

    private void validateExecutable(JsonNode document) {
        JsonNode runNodes = document.path("nodes");
        JsonNode edges = document.path("edges");
        if (!runNodes.isArray() || runNodes.isEmpty() || runNodes.size() > MAX_SOURCE_NODES
                || !edges.isArray() || !edges.isEmpty()) {
            throw badRequest("当前运行器支持 1 到 20 个独立数据源节点；其他 DAG 节点执行器尚未启用");
        }
        for (JsonNode node : runNodes) {
            if (!"DATA_SOURCE".equals(node.path("type").asText())) {
                throw badRequest("当前运行器只支持数据源节点，暂不执行「" + node.path("type").asText() + "」节点");
            }
            JsonNode config = node.path("data").path("config");
            if (!validUuid(config.path("datasetVersionId").asText())
                    || !validUuid(config.path("tableId").asText())) {
                throw badRequest("每个数据源节点都必须固定一个已就绪的数据集版本和数据表");
            }
        }
    }

    private boolean validUuid(String value) { return UUID_PATTERN.matcher(value).matches(); }

    private JsonNode readSnapshot(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已保存的工作流文档无法解析", exception);
        }
    }

    private String writeJson(JsonNode json) {
        try {
            return mapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作流快照无法保存", exception);
        }
    }

    private WorkflowDefinition requireWorkflow(String ownerId, UUID workflowId) {
        return workflows.findByIdAndOwnerId(workflowId, ownerId).orElseThrow(this::notFound);
    }

    private WorkflowRun requireOwned(String ownerId, UUID runId) {
        return runs.findByIdAndOwnerId(runId, ownerId).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到此工作流运行");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private WorkflowRunResponse response(WorkflowRun run) {
        List<WorkflowRunNodeResponse> nodeResponses = nodes.findByRunIdOrderByCreatedAtAsc(run.getId())
                .stream().map(node -> new WorkflowRunNodeResponse(node.getNodeId(), node.getNodeType(),
                        node.getStatus(), parseJson(node.getResultJson()), node.getErrorMessage(),
                        node.getStartedAt(), node.getFinishedAt()))
                .toList();
        return new WorkflowRunResponse(run.getId(), run.getWorkflowId(), run.getWorkflowName(),
                run.getWorkflowRevision(), run.getStatus(), run.getProgress(), parseJson(run.getOutputJson()),
                run.getErrorMessage(), run.getRetryOfId(), run.getCreatedAt(), run.getStartedAt(),
                run.getFinishedAt(), run.getUpdatedAt(), nodeResponses);
    }

    private Object parseJson(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }
}
