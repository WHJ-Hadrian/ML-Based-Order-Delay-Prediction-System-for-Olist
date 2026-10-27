package com.olist.datasets.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public class RuleWorkbookCreateRequest {

    @NotBlank(message = "规则库名称不能为空")
    @Size(max = 255, message = "规则库名称不能超过 255 个字符")
    private String name;

    @Size(max = 1000, message = "规则库描述不能超过 1000 个字符")
    private String description;

    @NotNull(message = "数据集版本不能为空")
    private UUID datasetVersionId;

    private JsonNode document;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(UUID datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public JsonNode getDocument() { return document; }
    public void setDocument(JsonNode document) { this.document = document; }
}