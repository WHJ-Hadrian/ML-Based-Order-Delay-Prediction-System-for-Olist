package com.olist.models.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ModelTrialCompareRequest(
        @NotEmpty @Size(max = 5) List<@jakarta.validation.Valid ModelTrialTestSource> testSources,
        @NotEmpty @Size(min = 2, max = 5) List<@NotNull UUID> modelIds,
        @Size(max = 255) String targetColumn) {
}
