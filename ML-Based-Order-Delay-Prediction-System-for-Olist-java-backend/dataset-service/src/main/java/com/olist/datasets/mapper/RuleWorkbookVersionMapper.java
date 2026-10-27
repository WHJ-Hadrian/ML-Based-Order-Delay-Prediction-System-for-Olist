package com.olist.datasets.mapper;

import com.olist.datasets.entity.RuleWorkbookVersion;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface RuleWorkbookVersionMapper {

    int insert(RuleWorkbookVersion version);

    List<RuleWorkbookVersion> findByWorkbookId(
            @Param("workbookId") UUID workbookId,
            @Param("ownerId") String ownerId);

    RuleWorkbookVersion findByIdAndOwner(
            @Param("id") UUID id,
            @Param("ownerId") String ownerId);

    RuleWorkbookVersion findByNumberAndOwner(
            @Param("workbookId") UUID workbookId,
            @Param("versionNumber") int versionNumber,
            @Param("ownerId") String ownerId);

    RuleWorkbookVersion findLatestByWorkbook(
            @Param("workbookId") UUID workbookId,
            @Param("ownerId") String ownerId);

    int findMaxVersionNumber(@Param("workbookId") UUID workbookId);
}