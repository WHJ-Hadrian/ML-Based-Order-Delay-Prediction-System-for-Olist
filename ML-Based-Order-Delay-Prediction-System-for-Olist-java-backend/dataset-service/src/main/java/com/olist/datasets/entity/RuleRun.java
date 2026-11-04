package com.olist.datasets.entity;

import com.olist.datasets.enums.RuleRunMode;
import com.olist.datasets.enums.RuleRunStatus;
import java.time.Instant;
import java.util.UUID;

public class RuleRun {

    private UUID id;
    private String ownerId;
    private UUID workbookId;
    private UUID workbookVersionId;
    private UUID datasetVersionId;
    private RuleRunMode mode;
    private RuleRunStatus status;
    private Integer sampleSize;
    private Long seed;
    private String errorMessage;
    private UUID retryOfRunId;
    private UUID claimToken;
    private String claimedBy;
    private Instant leaseExpiresAt;
    private int executionAttempt;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public UUID getWorkbookId() { return workbookId; }
    public void setWorkbookId(UUID workbookId) { this.workbookId = workbookId; }
    public UUID getWorkbookVersionId() { return workbookVersionId; }
    public void setWorkbookVersionId(UUID workbookVersionId) { this.workbookVersionId = workbookVersionId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public RuleRunMode getMode() { return mode; }
    public void setMode(RuleRunMode mode) { this.mode = mode; }
    public RuleRunStatus getStatus() { return status; }
    public void setStatus(RuleRunStatus status) { this.status = status; }
    public Integer getSampleSize() { return sampleSize; }
    public void setSampleSize(Integer sampleSize) { this.sampleSize = sampleSize; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public UUID getRetryOfRunId() { return retryOfRunId; }
    public void setRetryOfRunId(UUID retryOfRunId) { this.retryOfRunId = retryOfRunId; }
    public UUID getClaimToken() { return claimToken; }
    public void setClaimToken(UUID claimToken) { this.claimToken = claimToken; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public int getExecutionAttempt() { return executionAttempt; }
    public void setExecutionAttempt(int executionAttempt) { this.executionAttempt = executionAttempt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
