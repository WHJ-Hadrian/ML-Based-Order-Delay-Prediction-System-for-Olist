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
@Table(name = "analysis_plans", indexes = {
    @Index(name = "idx_plans_owner", columnList = "owner_id, updated_at")
})
public class AnalysisPlan {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", length = 255, nullable = false, updatable = false)
    private String ownerId;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "template_key", length = 100)
    private String templateKey;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AnalysisPlan() {
    }

    public AnalysisPlan(UUID id, String ownerId, String name, String description, String templateKey, Instant now) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.description = description;
        this.templateKey = templateKey;
        this.currentVersion = 1;
        this.status = "DRAFT";
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String description, int nextVersion, Instant now) {
        this.name = name;
        this.description = description;
        this.currentVersion = nextVersion;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getTemplateKey() { return templateKey; }
    public int getCurrentVersion() { return currentVersion; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
