package com.olist.datasets.dto;

import jakarta.validation.constraints.NotNull;

public class CleaningRuleEnabledRequest {

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
