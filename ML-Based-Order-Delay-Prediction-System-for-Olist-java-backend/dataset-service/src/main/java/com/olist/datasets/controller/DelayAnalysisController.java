package com.olist.datasets.controller;

import com.olist.datasets.service.DelayAnalysisQuery;
import com.olist.datasets.service.DelayAnalysisService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 物流延误分析：整体指标、签收结果分布与维度延误率，支持时间/类目/州筛选。 */
@RestController
@RequestMapping("/api/v1/datasets")
public class DelayAnalysisController {

    private final DelayAnalysisService service;

    public DelayAnalysisController(DelayAnalysisService service) {
        this.service = service;
    }

    @GetMapping("/{datasetId}/tables/{tableId}/delay-analysis")
    public Map<String, Object> analyze(
            @PathVariable UUID datasetId,
            @PathVariable UUID tableId,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) List<String> sellerState,
            @RequestParam(required = false) List<String> customerState) {
        return service.analyze(datasetId, tableId,
                new DelayAnalysisQuery(groupBy, from, to, category, sellerState, customerState));
    }
}
