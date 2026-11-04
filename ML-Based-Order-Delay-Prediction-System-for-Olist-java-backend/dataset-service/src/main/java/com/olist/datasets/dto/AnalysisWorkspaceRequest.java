package com.olist.datasets.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record AnalysisWorkspaceRequest(
        @NotBlank @Size(max = 255) String name,
        Long expectedRevision,
        @NotNull @Size(max = 20) List<@Valid CardRef> cards) {

    public record CardRef(
            @NotNull UUID datasetVersionId,
            @NotNull UUID tableId,
            @NotNull UUID columnId,
            UUID relationColumnId) {
    }
}
