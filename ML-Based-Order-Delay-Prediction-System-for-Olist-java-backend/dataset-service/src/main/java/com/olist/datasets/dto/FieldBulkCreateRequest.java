package com.olist.datasets.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class FieldBulkCreateRequest {

    @NotEmpty(message = "请至少提供一个字段")
    @Valid
    private List<FieldDefinitionRequest> rows;

    public List<FieldDefinitionRequest> getRows() { return rows; }
    public void setRows(List<FieldDefinitionRequest> rows) { this.rows = rows; }
}
