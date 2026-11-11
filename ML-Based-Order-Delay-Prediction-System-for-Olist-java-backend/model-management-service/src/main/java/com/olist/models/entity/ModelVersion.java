package com.olist.models.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "model_versions", indexes = {
    @Index(name = "idx_model_versions_owner_created", columnList = "owner_id, created_at")
})
public class ModelVersion {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(length = 32, nullable = false, updatable = false)
    private String task;

    @Column(length = 64, nullable = false, updatable = false)
    private String algorithm;

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

    @Column(name = "target_column", length = 255, nullable = false, updatable = false)
    private String targetColumn;

    @Column(name = "feature_columns_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String featureColumnsJson;

    @Column(name = "feature_types_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String featureTypesJson;

    @Column(name = "test_size", nullable = false, updatable = false)
    private double testSize;

    @Column(name = "random_state", nullable = false, updatable = false)
    private int randomState;

    @Column(name = "metrics_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String metricsJson;

    @Column(name = "train_rows", nullable = false, updatable = false)
    private int trainRows;

    @Column(name = "test_rows", nullable = false, updatable = false)
    private int testRows;

    @Column(name = "dropped_target_rows", nullable = false, updatable = false)
    private int droppedTargetRows;

    @Column(name = "artifact_id", length = 36, nullable = false, unique = true, updatable = false)
    private String artifactId;

    @Column(name = "confusion_matrix_json", columnDefinition = "TEXT", updatable = false)
    private String confusionMatrixJson;

    @Column(name = "evaluation_samples_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String evaluationSamplesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ModelVersionStatus status;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "deprecated_at")
    private Instant deprecatedAt;

    @Column(name = "model_family", length = 255)
    private String modelFamily;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModelVersion() {
    }

    public ModelVersion(
            UUID id,
            String ownerId,
            String name,
            String task,
            String algorithm,
            UUID datasetId,
            UUID datasetVersionId,
            int datasetVersionNo,
            UUID tableId,
            String targetColumn,
            String featureColumnsJson,
            String featureTypesJson,
            double testSize,
            int randomState,
            String metricsJson,
            int trainRows,
            int testRows,
            int droppedTargetRows,
            String artifactId,
            String confusionMatrixJson,
            String evaluationSamplesJson,
            Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.task = task;
        this.algorithm = algorithm;
        this.datasetId = datasetId;
        this.datasetVersionId = datasetVersionId;
        this.datasetVersionNo = datasetVersionNo;
        this.tableId = tableId;
        this.targetColumn = targetColumn;
        this.featureColumnsJson = featureColumnsJson;
        this.featureTypesJson = featureTypesJson;
        this.testSize = testSize;
        this.randomState = randomState;
        this.metricsJson = metricsJson;
        this.trainRows = trainRows;
        this.testRows = testRows;
        this.droppedTargetRows = droppedTargetRows;
        this.artifactId = artifactId;
        this.confusionMatrixJson = confusionMatrixJson;
        this.evaluationSamplesJson = evaluationSamplesJson;
        this.status = ModelVersionStatus.DRAFT;
        this.modelFamily = name;
        this.versionNo = 1;
        this.createdAt = createdAt;
    }

    public void approve(String approvedBy, Instant approvedAt) {
        if (status != ModelVersionStatus.DRAFT
                && status != ModelVersionStatus.CANDIDATE
                && status != ModelVersionStatus.VALIDATED) {
            throw new IllegalStateException("模型版本当前状态不允许审批: " + status);
        }
        this.status = ModelVersionStatus.APPROVED;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.deprecatedAt = null;
    }

    public void deprecate(Instant deprecatedAt) {
        if (status != ModelVersionStatus.APPROVED) {
            throw new IllegalStateException("只有已审批模型版本可以弃用，当前状态: " + status);
        }
        this.status = ModelVersionStatus.DEPRECATED;
        this.deprecatedAt = deprecatedAt;
    }

    public void assignVersion(String modelFamily, int versionNo) {
        this.modelFamily = modelFamily;
        this.versionNo = versionNo;
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getTask() { return task; }
    public String getAlgorithm() { return algorithm; }
    public UUID getDatasetId() { return datasetId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public int getDatasetVersionNo() { return datasetVersionNo; }
    public UUID getTableId() { return tableId; }
    public String getTargetColumn() { return targetColumn; }
    public String getFeatureColumnsJson() { return featureColumnsJson; }
    public String getFeatureTypesJson() { return featureTypesJson; }
    public double getTestSize() { return testSize; }
    public int getRandomState() { return randomState; }
    public String getMetricsJson() { return metricsJson; }
    public int getTrainRows() { return trainRows; }
    public int getTestRows() { return testRows; }
    public int getDroppedTargetRows() { return droppedTargetRows; }
    public String getArtifactId() { return artifactId; }
    public String getConfusionMatrixJson() { return confusionMatrixJson; }
    public String getEvaluationSamplesJson() { return evaluationSamplesJson; }
    public ModelVersionStatus getStatus() { return status; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getDeprecatedAt() { return deprecatedAt; }
    public String getModelFamily() { return modelFamily; }
    public int getVersionNo() { return versionNo; }
    public Instant getCreatedAt() { return createdAt; }
}