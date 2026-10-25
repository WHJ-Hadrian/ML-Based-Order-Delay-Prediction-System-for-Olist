package com.olist.datasets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class DatasetTableUpdateRequest {

    @NotBlank(message = "数据表名称不能为空")
    @Size(max = 512, message = "数据表名称不能超过 512 个字符")
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}