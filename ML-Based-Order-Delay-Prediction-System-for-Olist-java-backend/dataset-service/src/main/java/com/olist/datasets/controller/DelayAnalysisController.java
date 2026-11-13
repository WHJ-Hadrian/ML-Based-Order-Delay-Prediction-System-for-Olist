package com.olist.datasets.controller;

import com.olist.datasets.service.DelayAnalysisService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 物流延误分析：整体指标、签收结果分布与维度延误率。 */
@RestController
@RequestMapping("/api/v1/datasets")
public class DelayAnalysisController {

    private final DelayAnalysisService service;

    public DelayAnalysisController(DelayAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/{datasetId}/tables/{tableId}/delay-analysis")
    public Map<String, Object> analyze(@PathVariable UUID datasetId, @PathVariable UUID tableId) {
        return service.analyze(datasetId, tableId);
    }
}
