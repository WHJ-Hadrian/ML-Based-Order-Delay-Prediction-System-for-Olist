package com.olist.datasets.dto;

public record RuleRunFinishRequest(
        String status,
        String errorMessage) {
}