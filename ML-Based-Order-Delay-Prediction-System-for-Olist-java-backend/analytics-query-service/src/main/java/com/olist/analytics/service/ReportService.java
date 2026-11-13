package com.olist.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.olist.analytics.spec.AnalyticsSpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 把一次分析的结果组装成通用文档，交给渲染服务输出 PDF/Word/HTML。 */
@Service
public class ReportService {

    public record Rendered(byte[] body, String contentType, String filename) {
    }

    private static final Map<String, String> MEDIA_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "html", "text/html; charset=utf-8");

    private final AnalyticsService analyticsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String serviceToken;

    public ReportService(
            AnalyticsService analyticsService,
            @Value("${olist.report-service.url:http://localhost:8090}") String baseUrl,
            @Value("${olist.report-service.token:local-training-token-2026}") String serviceToken) {
        this.analyticsService = analyticsService;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.serviceToken = serviceToken;
    }

    public Rendered render(String ownerId, java.util.UUID datasetVersionId, java.util.UUID tableId,
            AnalyticsSpec spec, String format, String authorization) {
        String normalized = format == null ? "pdf" : format.toLowerCase();
        if (!MEDIA_TYPES.containsKey(normalized)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "仅支持 pdf / docx / html");
        }
        Map<String, Object> result = analyticsService.query(
                datasetVersionId, tableId, spec, ownerId, authorization);
        String title = spec.report() != null && spec.report().title() != null
                ? spec.report().title()
                : "分析报告";
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", title);
        payload.put("format", normalized);
        ArrayNode meta = payload.putArray("meta");
        appendMeta(meta, "结果行数", String.valueOf(result.get("rowCount")));
        appendMeta(meta, "执行耗时", result.get("elapsedMs") + " ms");
        appendMeta(meta, "指标数", String.valueOf(spec.metrics() == null ? 0 : spec.metrics().size()));
        appendMeta(meta, "维度数", String.valueOf(spec.dimensions() == null ? 0 : spec.dimensions().size()));
        ArrayNode sections = payload.putArray("sections");

        ObjectNode definitions = sections.addObject();
        definitions.put("heading", "一、分析口径");
        ArrayNode definitionLines = definitions.putArray("paragraphs");
        definitionLines.add("指标：" + metricsText(spec));
        definitionLines.add("维度：" + dimensionsText(spec));
        definitionLines.add("时间：" + timeText(spec));
        definitionLines.add("筛选：" + filtersText(spec));

        ObjectNode tableSection = sections.addObject();
        tableSection.put("heading", "二、分析结果");
        ArrayNode tableParagraphs = tableSection.putArray("paragraphs");
        tableParagraphs.add("共 " + result.get("rowCount") + " 行，耗时 " + result.get("elapsedMs") + " ms。");
        ArrayNode table = tableSection.putArray("table");
        List<Map<String, Object>> columns = castList(result.get("columns"));
        ArrayNode header = table.addArray();
        for (Map<String, Object> column : columns) {
            header.add(String.valueOf(column.get("label")));
        }
        List<List<Object>> rows = castRows(result.get("rows"));
        int limit = Math.min(rows.size(), 300);
        for (int index = 0; index < limit; index++) {
            ArrayNode line = table.addArray();
            for (Object cell : rows.get(index)) {
                line.add(cell == null ? "" : String.valueOf(cell));
            }
        }

        ObjectNode note = sections.addObject();
        note.put("heading", "三、说明");
        note.putArray("paragraphs").add("本报告由通用分析引擎按当前分析方案生成，结果口径与页面预览一致。");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/v1/report-renders"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + serviceToken)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "报告渲染失败（HTTP " + response.statusCode() + "）");
            }
            String filename = URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20")
                    + "." + normalized;
            return new Rendered(response.body(), MEDIA_TYPES.get(normalized), filename);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "报告服务不可用：" + error.getMessage(), error);
        }
    }

    private static void appendMeta(ArrayNode meta, String label, String value) {
        ArrayNode row = meta.addArray();
        row.add(label);
        row.add(value);
    }

    private static String metricsText(AnalyticsSpec spec) {
        if (spec.metrics() == null || spec.metrics().isEmpty()) {
            return "—";
        }
        return String.join("、", spec.metrics().stream()
                .map(metric -> metric.alias() != null ? metric.alias()
                        : metric.ref() != null ? metric.ref()
                        : (metric.op() == null ? "" : metric.op()) + "(" + metric.column() + ")")
                .toList());
    }

    private static String dimensionsText(AnalyticsSpec spec) {
        if (spec.dimensions() == null || spec.dimensions().isEmpty()) {
            return "无（总计）";
        }
        return String.join("、", spec.dimensions().stream()
                .map(dimension -> dimension.alias() != null && !dimension.alias().isBlank()
                        ? dimension.alias() : dimension.column())
                .toList());
    }

    private static String timeText(AnalyticsSpec spec) {
        if (spec.time() == null) {
            return "未使用";
        }
        StringBuilder text = new StringBuilder(spec.time().column() + "（" + spec.time().grain() + "）");
        if (spec.time().range() != null) {
            text.append("，")
                    .append(spec.time().range().from() == null ? "不限" : spec.time().range().from())
                    .append(" ~ ")
                    .append(spec.time().range().to() == null ? "不限" : spec.time().range().to());
        }
        return text.toString();
    }

    private static String filtersText(AnalyticsSpec spec) {
        if (spec.filters() == null || spec.filters().isEmpty()) {
            return "无";
        }
        return String.join("；", spec.filters().stream()
                .map(filter -> filter.column() + " " + filter.op() + " "
                        + (filter.values() == null ? "" : String.join(",", filter.values())))
                .toList());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> castRows(Object value) {
        return value instanceof List<?> list ? (List<List<Object>>) list : List.of();
    }
}
