package com.olist.models.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/** 提交一次单笔预测：模型 + 一行特征值。 */
public record ModelPredictionRequest(
        @NotNull UUID modelId,
        @NotEmpty @Size(max = 200) Map<String, Object> features,
        Boolean includeCounterfactuals) {

    public ModelPredictionRequest {
        if (includeCounterfactuals == null) {
            includeCounterfactuals = false;
        }
    }
}
