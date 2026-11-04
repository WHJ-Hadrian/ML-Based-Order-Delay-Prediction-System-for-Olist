package com.olist.datasets.controller;

import com.olist.datasets.service.DatasetAnalysisService;
import com.olist.datasets.vo.DatasetColumnAnalysisVO;
import com.olist.datasets.vo.DatasetColumnRelationVO;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetAnalysisController {

    private final DatasetAnalysisService analysisService;

    public DatasetAnalysisController(DatasetAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @GetMapping("/{datasetId}/tables/{tableId}/analysis")
    public DatasetColumnAnalysisVO analyzeColumn(
            @PathVariable UUID datasetId,
            @PathVariable UUID tableId,
            @RequestParam UUID columnId) {
        return analysisService.analyzeColumn(datasetId, tableId, columnId);
    }

    @GetMapping("/{datasetId}/tables/{tableId}/relation")
    public DatasetColumnRelationVO analyzeRelation(
            @PathVariable UUID datasetId,
            @PathVariable UUID tableId,
            @RequestParam UUID xColumnId,
            @RequestParam UUID yColumnId) {
        return analysisService.analyzeRelation(datasetId, tableId, xColumnId, yColumnId);
    }
}
