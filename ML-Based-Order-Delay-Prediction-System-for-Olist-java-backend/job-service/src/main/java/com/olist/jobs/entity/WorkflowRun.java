package com.olist.jobs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_runs", indexes = {
    @Index(name = "idx_workflow_runs_owner_created", columnList = "owner_id, created_at"),
    @Index(name = "idx_workflow_runs_workflow_created", columnList = "workflow_id, created_at")
})
public class WorkflowRun {
    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "workflow_id", length = 36, nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @Column(name = "workflow_name", length = 255, nullable = false, updatable = false)
    private String workflowName;

    @Column(name = "workflow_revision", nullable = false, updatable = false)
    private long workflowRevision;

    @Column(name = "snapshot_json", columnDefinition = "MEDIUMTEXT", nullable = false, updatable = false)
    private String snapshotJson;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(nullable = false)
    private int progress;

    @Column(name = "output_json", columnDefinition = "MEDIUMTEXT")
    private String outputJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "retry_of_id", length = 36, updatable = false)
    private UUID retryOfId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "execution_lease_owner", length = 100)
    private String executionLeaseOwner;

    @Column(name = "execution_lease_token", length = 36)
    private String executionLeaseToken;

    @Column(name = "execution_engine", length = 16, nullable = false, updatable = false)
    private String executionEngine = "temporal";

    @Column(name = "execution_lease_until")
    private Instant executionLeaseUntil;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected WorkflowRun() { }

    public WorkflowRun(UUID id, UUID workflowId, String ownerId, String workflowName,
            long workflowRevision, String snapshotJson, UUID retryOfId, Instant now) {
        this.id = id;
        this.workflowId = workflowId;
        this.ownerId = ownerId;
        this.workflowName = workflowName;
        this.workflowRevision = workflowRevision;
        this.snapshotJson = snapshotJson;
        this.retryOfId = retryOfId;
        this.status = "QUEUED";
        this.progress = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void start(Instant now) {
        if ("CANCELLING".equals(status) || "CANCELED".equals(status)) return;
        if (startedAt == null) startedAt = now;
        status = "RUNNING";
        errorMessage = null;
        updatedAt = now;
    }

    public void progress(int progress, String outputJson, Instant now) {
        this.progress = Math.max(0, Math.min(100, progress));
        if (outputJson != null) this.outputJson = outputJson;
        updatedAt = now;
    }

    public void succeed(String outputJson, Instant now) {
        if ("CANCELLING".equals(status) || "CANCELED".equals(status)) return;
        progress = 100;
        status = "SUCCEEDED";
        this.outputJson = outputJson;
        finishedAt = now;
        updatedAt = now;
        releaseLease();
    }

    public void fail(String message, Instant now) {
        if ("CANCELLING".equals(status) || "CANCELED".equals(status)) return;
        status = "FAILED";
        errorMessage = message;
        finishedAt = now;
        updatedAt = now;
        releaseLease();
    }

    public void cancel(String message, Instant now) {
        status = "CANCELED";
        errorMessage = message;
        finishedAt = now;
        updatedAt = now;
        releaseLease();
    }

    public void requestCancellation(Instant now) {
        if ("QUEUED".equals(status) || "RUNNING".equals(status)) status = "CANCELLING";
        updatedAt = now;
    }

    public void claim(String workerId, String leaseToken, Instant leaseUntil, Instant now) {
        boolean recovering = executionLeaseUntil != null && !executionLeaseUntil.isAfter(now);
        if (!"CANCELLING".equals(status)) status = "RUNNING";
        if (startedAt == null) startedAt = now;
        if (recovering) {
            progress = 0;
            outputJson = null;
            finishedAt = null;
        }
        executionLeaseOwner = workerId;
        executionLeaseToken = leaseToken;
        executionLeaseUntil = leaseUntil;
        if (!"CANCELLING".equals(status)) errorMessage = null;
        updatedAt = now;
    }

    public void releaseLease() {
        executionLeaseOwner = null;
        executionLeaseToken = null;
        executionLeaseUntil = null;
    }

    public void expireLease(Instant now) {
        executionLeaseOwner = null;
        executionLeaseToken = null;
        executionLeaseUntil = now;
    }

    public void renewLease(String workerId, Instant leaseUntil, Instant now) {
        if (!workerId.equals(executionLeaseOwner)
                || (!"RUNNING".equals(status) && !"CANCELLING".equals(status)) || executionLeaseUntil == null
                || !executionLeaseUntil.isAfter(now)) {
            throw new IllegalStateException("工作流运行租约已失效");
        }
        executionLeaseUntil = leaseUntil;
        updatedAt = now;
    }

    public boolean hasLease(String workerId, String leaseToken, Instant now) {
        return workerId.equals(executionLeaseOwner) && leaseToken.equals(executionLeaseToken)
                && executionLeaseUntil != null && executionLeaseUntil.isAfter(now)
                && ("RUNNING".equals(status) || "CANCELLING".equals(status));
    }

    public UUID getId() { return id; }
    public UUID getWorkflowId() { return workflowId; }
    public String getOwnerId() { return ownerId; }
    public String getWorkflowName() { return workflowName; }
    public long getWorkflowRevision() { return workflowRevision; }
    public String getSnapshotJson() { return snapshotJson; }
    public String getStatus() { return status; }
    public int getProgress() { return progress; }
    public String getOutputJson() { return outputJson; }
    public String getErrorMessage() { return errorMessage; }
    public UUID getRetryOfId() { return retryOfId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getExecutionLeaseOwner() { return executionLeaseOwner; }
    public String getExecutionLeaseToken() { return executionLeaseToken; }
    public Instant getExecutionLeaseUntil() { return executionLeaseUntil; }
    public String getExecutionEngine() { return executionEngine; }
    public void setExecutionEngine(String executionEngine) { this.executionEngine = executionEngine; }
}
