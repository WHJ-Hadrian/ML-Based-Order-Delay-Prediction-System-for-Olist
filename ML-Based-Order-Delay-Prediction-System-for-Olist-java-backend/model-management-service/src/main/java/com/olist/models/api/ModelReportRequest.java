package com.olist.models.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record ModelReportRequest(
        @NotBlank @Pattern(regexp = "pdf|docx|html|json|csv") String format,
        @Size(max = 20) List<String> sections,
        @Size(min = 1, max = 200) String title,
        List<Map<String, Object>> comparison,
        Map<String, Object> sampleExplanation) {

    public ModelReportRequest {
        if (format == null || format.isBlank()) {
            format = "pdf";
        }
        if (title == null || title.isBlank()) {
            title = "模型评估报告";
        }
        if (sections == null || sections.isEmpty()) {
            sections = List.of("overview", "comparison", "detail", "importance", "sample", "conclusion");
        }
        if (comparison == null) {
            comparison = List.of();
        }
    }
}
