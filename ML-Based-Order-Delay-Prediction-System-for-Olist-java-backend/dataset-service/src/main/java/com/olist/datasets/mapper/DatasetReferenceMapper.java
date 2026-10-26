package com.olist.datasets.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface DatasetReferenceMapper {

    long countByDatasetVersionId(@Param("datasetVersionId") UUID datasetVersionId);
}