package com.olist.datasets.controller;

import com.olist.datasets.security.ModelTrainingDatasetAuthenticator;
import com.olist.datasets.service.DatasetService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 训练面专用数据出口：只暴露训练 worker 需要的流式导出，与工作流执行快照隔离。
 */
@RestController
@RequestMapping("/internal/model-training/dataset-versions")
public class InternalModelTrainingDatasetController {

    private static final String TRAINING_TOKEN_HEADER = "X-Model-Training-Token";
    private final DatasetService datasetService;
    private final ModelTrainingDatasetAuthenticator authenticator;

    public InternalModelTrainingDatasetController(
            DatasetService datasetService,
            ModelTrainingDatasetAuthenticator authenticator) {
        this.datasetService = datasetService;
        this.authenticator = authenticator;
    }

    @GetMapping("/{id}/tables/{tableId}/export.csv")
    public void exportTable(
            @PathVariable UUID id,
            @PathVariable UUID tableId,
            @RequestParam String ownerId,
            @RequestParam(required = false) String columns,
            @RequestParam(defaultValue = "100000") int maxRows,
            @RequestHeader(value = TRAINING_TOKEN_HEADER, required = false) String trainingToken,
            HttpServletResponse response) throws IOException {
        authenticator.requireValid(trainingToken);
        List<String> selected = columns == null
                ? List.of()
                : Arrays.stream(columns.split(","))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .toList();
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"export-" + tableId + ".csv\"");
        datasetService.exportTable(id, tableId, ownerId, selected, maxRows, response.getOutputStream());
    }
}
