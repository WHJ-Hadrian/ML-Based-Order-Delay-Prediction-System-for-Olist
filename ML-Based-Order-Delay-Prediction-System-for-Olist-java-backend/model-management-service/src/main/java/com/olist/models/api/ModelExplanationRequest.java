package com.olist.models.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record ModelExplanationRequest(
        @NotEmpty @Size(max = 20) List<Map<String, Object>> rows,
        @DecimalMin("0.0") @DecimalMax("1.0") Double threshold,
        Boolean includeCounterfactuals) {

    public ModelExplanationRequest {
        if (includeCounterfactuals == null) {
            includeCounterfactuals = true;
        }
    }
}
