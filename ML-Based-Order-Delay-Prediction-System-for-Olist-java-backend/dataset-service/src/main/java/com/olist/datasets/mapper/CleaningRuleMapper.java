package com.olist.datasets.mapper;

import com.olist.datasets.entity.CleaningRule;
import com.olist.datasets.enums.RuleType;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface CleaningRuleMapper {

    int insert(CleaningRule rule);

    int update(CleaningRule rule);

    CleaningRule findById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    List<CleaningRule> findPage(
            @Param("keywordPattern") String keywordPattern,
            @Param("ruleType") RuleType ruleType,
            @Param("enabled") Boolean enabled,
            @Param("ownerId") String ownerId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    long count(
            @Param("keywordPattern") String keywordPattern,
            @Param("ruleType") RuleType ruleType,
            @Param("enabled") Boolean enabled,
            @Param("ownerId") String ownerId);

    int deleteById(@Param("id") UUID id, @Param("ownerId") String ownerId);

    long countByName(
            @Param("name") String name,
            @Param("ownerId") String ownerId,
            @Param("excludeId") UUID excludeId);
}
