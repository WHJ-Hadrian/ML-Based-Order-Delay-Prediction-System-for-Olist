package com.olist.analytics.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "analysis_templates")
public class AnalysisTemplate {

    @Id
    @Column(name = "template_key", length = 100, nullable = false, updatable = false)
    private String templateKey;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(length = 64)
    private String category;

    @Column(name = "spec_json", columnDefinition = "LONGTEXT", nullable = false)
    private String specJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AnalysisTemplate() {
    }

    public String getTemplateKey() { return templateKey; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getSpecJson() { return specJson; }
    public Instant getCreatedAt() { return createdAt; }
}
