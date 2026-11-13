package com.olist.analytics.entity;

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
@Table(name = "analysis_runs", indexes = {
    @Index(name = "idx_runs_owner", columnList = "owner_id, created_at"),
    @Index(name = "idx_runs_plan", columnList = "plan_id, created_at")
})
public class AnalysisRun {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "plan_id", length = 36, updatable = false)
    private UUID planId;

    @Column(name = "plan_version")
    private Integer planVersion;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "dataset_version_id", length = 36, nullable = false, updatable = false)
    private UUID datasetVersionId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "table_id", length = 36, nullable = false, updatable = false)
    private UUID tableId;

    @Column(name = "spec_json", columnDefinition = "LONGTEXT", nullable = false, updatable = false)
    private String specJson;

    @Column(name = "spec_hash", length = 64, nullable = false, updatable = false)
    private String specHash;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    @Column(name = "result_columns_json", columnDefinition = "TEXT")
    private String resultColumnsJson;

    @Column(name = "result_rows_json", columnDefinition = "LONGTEXT")
    private String resultRowsJson;

    @Column(name = "result_ref", length = 500)
    private String resultRef;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AnalysisRun() {
    }

    public AnalysisRun(UUID id, String ownerId, UUID planId, Integer planVersion, UUID datasetVersionId,
            UUID tableId, String specJson, String specHash, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.planId = planId;
        this.planVersion = planVersion;
        this.datasetVersionId = datasetVersionId;
        this.tableId = tableId;
        this.specJson = specJson;
        this.specHash = specHash;
        this.status = "RUNNING";
        this.createdAt = createdAt;
    }

    public void succeed(int rows, int elapsed, String columnsJson, String rowsJson, Instant now) {
        this.status = "SUCCEEDED";
        this.rowCount = rows;
        this.elapsedMs = elapsed;
        this.resultColumnsJson = columnsJson;
        this.resultRowsJson = rowsJson;
        this.finishedAt = now;
    }

    public void fail(String message, Instant now) {
        this.status = "FAILED";
        this.errorMessage = message == null ? null : message.substring(0, Math.min(1000, message.length()));
        this.finishedAt = now;
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public UUID getPlanId() { return planId; }
    public Integer getPlanVersion() { return planVersion; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public UUID getTableId() { return tableId; }
    public String getSpecJson() { return specJson; }
    public String getSpecHash() { return specHash; }
    public String getStatus() { return status; }
    public Integer getRowCount() { return rowCount; }
    public Integer getElapsedMs() { return elapsedMs; }
    public String getResultColumnsJson() { return resultColumnsJson; }
    public String getResultRowsJson() { return resultRowsJson; }
    public String getResultRef() { return resultRef; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
