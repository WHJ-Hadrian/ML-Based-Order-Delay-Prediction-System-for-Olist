package com.olist.datasets.vo;

import com.olist.datasets.enums.RuleSeverity;
import com.olist.datasets.enums.RuleType;
import java.time.Instant;
import java.util.UUID;

public record CleaningRuleVO(
        UUID id,
        String name,
        RuleType ruleType,
        String targetField,
        RuleSeverity severity,
        String expression,
        String description,
        boolean enabled,
        int priority,
        Instant createdAt,
        Instant updatedAt) {
}
