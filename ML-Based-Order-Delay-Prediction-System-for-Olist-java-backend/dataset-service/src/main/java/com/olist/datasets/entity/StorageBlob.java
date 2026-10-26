package com.olist.datasets.entity;

import java.time.Instant;

public class StorageBlob {

    private String sha256;
    private String storageKey;
    private long sizeBytes;
    private String contentType;
    private Instant createdAt;

    public StorageBlob() {
    }

    public StorageBlob(
            String sha256,
            String storageKey,
            long sizeBytes,
            String contentType,
            Instant createdAt) {
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.createdAt = createdAt;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
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
