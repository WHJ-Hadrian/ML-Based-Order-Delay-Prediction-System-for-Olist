package com.olist.datasets.dto;

import org.springframework.web.multipart.MultipartFile;

public record RuleOutputUpload(
        String nodeId,
        String name,
        String sha256,
        long sizeBytes,
        long rowCount,
        int columnCount,
        MultipartFile file) {}
