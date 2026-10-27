package com.olist.datasets.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public class RuleWorkbookDraftRequest {

    @NotNull(message = "规则文档不能为空")
    private JsonNode document;

    private Long expectedRevision;

    public JsonNode getDocument() { return document; }
    public void setDocument(JsonNode document) { this.document = document; }
    public Long getExpectedRevision() { return expectedRevision; }
    public void setExpectedRevision(Long expectedRevision) { this.expectedRevision = expectedRevision; }
}