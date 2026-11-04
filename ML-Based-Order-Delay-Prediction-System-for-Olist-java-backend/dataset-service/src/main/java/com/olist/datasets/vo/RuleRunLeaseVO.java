package com.olist.datasets.vo;

import java.time.Instant;
import java.util.UUID;

public record RuleRunLeaseVO(
        UUID runId,
        int executionAttempt,
        Instant leaseExpiresAt) {
}
