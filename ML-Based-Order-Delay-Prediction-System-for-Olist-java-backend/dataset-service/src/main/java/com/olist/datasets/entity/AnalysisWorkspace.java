package com.olist.datasets.entity;

import java.time.Instant;
import java.util.UUID;

public class AnalysisWorkspace {

    private UUID id;
    private String ownerId;
    private String name;
    private long revision;
    private String cardsJson;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String getCardsJson() { return cardsJson; }
    public void setCardsJson(String cardsJson) { this.cardsJson = cardsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
