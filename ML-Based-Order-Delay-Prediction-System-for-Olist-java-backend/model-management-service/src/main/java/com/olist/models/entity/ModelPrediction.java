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

/** 单笔订单预测任务：入队、执行、结果都落库，重启或换浏览器都能看到。 */
@Entity
@Table(name = "model_predictions", indexes = {
    @Index(name = "idx_model_predictions_owner_created", columnList = "owner_id, created_at"),
    @Index(name = "idx_model_predictions_status_created", columnList = "status, created_at")
})
public class ModelPrediction {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "model_id", length = 36, nullable = false, updatable = false)
    private UUID modelId;

    @Column(name = "model_name", length = 255)
    private String modelName;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(name = "input_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String inputJson;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(length = 64)
    private String prediction;

    @Column
    private Double probability;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "include_counterfactuals", nullable = false)
    private boolean includeCounterfactuals;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ModelPrediction() {
    }

    public ModelPrediction(
            UUID id,
            String ownerId,
            UUID modelId,
            String modelName,
            String inputJson,
            boolean includeCounterfactuals,
            Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.modelId = modelId;
        this.modelName = modelName;
        this.status = QUEUED;
        this.inputJson = inputJson;
        this.includeCounterfactuals = includeCounterfactuals;
        this.createdAt = createdAt;
    }

    public void markRunning(Instant startedAt) {
        this.status = RUNNING;
        this.startedAt = startedAt;
        this.errorMessage = null;
    }

    public void requeue() {
        this.status = QUEUED;
        this.startedAt = null;
        this.errorMessage = null;
    }

    public void succeed(Instant finishedAt, String resultJson, String prediction, Double probability, Double thresholdValue) {
        this.status = SUCCEEDED;
        this.resultJson = resultJson;
        this.prediction = prediction;
        this.probability = probability;
        this.thresholdValue = thresholdValue;
        this.finishedAt = finishedAt;
        this.errorMessage = null;
    }

    public void fail(Instant finishedAt, String message) {
        this.status = FAILED;
        this.finishedAt = finishedAt;
        this.errorMessage = message == null ? null : message.substring(0, Math.min(1000, message.length()));
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public UUID getModelId() { return modelId; }
    public String getModelName() { return modelName; }
    public String getStatus() { return status; }
    public String getInputJson() { return inputJson; }
    public String getResultJson() { return resultJson; }
    public String getErrorMessage() { return errorMessage; }
    public String getPrediction() { return prediction; }
    public Double getProbability() { return probability; }
    public Double getThresholdValue() { return thresholdValue; }
    public boolean isIncludeCounterfactuals() { return includeCounterfactuals; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
