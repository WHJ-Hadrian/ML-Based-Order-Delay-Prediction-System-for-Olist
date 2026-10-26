package com.olist.datasets.vo;

import com.olist.datasets.enums.DatasetStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DatasetCatalogVO(
        UUID datasetId,
        UUID datasetVersionId,
        int versionNo,
        UUID parentVersionId,
        DatasetStatus status,
        String manifestHash,
        String schemaHash,
        Instant createdAt,
        List<CatalogTableVO> tables) {

    public record CatalogTableVO(
            UUID tableId,
            String tableKey,
            String tableName,
            int ordinal,
            long rowCount,
            int columnCount,
            List<CatalogColumnVO> columns) {
    }

    public record CatalogColumnVO(
            UUID columnId,
            String name,
            int ordinal,
            String logicalType,
            boolean nullable) {
    }
}