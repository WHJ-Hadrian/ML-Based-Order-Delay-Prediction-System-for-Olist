package com.olist.analytics.api;

import com.olist.analytics.service.AnalyticsService;
import com.olist.analytics.spec.AnalyticsSpec;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 通用分析接口：能力查询、规格校验、执行。 */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    public record AnalyticsRequest(UUID datasetVersionId, UUID tableId, AnalyticsSpec spec) {
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(
            @RequestParam UUID datasetVersionId, @RequestParam UUID tableId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.capabilities(datasetVersionId, tableId, authorization);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody AnalyticsRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.validate(request.datasetVersionId(), request.tableId(), request.spec(), authorization);
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody AnalyticsRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.query(request.datasetVersionId(), request.tableId(), request.spec(), authorization);
    }
}
