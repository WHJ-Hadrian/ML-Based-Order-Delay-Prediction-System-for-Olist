package com.olist.datasets.convert;

import com.olist.datasets.entity.CleaningRule;
import com.olist.datasets.entity.FieldDefinition;
import com.olist.datasets.vo.CleaningRuleVO;
import com.olist.datasets.vo.FieldDefinitionVO;

public final class LibraryConverter {

    private LibraryConverter() {
    }

    public static FieldDefinitionVO toVO(FieldDefinition entity) {
        return new FieldDefinitionVO(
                entity.getId(),
                entity.getName(),
                entity.getDisplayName(),
                entity.getDataType(),
                entity.getSemanticRole(),
                entity.getSourceTable(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public static CleaningRuleVO toVO(CleaningRule entity) {
        return new CleaningRuleVO(
                entity.getId(),
                entity.getName(),
                entity.getRuleType(),
                entity.getTargetField(),
                entity.getSeverity(),
                entity.getRuleExpression(),
                entity.getDescription(),
                entity.isEnabled(),
                entity.getPriority(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
