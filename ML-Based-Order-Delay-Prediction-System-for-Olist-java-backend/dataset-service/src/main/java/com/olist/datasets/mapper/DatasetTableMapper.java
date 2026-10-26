package com.olist.datasets.mapper;

import com.olist.datasets.entity.DatasetTable;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface DatasetTableMapper {

    int insertBatch(@Param("tables") List<DatasetTable> tables);

    List<DatasetTable> findByDatasetId(@Param("datasetId") UUID datasetId);

    DatasetTable findByIdAndDatasetId(@Param("id") UUID id, @Param("datasetId") UUID datasetId);

    int updateName(
            @Param("id") UUID id,
            @Param("datasetId") UUID datasetId,
            @Param("tableName") String tableName);

    int deleteByIdAndDatasetId(@Param("id") UUID id, @Param("datasetId") UUID datasetId);

    long countByFileId(@Param("fileId") UUID fileId);

    int updatePhysicalTable(
            @Param("id") UUID id,
            @Param("datasetId") UUID datasetId,
            @Param("physicalTable") String physicalTable);
}