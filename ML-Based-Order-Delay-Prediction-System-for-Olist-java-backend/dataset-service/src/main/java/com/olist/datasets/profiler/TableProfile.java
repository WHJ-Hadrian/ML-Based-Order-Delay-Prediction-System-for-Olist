package com.olist.datasets.profiler;

import com.olist.datasets.enums.DatasetFormat;
import java.util.List;

public record TableProfile(
        String name,
        String sourceFile,
        DatasetFormat format,
        long rowCount,
        int columnCount,
        List<ColumnProfile> columns) {

    public TableProfile {
        columns = List.copyOf(columns);
    }
}
