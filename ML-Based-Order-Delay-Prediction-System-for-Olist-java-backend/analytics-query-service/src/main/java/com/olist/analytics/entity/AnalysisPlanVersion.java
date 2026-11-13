package com.olist.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "analysis_plan_versions")
public class AnalysisPlanVersion {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36, nullable = false, updatable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "plan_id", length = 36, nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(name = "spec_json", columnDefinition = "LONGTEXT", nullable = false, updatable = false)
    private String specJson;

    @Column(name = "schema_hash", length = 64, updatable = false)
    private String schemaHash;

    @Column(name = "created_by", length = 255, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AnalysisPlanVersion() {
    }

    public AnalysisPlanVersion(UUID id, UUID planId, int versionNo, String specJson, String schemaHash,
            String createdBy, Instant createdAt) {
        this.id = id;
        this.planId = planId;
        this.versionNo = versionNo;
        this.specJson = specJson;
        this.schemaHash = schemaHash;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public int getVersionNo() { return versionNo; }
    public String getSpecJson() { return specJson; }
    public String getSchemaHash() { return schemaHash; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
