package com.olist.models.entity;

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
@Table(name = "training_runs", indexes = {
    @Index(name = "idx_training_runs_owner_created", columnList = "owner_id, created_at"),
    @Index(name = "idx_training_runs_status_created", columnList = "status, created_at"),
    @Index(name = "idx_training_runs_status_lease", columnList = "status, lease_expires_at")
})
public class TrainingRun {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public static final String STAGE_QUEUED = "QUEUED";
    public static final String STAGE_VALIDATING = "VALIDATING";
    public static final String STAGE_TRAINING = "TRAINING";
    public static final String STAGE_REGISTERING = "REGISTERING";
    public static final String STAGE_COMPLETED = "COMPLETED";
    public static final String STAGE_FAILED = "FAILED";
    public static final String STAGE_CANCELLED = "CANCELLED";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "dataset_id", length = 36, nullable = false, updatable = false)
    private UUID datasetId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "dataset_version_id", length = 36, nullable = false, updatable = false)
    private UUID datasetVersionId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "table_id", length = 36, nullable = false, updatable = false)
    private UUID tableId;

    @Column(length = 255)
    private String name;

    @Column(length = 32, nullable = false, updatable = false)
    private String task;

    @Column(length = 64, nullable = false, updatable = false)
    private String algorithm;

    @Column(name = "target_column", length = 255, nullable = false, updatable = false)
    private String targetColumn;

    @Column(name = "feature_columns_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String featureColumnsJson;

    @Column(name = "test_size", nullable = false, updatable = false)
    private double testSize;

    @Column(name = "random_state", nullable = false, updatable = false)
    private int randomState;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 32, nullable = false)
    private String stage;

    @Column(nullable = false)
    private int progress;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "request_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String requestJson;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "model_version_id", length = 36)
    private UUID modelVersionId;

    @Column(name = "artifact_id", length = 255)
    private String artifactId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "claim_token", length = 64)
    private String claimToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "owner_token", columnDefinition = "TEXT")
    private String ownerToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TrainingRun() {
    }

    public TrainingRun(
            UUID id,
            String ownerId,
            UUID datasetId,
            UUID datasetVersionId,
            UUID tableId,
            String name,
            String task,
            String algorithm,
            String targetColumn,
            String featureColumnsJson,
            double testSize,
            int randomState,
            String idempotencyKey,
            String requestJson,
            String ownerToken,
            Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.datasetId = datasetId;
        this.datasetVersionId = datasetVersionId;
        this.tableId = tableId;
        this.name = name;
        this.task = task;
        this.algorithm = algorithm;
        this.targetColumn = targetColumn;
        this.featureColumnsJson = featureColumnsJson;
        this.testSize = testSize;
        this.randomState = randomState;
        this.idempotencyKey = idempotencyKey;
        this.requestJson = requestJson;
        this.ownerToken = ownerToken;
        this.status = QUEUED;
        this.stage = STAGE_QUEUED;
        this.progress = 0;
        this.attempts = 0;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void claim(String token, Instant now, Instant leaseUntil) {
        this.status = RUNNING;
        this.stage = STAGE_VALIDATING;
        this.progress = 10;
        this.claimToken = token;
        this.leaseExpiresAt = leaseUntil;
        this.attempts += 1;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.updatedAt = now;
        this.errorMessage = null;
    }

    public boolean updateStage(String token, String stage, int progress, Instant now) {
        if (!RUNNING.equals(status) || claimToken == null || !claimToken.equals(token)) {
            return false;
        }
        this.stage = stage;
        this.progress = Math.max(this.progress, Math.min(progress, 99));
        this.updatedAt = now;
        return true;
    }

    public void succeed(UUID modelVersionId, String artifactId, String resultJson, Instant now) {
        this.status = SUCCEEDED;
        this.stage = STAGE_COMPLETED;
        this.progress = 100;
        this.modelVersionId = modelVersionId;
        this.artifactId = artifactId;
        this.resultJson = resultJson;
        this.errorMessage = null;
        this.finishedAt = now;
        this.updatedAt = now;
        clearClaim();
    }

    public void fail(String message, Instant now) {
        this.status = FAILED;
        this.stage = STAGE_FAILED;
        this.errorMessage = message;
        this.finishedAt = now;
        this.updatedAt = now;
        clearClaim();
    }

    public void cancel(Instant now) {
        this.status = CANCELLED;
        this.stage = STAGE_CANCELLED;
        this.finishedAt = now;
        this.updatedAt = now;
        clearClaim();
    }

    public boolean isTerminal() {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    public boolean isLeaseExpired(Instant now) {
        return RUNNING.equals(status) && (leaseExpiresAt == null || leaseExpiresAt.isBefore(now));
    }

    public void renewLease(Instant leaseUntil, Instant now) {
        this.leaseExpiresAt = leaseUntil;
        this.updatedAt = now;
    }

    private void clearClaim() {
        this.claimToken = null;
        this.leaseExpiresAt = null;
        this.ownerToken = null;
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public UUID getDatasetId() { return datasetId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public UUID getTableId() { return tableId; }
    public String getName() { return name; }
    public String getTask() { return task; }
    public String getAlgorithm() { return algorithm; }
    public String getTargetColumn() { return targetColumn; }
    public String getFeatureColumnsJson() { return featureColumnsJson; }
    public double getTestSize() { return testSize; }
    public int getRandomState() { return randomState; }
    public String getStatus() { return status; }
    public String getStage() { return stage; }
    public int getProgress() { return progress; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestJson() { return requestJson; }
    public String getResultJson() { return resultJson; }
    public UUID getModelVersionId() { return modelVersionId; }
    public String getArtifactId() { return artifactId; }
    public String getErrorMessage() { return errorMessage; }
    public int getAttempts() { return attempts; }
    public String getClaimToken() { return claimToken; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public String getOwnerToken() { return ownerToken; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
