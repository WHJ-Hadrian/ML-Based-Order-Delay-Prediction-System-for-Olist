package com.olist.datasets.profiler;

import java.util.List;

public record DatasetProfile(List<TableProfile> tables) {

    public DatasetProfile {
        tables = List.copyOf(tables);
    }

    public int tableCount() {
        return tables.size();
    }

    public long rowCount() {
        return tables.stream().mapToLong(TableProfile::rowCount).sum();
    }
}
