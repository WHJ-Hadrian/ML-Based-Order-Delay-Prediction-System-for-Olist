package com.olist.datasets.entity;

import com.olist.datasets.enums.FieldDataType;
import com.olist.datasets.enums.FieldStatus;
import java.time.Instant;
import java.util.UUID;

public class FieldDefinition {

    private UUID id;
    private String ownerId;
    private UUID libraryId;
    private String name;
    private String displayName;
    private FieldDataType dataType;
    private String semanticRole;
    private String sourceTable;
    private String description;
    private FieldStatus status;
    private UUID sourceDatasetId;
    private String sourceDatasetName;
    private String sourceTableName;
    private String sourceColumnName;
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

    public UUID getLibraryId() {
        return libraryId;
    }

    public void setLibraryId(UUID libraryId) {
        this.libraryId = libraryId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public FieldDataType getDataType() {
        return dataType;
    }

    public void setDataType(FieldDataType dataType) {
        this.dataType = dataType;
    }

    public String getSemanticRole() {
        return semanticRole;
    }

    public void setSemanticRole(String semanticRole) {
        this.semanticRole = semanticRole;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public FieldStatus getStatus() {
        return status;
    }

    public void setStatus(FieldStatus status) {
        this.status = status;
    }

    public UUID getSourceDatasetId() {
        return sourceDatasetId;
    }

    public void setSourceDatasetId(UUID sourceDatasetId) {
        this.sourceDatasetId = sourceDatasetId;
    }

    public String getSourceDatasetName() {
        return sourceDatasetName;
    }

    public void setSourceDatasetName(String sourceDatasetName) {
        this.sourceDatasetName = sourceDatasetName;
    }

    public String getSourceTableName() {
        return sourceTableName;
    }

    public void setSourceTableName(String sourceTableName) {
        this.sourceTableName = sourceTableName;
    }

    public String getSourceColumnName() {
        return sourceColumnName;
    }

    public void setSourceColumnName(String sourceColumnName) {
        this.sourceColumnName = sourceColumnName;
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
