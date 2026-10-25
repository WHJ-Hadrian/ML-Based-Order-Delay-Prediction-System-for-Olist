package com.olist.datasets.mapper;

import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.enums.DatasetStatus;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface DatasetVersionMapper {

    int insert(DatasetVersion datasetVersion);

    DatasetVersion findById(@Param("id") UUID id);

    DatasetVersion findByIdForUpdate(@Param("id") UUID id);

    List<DatasetVersion> findPage(
            @Param("status") DatasetStatus status,
            @Param("keywordPattern") String keywordPattern,
            @Param("formatPattern") String formatPattern,
            @Param("hasData") Boolean hasData,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long count(
            @Param("status") DatasetStatus status,
            @Param("keywordPattern") String keywordPattern,
            @Param("formatPattern") String formatPattern,
            @Param("hasData") Boolean hasData);

    int update(DatasetVersion datasetVersion);

    int deleteById(@Param("id") UUID id);

    List<String> findNamesStartingWith(@Param("pattern") String pattern);
}