package com.olist.datasets.profiler;

import java.util.List;

public record TablePreview(
        List<String> columns,
        List<List<String>> rows,
        List<List<String>> tailRows,
        long totalRows,
        boolean truncated) {

    public TablePreview {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
        tailRows = tailRows.stream().map(List::copyOf).toList();
    }
}