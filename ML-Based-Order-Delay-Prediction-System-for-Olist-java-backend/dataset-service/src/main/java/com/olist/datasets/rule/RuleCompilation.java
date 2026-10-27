package com.olist.datasets.rule;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.olist.datasets.vo.RuleValidationIssueVO;
import java.util.List;

public record RuleCompilation(
        boolean valid,
        List<RuleValidationIssueVO> issues,
        int nodeCount,
        ObjectNode plan) {
}