package com.olist.datasets.mapper;

import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.enums.DatasetStatus;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface DatasetVersionMapper {

    int insert(DatasetVersion datasetVersion);

    DatasetVersion findById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    DatasetVersion findByIdForUpdate(@Param("id") UUID id, @Param("ownerId") String ownerId);

    List<DatasetVersion> findPage(
            @Param("status") DatasetStatus status,
            @Param("keywordPattern") String keywordPattern,
            @Param("formatPattern") String formatPattern,
            @Param("hasData") Boolean hasData,
            @Param("ownerId") String ownerId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    long count(
            @Param("status") DatasetStatus status,
            @Param("keywordPattern") String keywordPattern,
            @Param("formatPattern") String formatPattern,
            @Param("hasData") Boolean hasData,
            @Param("ownerId") String ownerId);

    int update(DatasetVersion datasetVersion);

    int deleteById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    List<String> findNamesStartingWith(@Param("pattern") String pattern, @Param("ownerId") String ownerId);
}
