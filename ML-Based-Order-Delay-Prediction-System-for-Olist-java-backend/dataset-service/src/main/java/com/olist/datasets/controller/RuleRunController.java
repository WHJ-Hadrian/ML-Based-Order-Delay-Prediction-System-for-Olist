package com.olist.datasets.controller;

import com.olist.datasets.dto.RuleRunFinishRequest;
import com.olist.datasets.dto.RuleRunNodeRequest;
import com.olist.datasets.service.RuleWorkbookService;
import com.olist.datasets.vo.RuleRunExecutionVO;
import com.olist.datasets.vo.RuleRunNodeVO;
import com.olist.datasets.vo.RuleRunVO;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rule-runs")
public class RuleRunController {

    private final RuleWorkbookService ruleWorkbookService;

    public RuleRunController(RuleWorkbookService ruleWorkbookService) {
        this.ruleWorkbookService = ruleWorkbookService;
    }

    @GetMapping("/{runId}")
    public RuleRunVO get(@PathVariable UUID runId) {
        return ruleWorkbookService.getRun(runId);
    }

    @GetMapping("/{runId}/execution")
    public RuleRunExecutionVO execution(@PathVariable UUID runId) {
        return ruleWorkbookService.getRunExecution(runId);
    }

    @PostMapping("/{runId}/start")
    public RuleRunVO start(@PathVariable UUID runId) {
        return ruleWorkbookService.startRun(runId);
    }

    @PostMapping("/{runId}/finish")
    public RuleRunVO finish(
            @PathVariable UUID runId,
            @RequestBody(required = false) RuleRunFinishRequest request) {
        return ruleWorkbookService.finishRun(runId, request);
    }

    @PutMapping("/{runId}/nodes/{nodeId}")
    public RuleRunNodeVO upsertNode(
            @PathVariable UUID runId,
            @PathVariable String nodeId,
            @RequestBody RuleRunNodeRequest request) {
        RuleRunNodeRequest normalized = new RuleRunNodeRequest(
                nodeId,
                request == null ? null : request.nodeKind(),
                request == null ? null : request.status(),
                request == null ? null : request.metrics(),
                request == null ? null : request.sampleUri(),
                request == null ? null : request.errorMessage());
        return ruleWorkbookService.upsertRunNode(runId, normalized);
    }

    @GetMapping("/{runId}/nodes")
    public List<RuleRunNodeVO> nodes(@PathVariable UUID runId) {
        return ruleWorkbookService.runNodes(runId);
    }
}
