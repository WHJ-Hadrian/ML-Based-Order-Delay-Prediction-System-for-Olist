package com.olist.models.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ModelTrialTestSource(
        @NotNull UUID datasetId,
        @NotNull UUID datasetVersionId,
        @NotNull UUID tableId) {
}
