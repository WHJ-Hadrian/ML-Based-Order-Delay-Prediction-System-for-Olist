package com.olist.datasets.entity;

import com.olist.datasets.enums.FieldLibrarySourceType;
import java.time.Instant;
import java.util.UUID;

public class FieldLibrary {

    private UUID id;
    private String ownerId;
    private String name;
    private String description;
    private FieldLibrarySourceType sourceType;
    private long fieldCount;
    private int datasetCount;
    private Instant createdAt;
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public FieldLibrarySourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(FieldLibrarySourceType sourceType) {
        this.sourceType = sourceType;
    }

    public long getFieldCount() {
        return fieldCount;
    }

    public void setFieldCount(long fieldCount) {
        this.fieldCount = fieldCount;
    }

    public int getDatasetCount() {
        return datasetCount;
    }

    public void setDatasetCount(int datasetCount) {
        this.datasetCount = datasetCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
