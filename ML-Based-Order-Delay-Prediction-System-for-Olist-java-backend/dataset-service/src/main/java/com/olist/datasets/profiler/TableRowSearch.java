package com.olist.datasets.profiler;

import java.util.List;

public record TableRowSearch(
        List<String> columns,
        List<Match> rows,
        long totalMatches,
        long scannedRows,
        boolean truncated) {

    public TableRowSearch {
        columns = List.copyOf(columns);
        rows = rows.stream().map(match -> new Match(match.rowNumber(), match.values())).toList();
    }

    public record Match(long rowNumber, List<String> values) {

        public Match {
            values = List.copyOf(values);
        }
    }
}
