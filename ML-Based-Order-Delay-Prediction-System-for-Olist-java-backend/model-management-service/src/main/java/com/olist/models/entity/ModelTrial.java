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
@Table(name = "model_trials", indexes = {
    @Index(name = "idx_model_trials_owner_created", columnList = "owner_id, created_at"),
    @Index(name = "idx_model_trials_model_created", columnList = "model_id, created_at")
})
public class ModelTrial {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "model_id", length = 36, nullable = false, updatable = false)
    private UUID modelId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "comparison_id", length = 36, nullable = false, updatable = false)
    private UUID comparisonId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "dataset_id", length = 36, nullable = false, updatable = false)
    private UUID datasetId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "dataset_version_id", length = 36, nullable = false, updatable = false)
    private UUID datasetVersionId;

    @Column(name = "dataset_version_no", nullable = false, updatable = false)
    private int datasetVersionNo;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "table_id", length = 36, nullable = false, updatable = false)
    private UUID tableId;

    @Column(name = "target_column", length = 255, updatable = false)
    private String targetColumn;

    @Column(name = "predicted_rows", nullable = false, updatable = false)
    private int predictedRows;

    @Column(name = "evaluated_rows", nullable = false, updatable = false)
    private int evaluatedRows;

    @Column(name = "ignored_target_rows", nullable = false, updatable = false)
    private int ignoredTargetRows;

    @Column(name = "metrics_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String metricsJson;

    @Column(name = "confusion_matrix_json", columnDefinition = "TEXT", updatable = false)
    private String confusionMatrixJson;

    @Column(name = "evaluation_samples_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String evaluationSamplesJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModelTrial() {
    }

    public ModelTrial(
            UUID id, String ownerId, UUID modelId, UUID datasetId, UUID datasetVersionId,
            int datasetVersionNo, UUID tableId, String targetColumn, int predictedRows,
            int evaluatedRows, int ignoredTargetRows, String metricsJson, String confusionMatrixJson,
            String evaluationSamplesJson, Instant createdAt) {
        this(id, id, ownerId, modelId, datasetId, datasetVersionId, datasetVersionNo, tableId,
                targetColumn, predictedRows, evaluatedRows, ignoredTargetRows, metricsJson,
                confusionMatrixJson, evaluationSamplesJson, createdAt);
    }

    public ModelTrial(
            UUID id, UUID comparisonId, String ownerId, UUID modelId, UUID datasetId, UUID datasetVersionId,
            int datasetVersionNo, UUID tableId, String targetColumn, int predictedRows,
            int evaluatedRows, int ignoredTargetRows, String metricsJson, String confusionMatrixJson,
            String evaluationSamplesJson, Instant createdAt) {
        this.id = id;
        this.comparisonId = comparisonId;
        this.ownerId = ownerId;
        this.modelId = modelId;
        this.datasetId = datasetId;
        this.datasetVersionId = datasetVersionId;
        this.datasetVersionNo = datasetVersionNo;
        this.tableId = tableId;
        this.targetColumn = targetColumn;
        this.predictedRows = predictedRows;
        this.evaluatedRows = evaluatedRows;
        this.ignoredTargetRows = ignoredTargetRows;
        this.metricsJson = metricsJson;
        this.confusionMatrixJson = confusionMatrixJson;
        this.evaluationSamplesJson = evaluationSamplesJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public UUID getModelId() { return modelId; }
    public UUID getComparisonId() { return comparisonId; }
    public UUID getDatasetId() { return datasetId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public int getDatasetVersionNo() { return datasetVersionNo; }
    public UUID getTableId() { return tableId; }
    public String getTargetColumn() { return targetColumn; }
    public int getPredictedRows() { return predictedRows; }
    public int getEvaluatedRows() { return evaluatedRows; }
    public int getIgnoredTargetRows() { return ignoredTargetRows; }
    public String getMetricsJson() { return metricsJson; }
    public String getConfusionMatrixJson() { return confusionMatrixJson; }
    public String getEvaluationSamplesJson() { return evaluationSamplesJson; }
    public Instant getCreatedAt() { return createdAt; }
}
