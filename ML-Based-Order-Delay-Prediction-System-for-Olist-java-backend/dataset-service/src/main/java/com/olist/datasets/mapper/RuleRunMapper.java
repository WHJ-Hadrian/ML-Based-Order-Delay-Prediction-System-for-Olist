package com.olist.datasets.mapper;

import com.olist.datasets.entity.RuleRun;
import com.olist.datasets.enums.RuleRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface RuleRunMapper {

    int insert(RuleRun run);

    RuleRun findByIdAndOwner(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId);

    RuleRun findByIdAndOwnerForUpdate(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId);

    List<RuleRun> findByWorkbookId(
            @Param("workbookId") UUID workbookId,
            @Param("ownerId") String ownerId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    int updateStatusIfCurrent(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("expectedStatus") RuleRunStatus expectedStatus,
            @Param("newStatus") RuleRunStatus newStatus,
            @Param("startedAt") Instant startedAt,
            @Param("finishedAt") Instant finishedAt,
            @Param("errorMessage") String errorMessage);
}