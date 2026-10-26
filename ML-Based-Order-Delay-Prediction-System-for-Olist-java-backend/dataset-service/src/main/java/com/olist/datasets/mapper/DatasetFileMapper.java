package com.olist.datasets.mapper;

import com.olist.datasets.entity.DatasetFile;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface DatasetFileMapper {

    int insert(DatasetFile datasetFile);

    List<DatasetFile> findByDatasetId(@Param("datasetId") UUID datasetId);

    DatasetFile findById(@Param("id") UUID id);

    int deleteByIdAndDatasetId(@Param("id") UUID id, @Param("datasetId") UUID datasetId);

    long countByBlobSha256(@Param("blobSha256") String blobSha256);

    long countByStorageKey(@Param("storageKey") String storageKey);
}
