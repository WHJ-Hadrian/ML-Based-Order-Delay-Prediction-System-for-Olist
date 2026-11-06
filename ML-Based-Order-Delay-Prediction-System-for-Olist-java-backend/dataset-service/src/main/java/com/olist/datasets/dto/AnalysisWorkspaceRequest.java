package com.olist.datasets.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
            UUID relationColumnId,
            @Pattern(regexp = "chart|table", message = "分析卡片视图只能是 chart 或 table") String displayMode) {
    }
}
