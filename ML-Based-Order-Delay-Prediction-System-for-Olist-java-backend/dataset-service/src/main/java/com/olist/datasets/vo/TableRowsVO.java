package com.olist.datasets.vo;

import java.util.List;
import java.util.UUID;

public record TableRowsVO(
        UUID tableId,
        String name,
        int offset,
        int limit,
        long totalRows,
        List<String> columns,
        List<List<String>> rows) {
}