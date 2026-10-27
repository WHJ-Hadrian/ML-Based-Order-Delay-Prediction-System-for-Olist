package com.olist.datasets.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public class RuleRunCreateRequest {

    private UUID workbookVersionId;

    @Pattern(regexp = "PREVIEW|FULL", message = "运行模式只能是 PREVIEW 或 FULL")
    private String mode = "FULL";

    @Min(value = 1, message = "预览行数必须大于 0")
    @Max(value = 100000, message = "预览行数不能超过 100000")
    private Integer sampleSize;

    private Long seed;

    public UUID getWorkbookVersionId() { return workbookVersionId; }
    public void setWorkbookVersionId(UUID workbookVersionId) { this.workbookVersionId = workbookVersionId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Integer getSampleSize() { return sampleSize; }
    public void setSampleSize(Integer sampleSize) { this.sampleSize = sampleSize; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
}