package com.olist.analytics.api;

import com.olist.analytics.service.AnalyticsService;
import com.olist.analytics.service.ReportService;
import com.olist.analytics.spec.AnalyticsSpec;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 通用分析接口：能力查询、规格校验、执行、维度取值。 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService service;
    private final ReportService reportService;

    public AnalyticsController(AnalyticsService service, ReportService reportService) {
        this.service = service;
        this.reportService = reportService;
    }

    public record AnalyticsRequest(UUID datasetVersionId, UUID tableId, AnalyticsSpec spec) {
    }

    public record ValuesRequest(UUID datasetVersionId, UUID tableId, String column, Integer limit) {
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(
            @RequestParam UUID datasetVersionId,
            @RequestParam UUID tableId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.capabilities(datasetVersionId, tableId, jwt.getSubject(), authorization);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(
            @RequestBody AnalyticsRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.validate(request.datasetVersionId(), request.tableId(), request.spec(),
                jwt.getSubject(), authorization);
    }

    @PostMapping("/query")
    public Map<String, Object> query(
            @RequestBody AnalyticsRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.query(request.datasetVersionId(), request.tableId(), request.spec(),
                jwt.getSubject(), authorization);
    }

    @PostMapping("/report")
    public org.springframework.http.ResponseEntity<byte[]> report(
            @RequestBody AnalyticsRequest request,
            @RequestParam(defaultValue = "pdf") String format,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ReportService.Rendered rendered = reportService.render(
                jwt.getSubject(), request.datasetVersionId(), request.tableId(),
                request.spec(), format, authorization);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(rendered.contentType()))
                .header("Content-Disposition",
                        "attachment; filename*=UTF-8''" + rendered.filename())
                .body(rendered.body());
    }

    @PostMapping("/values")
    public Map<String, Object> values(
            @RequestBody ValuesRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        int limit = request.limit() == null ? 50 : request.limit();
        return Map.of("values", service.values(
                request.datasetVersionId(), request.tableId(), request.column(), limit, authorization));
    }
}
