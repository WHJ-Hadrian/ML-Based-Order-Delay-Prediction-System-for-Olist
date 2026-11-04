package com.olist.datasets.dto;

public record RuleRunClaimRequest(
        String workerId,
        Integer leaseSeconds,
        String mode) {
}
