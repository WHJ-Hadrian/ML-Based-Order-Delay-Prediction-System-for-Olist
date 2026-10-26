package com.olist.datasets.controller;

import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.DatasetCatalogVO;
import com.olist.datasets.vo.DatasetReadinessVO;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dataset-versions")
public class DatasetVersionController {

    private final DatasetService datasetService;

    public DatasetVersionController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @GetMapping("/{id}/catalog")
    public DatasetCatalogVO catalog(@PathVariable UUID id) {
        return datasetService.catalog(id);
    }

    @GetMapping("/{id}/readiness")
    public DatasetReadinessVO readiness(@PathVariable UUID id) {
        return datasetService.readiness(id);
    }
}