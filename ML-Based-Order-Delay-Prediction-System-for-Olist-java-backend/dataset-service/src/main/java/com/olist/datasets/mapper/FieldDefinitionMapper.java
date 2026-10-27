package com.olist.datasets.mapper;

import com.olist.datasets.entity.FieldDefinition;
import com.olist.datasets.enums.FieldStatus;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface FieldDefinitionMapper {

    int insert(FieldDefinition field);

    int update(FieldDefinition field);

    FieldDefinition findById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    List<FieldDefinition> findByLibraryId(
            @Param("libraryId") UUID libraryId,
            @Param("ownerId") String ownerId);

    List<FieldDefinition> findPage(
            @Param("keywordPattern") String keywordPattern,
            @Param("status") FieldStatus status,
            @Param("semanticRole") String semanticRole,
            @Param("ownerId") String ownerId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    long count(
            @Param("keywordPattern") String keywordPattern,
            @Param("status") FieldStatus status,
            @Param("semanticRole") String semanticRole,
            @Param("ownerId") String ownerId);

    int deleteById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    long countByName(
            @Param("name") String name,
            @Param("ownerId") String ownerId,
            @Param("excludeId") UUID excludeId);
}
