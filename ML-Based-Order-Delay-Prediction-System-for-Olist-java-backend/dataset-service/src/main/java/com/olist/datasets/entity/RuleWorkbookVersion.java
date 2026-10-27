package com.olist.datasets.entity;

import java.time.Instant;
import java.util.UUID;

public class RuleWorkbookVersion {

    private UUID id;
    private UUID workbookId;
    private int versionNumber;
    private UUID datasetVersionId;
    private String status;
    private String documentJson;
    private String compiledPlanJson;
    private String contentHash;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getWorkbookId() { return workbookId; }
    public void setWorkbookId(UUID workbookId) { this.workbookId = workbookId; }
    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDocumentJson() { return documentJson; }
    public void setDocumentJson(String documentJson) { this.documentJson = documentJson; }
    public String getCompiledPlanJson() { return compiledPlanJson; }
    public void setCompiledPlanJson(String compiledPlanJson) { this.compiledPlanJson = compiledPlanJson; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}