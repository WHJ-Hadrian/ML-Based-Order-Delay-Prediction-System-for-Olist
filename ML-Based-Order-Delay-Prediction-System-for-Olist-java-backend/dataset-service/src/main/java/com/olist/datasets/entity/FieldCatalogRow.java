package com.olist.datasets.entity;

import com.olist.datasets.enums.FieldDataType;
import java.util.UUID;

public class FieldCatalogRow {

    private UUID columnId;
    private UUID fieldId;
    private String fieldName;
    private String fieldDisplayName;
    private UUID datasetId;
    private String datasetName;
    private UUID tableId;
    private String tableName;
    private String columnName;
    private FieldDataType dataType;
    private int ordinal;

    public UUID getColumnId() { return columnId; }
    public void setColumnId(UUID columnId) { this.columnId = columnId; }
    public UUID getFieldId() { return fieldId; }
    public void setFieldId(UUID fieldId) { this.fieldId = fieldId; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getFieldDisplayName() { return fieldDisplayName; }
    public void setFieldDisplayName(String fieldDisplayName) { this.fieldDisplayName = fieldDisplayName; }
    public UUID getDatasetId() { return datasetId; }
    public void setDatasetId(UUID datasetId) { this.datasetId = datasetId; }
    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }
    public UUID getTableId() { return tableId; }
    public void setTableId(UUID tableId) { this.tableId = tableId; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String columnName) { this.columnName = columnName; }
    public FieldDataType getDataType() { return dataType; }
    public void setDataType(FieldDataType dataType) { this.dataType = dataType; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}