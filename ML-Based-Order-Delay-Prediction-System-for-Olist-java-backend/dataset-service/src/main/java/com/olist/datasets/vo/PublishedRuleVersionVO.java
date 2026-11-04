package com.olist.datasets.vo;

import java.time.Instant;
import java.util.UUID;

/** Compact catalog entry used when composing a workflow rule node. */
public record PublishedRuleVersionVO(
        UUID id,
        UUID workbookId,
        String workbookName,
        int versionNumber,
        UUID sourceDatasetVersionId,
        String status,
        String contentHash,
        Instant createdAt) {
}
