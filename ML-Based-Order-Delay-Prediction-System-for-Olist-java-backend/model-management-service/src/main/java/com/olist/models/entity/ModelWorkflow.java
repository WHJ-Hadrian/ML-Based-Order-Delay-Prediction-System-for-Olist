package com.olist.models.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "model_workflows", indexes = {
    @Index(name = "idx_model_workflows_owner_updated", columnList = "owner_id, updated_at")
})
public class ModelWorkflow {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @Column(length = 255, nullable = false)
    private String name;

    @Column(nullable = false)
    private long revision;

    @Column(name = "document_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String documentJson;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ModelWorkflow() {
    }

    public ModelWorkflow(UUID id, String ownerId, String name, long revision, String documentJson,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.revision = revision;
        this.documentJson = documentJson;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void update(String name, String documentJson, long revision, Instant updatedAt) {
        this.name = name;
        this.documentJson = documentJson;
        this.revision = revision;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public long getRevision() { return revision; }
    public String getDocumentJson() { return documentJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
