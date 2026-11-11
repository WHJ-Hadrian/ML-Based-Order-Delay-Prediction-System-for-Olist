package com.olist.models.api;

import java.time.Instant;
import java.util.UUID;

public record ModelReportRecordResponse(
        UUID id,
        UUID modelId,
        String format,
        String title,
        String fileName,
        Instant createdAt) {
}
