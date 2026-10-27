package com.olist.datasets.enums;

public enum RuleRunNodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED;
    }
}