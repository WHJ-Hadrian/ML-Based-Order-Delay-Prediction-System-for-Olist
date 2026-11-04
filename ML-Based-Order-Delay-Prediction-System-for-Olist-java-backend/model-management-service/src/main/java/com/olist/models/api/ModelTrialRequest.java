package com.olist.models.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ModelTrialRequest(
        @NotNull UUID datasetId,
        @NotNull UUID datasetVersionId,
        @NotNull UUID tableId,
        @Size(max = 255) String targetColumn) {
}
