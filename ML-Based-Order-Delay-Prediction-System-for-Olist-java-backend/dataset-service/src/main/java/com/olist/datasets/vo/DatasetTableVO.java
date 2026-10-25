package com.olist.datasets.vo;

import com.olist.datasets.enums.DatasetFormat;
import java.util.UUID;

public record DatasetTableVO(
        UUID id,
        String name,
        String fileName,
        DatasetFormat format,
        long rowCount,
        int columnCount) {
}
