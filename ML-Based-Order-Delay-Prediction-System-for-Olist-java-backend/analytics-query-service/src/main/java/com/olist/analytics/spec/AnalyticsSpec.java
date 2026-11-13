package com.olist.analytics.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** 分析规格：指标 + 维度 + 时间 + 筛选 + having + 排序，渲染在 visuals/report 里声明。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalyticsSpec(
        Integer schemaVersion,
        Source source,
        List<MetricSpec> metrics,
        List<DimensionSpec> dimensions,
        TimeSpec time,
        List<FilterSpec> filters,
        List<HavingSpec> having,
        List<OrderSpec> orderBy,
        Integer limit,
        List<VisualSpec> visuals,
        ReportSpec report) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String datasetVersionId, String tableId) {
    }

    /** ref = 指标库里的 key；或直接给 type=aggregate + op + column。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetricSpec(String ref, String type, String op, String column, String alias) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DimensionSpec(String column, String alias, String grain, Integer limit, SortSpec sort) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SortSpec(String by, String dir) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimeSpec(String column, String grain, Range range) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Range(String from, String to) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilterSpec(String column, String op, List<String> values, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HavingSpec(String metric, String op, Double value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderSpec(String key, String dir) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VisualSpec(String type, String x, String y, String series, List<String> columns) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReportSpec(String title, List<String> sections) {
    }
}
