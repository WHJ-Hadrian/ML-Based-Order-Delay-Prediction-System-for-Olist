package com.olist.datasets.dto;

import com.olist.datasets.enums.FieldDataType;
import com.olist.datasets.enums.FieldStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class FieldDefinitionRequest {

    @NotBlank(message = "字段名不能为空")
    @Size(max = 128, message = "字段名不能超过 128 个字符")
    @Pattern(
            regexp = "^[A-Za-z_][A-Za-z0-9_.]*$",
            message = "字段名只能包含字母、数字、下划线和点，且不能以数字开头")
    private String name;

    @Size(max = 255, message = "字段显示名不能超过 255 个字符")
    private String displayName;

    @NotNull(message = "字段类型不能为空")
    private FieldDataType dataType;

    @Size(max = 64, message = "业务角色不能超过 64 个字符")
    private String semanticRole;

    @Size(max = 255, message = "来源表不能超过 255 个字符")
    private String sourceTable;

    @Size(max = 1000, message = "字段说明不能超过 1000 个字符")
    private String description;

    private FieldStatus status;

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
}
