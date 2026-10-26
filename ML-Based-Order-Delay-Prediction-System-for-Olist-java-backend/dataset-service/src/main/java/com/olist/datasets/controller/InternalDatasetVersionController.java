package com.olist.datasets.controller;

import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/dataset-versions")
public class InternalDatasetVersionController {

    private final DatasetService datasetService;

    public InternalDatasetVersionController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @GetMapping("/{id}/execution-snapshot")
    public ExecutionSnapshotVO executionSnapshot(@PathVariable UUID id) {
        return datasetService.executionSnapshot(id);
    }
}