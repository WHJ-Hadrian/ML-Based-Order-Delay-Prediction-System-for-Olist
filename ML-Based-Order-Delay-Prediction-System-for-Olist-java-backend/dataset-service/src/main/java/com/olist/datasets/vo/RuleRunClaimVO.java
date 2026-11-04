package com.olist.datasets.vo;

import java.time.Instant;
import java.util.UUID;

public record RuleRunClaimVO(
        boolean claimed,
        UUID runId,
        String ownerId,
        UUID claimToken,
        Integer executionAttempt,
        Instant leaseExpiresAt) {

    public static RuleRunClaimVO empty() {
        return new RuleRunClaimVO(false, null, null, null, null, null);
    }
}
