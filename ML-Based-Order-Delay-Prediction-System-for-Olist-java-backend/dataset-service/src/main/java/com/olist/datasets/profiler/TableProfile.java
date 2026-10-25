package com.olist.datasets.profiler;

import com.olist.datasets.enums.DatasetFormat;

public record TableProfile(
        String name,
        String sourceFile,
        DatasetFormat format,
        long rowCount,
        int columnCount) {
}
