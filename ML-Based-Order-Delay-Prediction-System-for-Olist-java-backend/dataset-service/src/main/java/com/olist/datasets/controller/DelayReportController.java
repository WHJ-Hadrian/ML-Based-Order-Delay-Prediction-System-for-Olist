package com.olist.datasets.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.datasets.service.DelayAnalysisService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 延误分析报告：把当前数据表的分析结果渲染成 PDF / Word / HTML 下载。 */
@RestController
@RequestMapping("/api/v1/datasets")
public class DelayReportController {

    private static final String TITLE = "Olist 订单延误分析报告";

    private final DelayAnalysisService analysisService;
    private final ObjectMapper objectMapper;
    private final String reportServiceUrl;
    private final String internalToken;

    public DelayReportController(
            DelayAnalysisService analysisService,
            ObjectMapper objectMapper,
            @Value("${olist.report-service.url:http://localhost:8090}") String reportServiceUrl,
            @Value("${olist.model-training.internal-token:}") String internalToken) {
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
        this.reportServiceUrl = reportServiceUrl;
        this.internalToken = internalToken;
    }

    @GetMapping("/{datasetId}/tables/{tableId}/delay-analysis/report")
    public ResponseEntity<byte[]> report(
            @PathVariable UUID datasetId,
            @PathVariable UUID tableId,
            @RequestParam(defaultValue = "pdf") String format) throws IOException, InterruptedException {
        String normalized = format == null ? "pdf" : format.toLowerCase();
        if (!normalized.equals("pdf") && !normalized.equals("docx") && !normalized.equals("html")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "报告格式只支持 pdf、docx、html");
        }
        if (internalToken == null || internalToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "报告服务令牌尚未配置");
        }

        Map<String, Object> data = analysisService.analyze(datasetId, tableId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", TITLE);
        payload.put("format", normalized);
        payload.put("data", data);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(reportServiceUrl + "/internal/v1/analysis-reports"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + internalToken)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "报告生成失败：" + response.statusCode() + " " + new String(response.body()));
        }
        String contentType = switch (normalized) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "html" -> "text/html; charset=utf-8";
            default -> "application/pdf";
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"analysis-report." + normalized + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(response.body());
    }
}
