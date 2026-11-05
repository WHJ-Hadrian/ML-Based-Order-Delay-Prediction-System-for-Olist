package com.olist.jobs.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.olist.jobs.entity.WorkflowRun;
import com.olist.jobs.entity.WorkflowRunNode;
import com.olist.jobs.repository.WorkflowRunNodeRepository;
import com.olist.jobs.repository.WorkflowRunRepository;
import com.olist.jobs.workflow.LocalWorkflowRunQueue.ClaimedRun;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkflowRunExecutionStore {
    private final WorkflowRunRepository runs;
    private final WorkflowRunNodeRepository nodes;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public WorkflowRunExecutionStore(WorkflowRunRepository runs, WorkflowRunNodeRepository nodes,
            ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.nodes = nodes;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean start(UUID id, ClaimedRun claim) {
        return update(id, claim, run -> run.start(Instant.now()));
    }

    public boolean startNode(UUID id, ClaimedRun claim, String nodeId, String message) {
        return update(id, claim, run -> {
            WorkflowRunNode node = requireNode(id, nodeId);
            node.start(Instant.now());
            appendLog(node, "INFO", "节点开始执行");
            appendLog(node, "INFO", message);
            nodes.save(node);
        });
    }

    public boolean completeNode(UUID id, ClaimedRun claim, String nodeId, String result,
            String summary, int progress, String output) {
        return update(id, claim, run -> {
            WorkflowRunNode node = requireNode(id, nodeId);
            node.succeed(result, Instant.now());
            appendLog(node, "INFO", summary);
            appendLog(node, "INFO", "节点执行成功");
            nodes.save(node);
            run.progress(progress, output, Instant.now());
        });
    }

    public boolean fail(UUID id, ClaimedRun claim, String nodeId, String message) {
        return update(id, claim, run -> {
            if (nodeId != null) {
                WorkflowRunNode node = requireNode(id, nodeId);
                node.fail(message, Instant.now());
                appendLog(node, "ERROR", message);
                nodes.save(node);
            }
            run.fail(message, Instant.now());
        });
    }

    public boolean succeed(UUID id, ClaimedRun claim, String output) {
        return update(id, claim, run -> run.succeed(output, Instant.now()));
    }

    public void cancel(UUID id) {
        transactions.executeWithoutResult(status -> runs.lockById(id).ifPresent(run -> {
            if (!"QUEUED".equals(run.getStatus()) && !"RUNNING".equals(run.getStatus())
                    && !"CANCELLING".equals(run.getStatus())) return;
            cancelOpenNodes(id);
            run.cancel("运行已取消", Instant.now());
            runs.save(run);
        }));
    }

    public boolean cancelIfRequested(UUID id, ClaimedRun claim) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            WorkflowRun run = runs.lockById(id).orElse(null);
            if (run == null || !id.equals(claim.id()) || !run.getOwnerId().equals(claim.ownerId())
                    || !run.hasLease(claim.leaseOwner(), claim.leaseToken(), Instant.now())) return true;
            if (!"CANCELLING".equals(run.getStatus())) {
                return !"QUEUED".equals(run.getStatus()) && !"RUNNING".equals(run.getStatus());
            }
            cancelOpenNodes(id);
            run.cancel("运行已取消", Instant.now());
            runs.save(run);
            return true;
        }));
    }

    private boolean update(UUID id, ClaimedRun claim, Consumer<WorkflowRun> mutation) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            WorkflowRun run = runs.lockById(id).orElseThrow(() -> new IllegalStateException("工作流运行记录不存在"));
            // The run lock serializes lease takeover, cancellation and every node/result write.
            if ("local".equals(run.getExecutionEngine())) {
                if (claim == null || !id.equals(claim.id()) || !run.getOwnerId().equals(claim.ownerId())
                        || !run.hasLease(claim.leaseOwner(), claim.leaseToken(), Instant.now())) return false;
            } else if (claim != null) return false;
            if (!"QUEUED".equals(run.getStatus()) && !"RUNNING".equals(run.getStatus())
                    && !"CANCELLING".equals(run.getStatus())) return false;
            if ("CANCELLING".equals(run.getStatus())) {
                cancelOpenNodes(id);
                run.cancel("运行已取消", Instant.now());
                runs.save(run);
                return false;
            }
            mutation.accept(run);
            runs.save(run);
            return true;
        }));
    }

    private void cancelOpenNodes(UUID id) {
        for (WorkflowRunNode node : nodes.findByRunIdOrderByCreatedAtAsc(id)) {
            if ("QUEUED".equals(node.getStatus()) || "RUNNING".equals(node.getStatus())) {
                node.cancel(Instant.now());
                appendLog(node, "INFO", "运行已取消");
                nodes.save(node);
            }
        }
    }

    private WorkflowRunNode requireNode(UUID runId, String nodeId) {
        return nodes.findByRunIdAndNodeId(runId, nodeId)
                .orElseThrow(() -> new IllegalStateException("运行节点记录不存在"));
    }

    private void appendLog(WorkflowRunNode node, String level, String message) {
        ArrayNode logs;
        try {
            JsonNode current = node.getLogsJson() == null ? null : mapper.readTree(node.getLogsJson());
            logs = current != null && current.isArray() ? (ArrayNode) current : mapper.createArrayNode();
        } catch (Exception exception) {
            throw new IllegalStateException("工作流节点日志无法读取", exception);
        }
        while (logs.size() >= 100) logs.remove(0);
        var entry = logs.addObject();
        entry.put("timestamp", Instant.now().toEpochMilli());
        entry.put("level", level);
        entry.put("message", message.length() > 500 ? message.substring(0, 500) : message);
        node.setLogsJson(logs.toString());
    }
}
