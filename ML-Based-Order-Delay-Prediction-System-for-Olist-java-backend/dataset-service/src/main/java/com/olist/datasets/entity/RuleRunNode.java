package com.olist.datasets.entity;

import com.olist.datasets.enums.RuleRunNodeStatus;
import java.time.Instant;
import java.util.UUID;

public class RuleRunNode {

    private UUID id;
    private UUID runId;
    private String nodeId;
    private String nodeKind;
    private RuleRunNodeStatus status;
    private String metricsJson;
    private String sampleUri;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getNodeKind() { return nodeKind; }
    public void setNodeKind(String nodeKind) { this.nodeKind = nodeKind; }
    public RuleRunNodeStatus getStatus() { return status; }
    public void setStatus(RuleRunNodeStatus status) { this.status = status; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getSampleUri() { return sampleUri; }
    public void setSampleUri(String sampleUri) { this.sampleUri = sampleUri; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}