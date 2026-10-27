package com.olist.datasets.mapper;

import com.olist.datasets.entity.RuleWorkbookDraft;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface RuleWorkbookDraftMapper {

    int insert(RuleWorkbookDraft draft);

    RuleWorkbookDraft findByWorkbookId(@Param("workbookId") UUID workbookId);

    int update(
            @Param("draft") RuleWorkbookDraft draft,
            @Param("previousRevision") long previousRevision);
}