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
        @NotNull Integer randomState) {
}
