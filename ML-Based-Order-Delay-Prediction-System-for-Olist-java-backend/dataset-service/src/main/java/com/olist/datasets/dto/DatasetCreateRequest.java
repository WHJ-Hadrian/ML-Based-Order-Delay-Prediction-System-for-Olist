package com.olist.datasets.dto;

import jakarta.validation.constraints.Size;

public class DatasetCreateRequest {

    @Size(max = 255, message = "数据集名称不能超过 255 个字符")
    private String name;

    @Size(max = 1000, message = "数据集描述不能超过 1000 个字符")
    private String description;

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
}
