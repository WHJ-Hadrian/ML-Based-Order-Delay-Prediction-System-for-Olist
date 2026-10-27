package com.olist.datasets.mapper;

import com.olist.datasets.entity.RuleWorkbook;
import com.olist.datasets.enums.RuleWorkbookStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface RuleWorkbookMapper {

    int insert(RuleWorkbook workbook);

    RuleWorkbook findById(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId);

    List<RuleWorkbook> findPage(
            @Param("status") RuleWorkbookStatus status,
            @Param("keywordPattern") String keywordPattern,
            @Param("ownerId") String ownerId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    long count(
            @Param("status") RuleWorkbookStatus status,
            @Param("keywordPattern") String keywordPattern,
            @Param("ownerId") String ownerId);

    int updateStatus(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("status") RuleWorkbookStatus status,
            @Param("updatedAt") Instant updatedAt);

    int touch(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId,
            @Param("updatedAt") Instant updatedAt);

    int deleteById(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId);

    long countByName(
            @Param("name") String name,
            @Param("ownerId") String ownerId,
            @Param("excludeId") UUID excludeId);
}