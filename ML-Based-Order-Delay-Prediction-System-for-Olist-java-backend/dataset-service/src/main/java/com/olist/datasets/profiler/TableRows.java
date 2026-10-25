package com.olist.datasets.profiler;

import java.util.List;

public record TableRows(List<String> columns, List<List<String>> rows) {

    public TableRows {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }
}