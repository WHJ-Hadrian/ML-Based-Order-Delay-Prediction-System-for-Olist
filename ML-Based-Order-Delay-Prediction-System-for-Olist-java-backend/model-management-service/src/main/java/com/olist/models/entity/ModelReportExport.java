package com.olist.models.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity
@Table(name = "model_report_exports", indexes = {
    @Index(name = "idx_model_reports_owner_model_created", columnList = "owner_id, model_id, created_at")
})
public class ModelReportExport {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "model_id", length = 36, nullable = false, updatable = false)
    private UUID modelId;

    @Column(name = "artifact_id", length = 36, nullable = false, updatable = false)
    private String artifactId;

    @Column(name = "format", length = 16, nullable = false, updatable = false)
    private String format;

    @Column(name = "title", length = 200, nullable = false, updatable = false)
    private String title;

    @Column(name = "file_name", length = 255, nullable = false, updatable = false)
    private String fileName;

    @Column(name = "sections_json", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String sectionsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ModelReportExport() {
    }

    public ModelReportExport(UUID id, String ownerId, UUID modelId, String artifactId, String format,
            String title, String fileName, String sectionsJson, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.modelId = modelId;
        this.artifactId = artifactId;
        this.format = format;
        this.title = title;
        this.fileName = fileName;
        this.sectionsJson = sectionsJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getModelId() { return modelId; }
    public String getFormat() { return format; }
    public String getTitle() { return title; }
    public String getFileName() { return fileName; }
    public Instant getCreatedAt() { return createdAt; }
}
