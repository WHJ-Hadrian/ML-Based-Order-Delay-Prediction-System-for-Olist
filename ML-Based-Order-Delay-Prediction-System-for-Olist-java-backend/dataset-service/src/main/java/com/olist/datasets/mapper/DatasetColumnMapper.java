package com.olist.datasets.mapper;

import com.olist.datasets.entity.DatasetColumn;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface DatasetColumnMapper {

    int insertBatch(@Param("columns") List<DatasetColumn> columns);

    List<DatasetColumn> findByDatasetVersionId(
            @Param("datasetVersionId") UUID datasetVersionId);

    List<DatasetColumn> findByTableId(@Param("tableId") UUID tableId);
}
