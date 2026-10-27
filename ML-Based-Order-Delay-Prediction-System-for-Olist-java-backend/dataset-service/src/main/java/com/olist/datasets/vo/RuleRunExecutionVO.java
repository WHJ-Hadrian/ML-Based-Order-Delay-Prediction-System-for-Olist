package com.olist.datasets.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.olist.datasets.enums.RuleRunMode;
import com.olist.datasets.enums.RuleRunStatus;
import java.util.UUID;

public record RuleRunExecutionVO(
        UUID id,
        UUID workbookId,
        UUID workbookVersionId,
        UUID datasetVersionId,
        String workbookVersionContentHash,
        RuleRunMode mode,
        RuleRunStatus status,
        Integer sampleSize,
        Long seed,
        JsonNode compiledPlan) {
}