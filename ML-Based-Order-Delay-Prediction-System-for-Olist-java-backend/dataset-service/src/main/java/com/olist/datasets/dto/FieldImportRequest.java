package com.olist.datasets.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public class FieldImportRequest {

    @NotNull(message = "数据集版本不能为空")
    private UUID datasetVersionId;

    private List<UUID> tableIds;

    private List<UUID> columnIds;

    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public List<UUID> getTableIds() { return tableIds; }
    public void setTableIds(List<UUID> tableIds) { this.tableIds = tableIds; }
    public List<UUID> getColumnIds() { return columnIds; }
    public void setColumnIds(List<UUID> columnIds) { this.columnIds = columnIds; }
}