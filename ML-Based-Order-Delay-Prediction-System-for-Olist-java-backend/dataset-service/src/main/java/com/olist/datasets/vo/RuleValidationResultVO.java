package com.olist.datasets.vo;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record RuleValidationResultVO(
        boolean valid,
        List<RuleValidationIssueVO> issues,
        int nodeCount,
        JsonNode compiledPlan) {
}