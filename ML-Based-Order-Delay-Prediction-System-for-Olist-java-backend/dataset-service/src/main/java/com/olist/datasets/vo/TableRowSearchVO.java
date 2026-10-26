package com.olist.datasets.vo;

import java.util.List;
import java.util.UUID;

public record TableRowSearchVO(
        UUID tableId,
        String name,
        long offset,
        int limit,
        long totalMatches,
        long scannedRows,
        boolean truncated,
        List<String> columns,
        List<RowMatch> rows) {

    public record RowMatch(long rowNumber, List<String> values) { }
}
