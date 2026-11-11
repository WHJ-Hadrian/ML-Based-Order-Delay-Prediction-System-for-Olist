package com.olist.models.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ModelTrainingRequest(
        @NotNull UUID datasetId,
        @NotNull UUID datasetVersionId,
        @NotNull UUID tableId,
        @Size(max = 255) String name,
        @NotBlank String task,
        @NotBlank String algorithm,
        @NotBlank @Size(max = 255) String targetColumn,
        @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 255) String> featureColumns,
        @NotNull @DecimalMin("0.1") @DecimalMax("0.4") Double testSize,
        @DecimalMin("0.0") @DecimalMax("0.39") Double validationSize,
        @NotNull Integer randomState,
        String splitStrategy,
        @Size(max = 255) String timeColumn,
        @Size(max = 255) String groupColumn,
        Integer cvFolds,
        Boolean explain) {

    public ModelTrainingRequest {
        if (validationSize == null) {
            validationSize = 0.0;
        }
        if (splitStrategy == null || splitStrategy.isBlank()) {
            splitStrategy = "random";
        }
        if (explain == null) {
            explain = true;
        }
        if (cvFolds == null) {
            cvFolds = 0;
        }
        if (cvFolds != 0 && cvFolds != 3 && cvFolds != 5 && cvFolds != 10) {
            throw new IllegalArgumentException("交叉验证折数只能是 0、3、5 或 10");
        }
    }

    public ModelTrainingRequest(
            UUID datasetId, UUID datasetVersionId, UUID tableId, String name, String task,
            String algorithm, String targetColumn, List<String> featureColumns, Double testSize,
            Double validationSize, Integer randomState, String splitStrategy, String timeColumn,
            String groupColumn, Boolean explain) {
        this(datasetId, datasetVersionId, tableId, name, task, algorithm, targetColumn,
                featureColumns, testSize, validationSize, randomState, splitStrategy,
                timeColumn, groupColumn, 0, explain);
    }
}
