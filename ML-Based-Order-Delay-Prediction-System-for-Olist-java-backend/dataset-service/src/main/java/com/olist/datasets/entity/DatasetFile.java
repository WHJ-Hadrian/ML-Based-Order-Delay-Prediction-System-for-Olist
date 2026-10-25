package com.olist.datasets.entity;

import java.time.Instant;
import java.util.UUID;

public class DatasetFile {

    private UUID id;
    private UUID datasetId;
    private String originalFilename;
    private String storageKey;
    private long sizeBytes;
    private String sha256;
    private String contentType;
    private Instant createdAt;

    public DatasetFile() {
    }

    public DatasetFile(
            UUID id,
            UUID datasetId,
            String originalFilename,
            String storageKey,
            long sizeBytes,
            String sha256,
            String contentType,
            Instant createdAt) {
        this.id = id;
        this.datasetId = datasetId;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.contentType = contentType;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public void setDatasetId(UUID datasetId) {
        this.datasetId = datasetId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
