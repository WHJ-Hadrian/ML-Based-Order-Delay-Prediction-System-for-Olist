package com.olist.datasets.vo;

import java.util.List;
import java.util.UUID;

public record TablePreviewVO(
        UUID tableId,
        String name,
        int limit,
        List<String> columns,
        List<List<String>> rows,
        List<List<String>> tailRows,
        long totalRows,
        boolean truncated) {
}