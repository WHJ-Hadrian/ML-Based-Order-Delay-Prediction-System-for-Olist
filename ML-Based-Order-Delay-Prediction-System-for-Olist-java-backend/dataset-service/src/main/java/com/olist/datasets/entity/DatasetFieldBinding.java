package com.olist.datasets.entity;

import com.olist.datasets.enums.BindingStatus;
import java.time.Instant;
import java.util.UUID;

public class DatasetFieldBinding {

    private UUID id;
    private String ownerId;
    private UUID fieldId;
    private UUID datasetVersionId;
    private UUID tableId;
    private UUID columnId;
    private BindingStatus bindingStatus;
    private Instant createdAt;

    public DatasetFieldBinding() {
    }

    public DatasetFieldBinding(
            UUID id,
            String ownerId,
            UUID fieldId,
            UUID datasetVersionId,
            UUID tableId,
            UUID columnId,
            BindingStatus bindingStatus,
            Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.fieldId = fieldId;
        this.datasetVersionId = datasetVersionId;
        this.tableId = tableId;
        this.columnId = columnId;
        this.bindingStatus = bindingStatus;
        this.createdAt = createdAt;
    }

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

    public UUID getFieldId() {
        return fieldId;
    }

    public void setFieldId(UUID fieldId) {
        this.fieldId = fieldId;
    }

    public UUID getDatasetVersionId() {
        return datasetVersionId;
    }

    public void setDatasetVersionId(UUID datasetVersionId) {
        this.datasetVersionId = datasetVersionId;
    }

    public UUID getTableId() {
        return tableId;
    }

    public void setTableId(UUID tableId) {
        this.tableId = tableId;
    }

    public UUID getColumnId() {
        return columnId;
    }

    public void setColumnId(UUID columnId) {
        this.columnId = columnId;
    }

    public BindingStatus getBindingStatus() {
        return bindingStatus;
    }

    public void setBindingStatus(BindingStatus bindingStatus) {
        this.bindingStatus = bindingStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}