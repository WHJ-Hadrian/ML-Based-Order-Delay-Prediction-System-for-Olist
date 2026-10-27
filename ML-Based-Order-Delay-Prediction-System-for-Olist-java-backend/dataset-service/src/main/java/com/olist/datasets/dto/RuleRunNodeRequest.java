package com.olist.datasets.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RuleRunNodeRequest(
        String nodeId,
        String nodeKind,
        String status,
        JsonNode metrics,
        String sampleUri,
        String errorMessage) {
}