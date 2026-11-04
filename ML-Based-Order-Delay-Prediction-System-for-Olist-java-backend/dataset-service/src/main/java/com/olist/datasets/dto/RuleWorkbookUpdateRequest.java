package com.olist.datasets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RuleWorkbookUpdateRequest {

    @NotBlank(message = "规则库名称不能为空")
    @Size(max = 255, message = "规则库名称不能超过 255 个字符")
    private String name;

    @Size(max = 1000, message = "规则库描述不能超过 1000 个字符")
    private String description;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
