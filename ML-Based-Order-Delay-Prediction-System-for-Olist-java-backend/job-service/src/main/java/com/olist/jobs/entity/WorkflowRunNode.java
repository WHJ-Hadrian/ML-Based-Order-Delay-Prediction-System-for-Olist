package com.olist.jobs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_run_nodes", indexes = {
    @Index(name = "idx_workflow_run_nodes_run", columnList = "run_id, created_at")
})
public class WorkflowRunNode {
    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "run_id", length = 36, nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "node_id", length = 100, nullable = false, updatable = false)
    private String nodeId;

    @Column(name = "node_type", length = 40, nullable = false, updatable = false)
    private String nodeType;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "result_json", columnDefinition = "MEDIUMTEXT")
    private String resultJson;

    @Column(name = "logs_json", columnDefinition = "MEDIUMTEXT")
    private String logsJson = "[]";

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected WorkflowRunNode() { }

    public WorkflowRunNode(UUID id, UUID runId, String nodeId, String nodeType, Instant now) {
        this.id = id;
        this.runId = runId;
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.status = "QUEUED";
        this.createdAt = now;
    }

    public void start(Instant now) { status = "RUNNING"; startedAt = now; }

    public void resetForRecovery() {
        status = "QUEUED";
        resultJson = null;
        errorMessage = null;
        startedAt = null;
        finishedAt = null;
    }

    public void succeed(String resultJson, Instant now) {
        status = "SUCCEEDED";
        this.resultJson = resultJson;
        finishedAt = now;
    }

    public void fail(String message, Instant now) {
        status = "FAILED";
        errorMessage = message;
        finishedAt = now;
    }

    public void cancel(Instant now) {
        status = "CANCELED";
        errorMessage = "运行已取消";
        finishedAt = now;
    }

    public String getNodeId() { return nodeId; }
    public String getNodeType() { return nodeType; }
    public String getStatus() { return status; }
    public String getResultJson() { return resultJson; }
    public String getLogsJson() { return logsJson; }
    public void setLogsJson(String logsJson) { this.logsJson = logsJson; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
