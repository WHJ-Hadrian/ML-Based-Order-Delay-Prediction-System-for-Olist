package com.olist.datasets.controller;

import com.olist.datasets.security.WorkflowWorkerAuthenticator;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import com.olist.datasets.vo.TablePreviewVO;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/workflow-datasets")
public class InternalWorkflowDatasetController {
    private final DatasetService datasetService;
    private final WorkflowWorkerAuthenticator authenticator;

    public InternalWorkflowDatasetController(
            DatasetService datasetService, WorkflowWorkerAuthenticator authenticator) {
        this.datasetService = datasetService;
        this.authenticator = authenticator;
    }

    @GetMapping("/{id}/execution-snapshot")
    public ExecutionSnapshotVO executionSnapshot(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Workflow-Worker-Token", required = false) String token,
            @RequestHeader(value = "X-Workflow-Owner-Id", required = false) String ownerId) {
        authenticator.requireValid(token);
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流 owner 标识无效");
        }
        return datasetService.executionSnapshot(id, ownerId);
    }

    @GetMapping("/{versionId}/tables/{tableId}/preview")
    public TablePreviewVO preview(
            @PathVariable UUID versionId,
            @PathVariable UUID tableId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Workflow-Worker-Token", required = false) String token,
            @RequestHeader(value = "X-Workflow-Owner-Id", required = false) String ownerId) {
        authenticator.requireValid(token);
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流 owner 标识无效");
        }
        return datasetService.workflowPreview(versionId, tableId, ownerId, limit);
    }
}
