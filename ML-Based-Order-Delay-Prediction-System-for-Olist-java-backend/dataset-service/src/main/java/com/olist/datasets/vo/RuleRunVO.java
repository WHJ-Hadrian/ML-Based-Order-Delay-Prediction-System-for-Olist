package com.olist.datasets.vo;

import com.olist.datasets.enums.RuleRunMode;
import com.olist.datasets.enums.RuleRunStatus;
import java.time.Instant;
import java.util.UUID;

public record RuleRunVO(
        UUID id,
        UUID workbookId,
        UUID workbookVersionId,
        UUID datasetVersionId,
        RuleRunMode mode,
        RuleRunStatus status,
        Integer sampleSize,
        Long seed,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {
}