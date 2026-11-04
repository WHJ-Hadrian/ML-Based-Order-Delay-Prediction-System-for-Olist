package com.olist.datasets.enums;

public enum RuleRunNodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED || this == CANCELLED;
    }
}
