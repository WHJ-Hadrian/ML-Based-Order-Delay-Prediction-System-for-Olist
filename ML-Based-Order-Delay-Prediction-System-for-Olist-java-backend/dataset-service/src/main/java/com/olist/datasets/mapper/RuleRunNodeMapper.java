package com.olist.datasets.mapper;

import com.olist.datasets.entity.RuleRunNode;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface RuleRunNodeMapper {

    int insert(RuleRunNode node);

    int update(RuleRunNode node);

    RuleRunNode findByRunIdAndNodeId(
            @Param("runId") UUID runId,
            @Param("nodeId") String nodeId);

    List<RuleRunNode> findByRunId(@Param("runId") UUID runId);
}