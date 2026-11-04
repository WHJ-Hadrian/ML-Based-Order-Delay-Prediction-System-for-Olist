package com.olist.datasets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnalysisWorkspaceCopyRequest(
        @NotBlank @Size(max = 255) String name) {
}
