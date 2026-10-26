package com.olist.datasets.mapper;

import com.olist.datasets.entity.Dataset;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface DatasetMapper {

    int insert(Dataset dataset);

    Dataset findById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    Dataset findByIdForUpdate(@Param("id") UUID id, @Param("ownerId") String ownerId);

    int updateMetadata(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("updatedAt") Instant updatedAt);

    int updateCurrentVersion(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("currentVersionId") UUID currentVersionId,
            @Param("updatedAt") Instant updatedAt);

    int softDelete(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("deletedAt") Instant deletedAt,
            @Param("updatedAt") Instant updatedAt);

    int deleteIfNoVersions(@Param("id") UUID id, @Param("ownerId") String ownerId);
}
