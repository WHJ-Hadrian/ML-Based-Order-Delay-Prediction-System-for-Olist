package com.olist.datasets.vo;

import java.time.Instant;
import java.util.UUID;

public record DatasetFileVO(
        UUID id,
        String originalFilename,
        long sizeBytes,
        String sha256,
        String contentType,
        Instant createdAt) {
}
