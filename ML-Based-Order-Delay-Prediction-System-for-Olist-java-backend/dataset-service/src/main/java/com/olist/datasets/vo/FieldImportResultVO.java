package com.olist.datasets.vo;

import java.util.List;
import java.util.UUID;

public record FieldImportResultVO(
        UUID datasetVersionId,
        int created,
        int skipped,
        List<FieldCatalogItemVO> items) {
}