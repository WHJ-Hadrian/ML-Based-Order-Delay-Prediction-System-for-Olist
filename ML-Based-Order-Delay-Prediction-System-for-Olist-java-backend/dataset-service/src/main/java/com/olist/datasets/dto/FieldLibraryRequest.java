package com.olist.datasets.dto;

import jakarta.validation.constraints.Size;

public class FieldLibraryRequest {

    @Size(max = 128, message = "字段库名称不能超过 128 个字符")
    private String name;

    @Size(max = 500, message = "字段库说明不能超过 500 个字符")
    private String description;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
