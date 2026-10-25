package com.olist.datasets.dto;

import com.olist.datasets.enums.RuleSeverity;
import com.olist.datasets.enums.RuleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CleaningRuleRequest {

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 128, message = "规则名称不能超过 128 个字符")
    private String name;

    @NotNull(message = "规则类型不能为空")
    private RuleType ruleType;

    @Size(max = 128, message = "目标字段不能超过 128 个字符")
    private String targetField;

    private RuleSeverity severity;

    @Size(max = 2000, message = "规则表达式不能超过 2000 个字符")
    private String expression;

    @Size(max = 1000, message = "规则说明不能超过 1000 个字符")
    private String description;

    private Boolean enabled;

    @Min(value = 0, message = "优先级不能小于 0")
    @Max(value = 10000, message = "优先级不能大于 10000")
    private Integer priority;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public RuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(RuleType ruleType) {
        this.ruleType = ruleType;
    }

    public String getTargetField() {
        return targetField;
    }

    public void setTargetField(String targetField) {
        this.targetField = targetField;
    }

    public RuleSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(RuleSeverity severity) {
        this.severity = severity;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }
}
