package com.olist.datasets.mapper;

import com.olist.datasets.entity.FieldLibrary;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface FieldLibraryMapper {

    int insert(FieldLibrary library);

    int update(FieldLibrary library);

    FieldLibrary findById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    List<FieldLibrary> findAll(
            @Param("ownerId") String ownerId,
            @Param("keywordPattern") String keywordPattern);

    int deleteById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    long countByName(
            @Param("name") String name,
            @Param("ownerId") String ownerId,
            @Param("excludeId") UUID excludeId);
}
