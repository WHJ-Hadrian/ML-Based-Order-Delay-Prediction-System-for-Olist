package com.olist.datasets.entity;

import com.olist.datasets.enums.DatasetFormat;
import java.util.UUID;

public class DatasetTable {

    private UUID id;
    private UUID datasetId;
    private UUID fileId;
    private String fileName;
    private String tableName;
    private String tableKey;
    private String physicalTable;
    private DatasetFormat tableFormat;
    private long rowCount;
    private int columnCount;
    private int ordinal;

    public DatasetTable() {
    }

    public DatasetTable(
            UUID id,
            UUID datasetId,
            UUID fileId,
            String fileName,
            String tableName,
            DatasetFormat tableFormat,
            long rowCount,
            int columnCount,
            int ordinal) {
        this.id = id;
        this.datasetId = datasetId;
        this.fileId = fileId;
        this.fileName = fileName;
        this.tableName = tableName;
        this.tableFormat = tableFormat;
        this.rowCount = rowCount;
        this.columnCount = columnCount;
        this.ordinal = ordinal;
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

    public UUID getFileId() {
        return fileId;
    }

    public void setFileId(UUID fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTableKey() {
        return tableKey;
    }

    public void setTableKey(String tableKey) {
        this.tableKey = tableKey;
    }

    public String getPhysicalTable() {
        return physicalTable;
    }

    public void setPhysicalTable(String physicalTable) {
        this.physicalTable = physicalTable;
    }

    public DatasetFormat getTableFormat() {
        return tableFormat;
    }

    public void setTableFormat(DatasetFormat tableFormat) {
        this.tableFormat = tableFormat;
    }

    public long getRowCount() {
        return rowCount;
    }

    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
    }

    public int getColumnCount() {
        return columnCount;
    }

    public void setColumnCount(int columnCount) {
        this.columnCount = columnCount;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(int ordinal) {
        this.ordinal = ordinal;
    }
}
