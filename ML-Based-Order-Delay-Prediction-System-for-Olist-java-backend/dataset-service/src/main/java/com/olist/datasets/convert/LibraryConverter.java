package com.olist.datasets.convert;

import com.olist.datasets.entity.CleaningRule;
import com.olist.datasets.entity.FieldDefinition;
import com.olist.datasets.entity.FieldLibrary;
import com.olist.datasets.vo.CleaningRuleVO;
import com.olist.datasets.vo.FieldDefinitionVO;
import com.olist.datasets.vo.FieldLibraryFieldVO;
import com.olist.datasets.vo.FieldLibraryVO;

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

    public static FieldLibraryFieldVO toLibraryFieldVO(FieldDefinition entity) {
        return new FieldLibraryFieldVO(
                entity.getId(),
                entity.getLibraryId(),
                entity.getName(),
                entity.getDisplayName(),
                entity.getDataType(),
                entity.getSemanticRole(),
                entity.getSourceTable(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getSourceDatasetId(),
                entity.getSourceDatasetName(),
                entity.getSourceTableName(),
                entity.getSourceColumnName(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public static FieldLibraryVO toVO(FieldLibrary entity) {
        return new FieldLibraryVO(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getSourceType(),
                entity.getFieldCount(),
                entity.getDatasetCount(),
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
