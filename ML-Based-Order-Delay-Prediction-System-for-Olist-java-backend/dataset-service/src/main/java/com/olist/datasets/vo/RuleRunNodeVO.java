package com.olist.datasets.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.olist.datasets.enums.RuleRunNodeStatus;
import java.time.Instant;
import java.util.UUID;

public record RuleRunNodeVO(
        UUID id,
        UUID runId,
        String nodeId,
        String nodeKind,
        RuleRunNodeStatus status,
        JsonNode metrics,
        String sampleUri,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt) {
}