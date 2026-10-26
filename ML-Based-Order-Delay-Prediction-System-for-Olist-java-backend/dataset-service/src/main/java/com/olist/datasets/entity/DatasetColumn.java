package com.olist.datasets.entity;

import java.time.Instant;
import java.util.UUID;

public class DatasetColumn {

    private UUID id;
    private UUID datasetVersionId;
    private UUID tableId;
    private String columnName;
    private int ordinal;
    private String dataType;
    private String physicalName;
    private String logicalType;
    private String clickhouseType;
    private boolean nullable;
    private UUID fileId;
    private Instant createdAt;

    public DatasetColumn() {
    }

    public DatasetColumn(
            UUID id,
            UUID datasetVersionId,
            UUID tableId,
            String columnName,
            int ordinal,
            String dataType,
            String logicalType,
            String physicalName,
            String clickhouseType,
            boolean nullable,
            UUID fileId,
            Instant createdAt) {
        this.id = id;
        this.datasetVersionId = datasetVersionId;
        this.tableId = tableId;
        this.columnName = columnName;
        this.ordinal = ordinal;
        this.dataType = dataType;
        this.logicalType = logicalType;
        this.physicalName = physicalName;
        this.clickhouseType = clickhouseType;
        this.nullable = nullable;
        this.fileId = fileId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getPhysicalName() {
        return physicalName;
    }

    public void setPhysicalName(String physicalName) {
        this.physicalName = physicalName;
    }

    public String getLogicalType() {
        return logicalType;
    }

    public void setLogicalType(String logicalType) {
        this.logicalType = logicalType;
    }

    public String getClickhouseType() {
        return clickhouseType;
    }

    public void setClickhouseType(String clickhouseType) {
        this.clickhouseType = clickhouseType;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public UUID getFileId() {
        return fileId;
    }

    public void setFileId(UUID fileId) {
        this.fileId = fileId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
