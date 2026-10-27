package com.olist.datasets.vo;

import com.olist.datasets.enums.FieldDataType;
import java.util.UUID;

public record FieldCatalogItemVO(
        UUID columnId,
        UUID fieldId,
        String fieldName,
        String fieldDisplayName,
        UUID datasetId,
        String datasetName,
        UUID tableId,
        String tableName,
        String columnName,
        FieldDataType dataType,
        int ordinal,
        boolean imported) {
}