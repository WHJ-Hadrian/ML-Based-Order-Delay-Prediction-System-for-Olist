package com.olist.datasets.profiler;

public record TablePreviewOptions(
        int headLimit,
        int tailLimit,
        boolean all,
        int maxRows,
        long knownRowCount) {
}