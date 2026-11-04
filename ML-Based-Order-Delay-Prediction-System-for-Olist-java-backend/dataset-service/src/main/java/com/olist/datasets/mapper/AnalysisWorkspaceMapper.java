package com.olist.datasets.mapper;

import com.olist.datasets.entity.AnalysisWorkspace;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface AnalysisWorkspaceMapper {

    int insert(AnalysisWorkspace workspace);

    AnalysisWorkspace findById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    List<AnalysisWorkspace> findByOwner(@Param("ownerId") String ownerId);

    int update(@Param("workspace") AnalysisWorkspace workspace, @Param("expectedRevision") long expectedRevision);

    int deleteById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    long countByName(@Param("ownerId") String ownerId, @Param("name") String name,
            @Param("excludeId") UUID excludeId);
}
