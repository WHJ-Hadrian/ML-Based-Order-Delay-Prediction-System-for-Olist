package com.olist.datasets.vo;

public record RuleValidationIssueVO(
        String code,
        String severity,
        String sheetKey,
        String rowId,
        String columnKey,
        String message,
        String fixSuggestion) {
}