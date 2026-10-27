package com.olist.datasets.entity;

import java.time.Instant;
import java.util.UUID;

public class RuleWorkbookDraft {

    private UUID workbookId;
    private long revision;
    private String documentJson;
    private Instant updatedAt;

    public UUID getWorkbookId() { return workbookId; }
    public void setWorkbookId(UUID workbookId) { this.workbookId = workbookId; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String getDocumentJson() { return documentJson; }
    public void setDocumentJson(String documentJson) { this.documentJson = documentJson; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}