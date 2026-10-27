package com.olist.datasets.controller;

import com.olist.datasets.dto.RuleRunCreateRequest;
import com.olist.datasets.dto.RuleWorkbookCreateRequest;
import com.olist.datasets.dto.RuleWorkbookDraftRequest;
import com.olist.datasets.dto.RuleWorkbookQuery;
import com.olist.datasets.service.RuleWorkbookService;
import com.olist.datasets.vo.PageVO;
import com.olist.datasets.vo.RuleRunVO;
import com.olist.datasets.vo.RuleValidationResultVO;
import com.olist.datasets.vo.RuleWorkbookDetailVO;
import com.olist.datasets.vo.RuleWorkbookVO;
import com.olist.datasets.vo.RuleWorkbookVersionVO;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/rule-workbooks")
public class RuleWorkbookController {

    private final RuleWorkbookService ruleWorkbookService;

    public RuleWorkbookController(RuleWorkbookService ruleWorkbookService) {
        this.ruleWorkbookService = ruleWorkbookService;
    }

    @GetMapping
    public PageVO<RuleWorkbookVO> list(@ModelAttribute RuleWorkbookQuery query) {
        return ruleWorkbookService.list(query);
    }

    @PostMapping
    public ResponseEntity<RuleWorkbookDetailVO> create(
            @Valid @RequestBody RuleWorkbookCreateRequest request) {
        RuleWorkbookDetailVO created = ruleWorkbookService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public RuleWorkbookDetailVO get(@PathVariable UUID id) {
        return ruleWorkbookService.get(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        ruleWorkbookService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/draft")
    public RuleWorkbookDetailVO saveDraft(
            @PathVariable UUID id,
            @Valid @RequestBody RuleWorkbookDraftRequest request) {
        return ruleWorkbookService.saveDraft(id, request);
    }

    @PostMapping("/{id}/validate")
    public RuleValidationResultVO validate(@PathVariable UUID id) {
        return ruleWorkbookService.validate(id);
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<RuleWorkbookVersionVO> publish(@PathVariable UUID id) {
        RuleWorkbookVersionVO version = ruleWorkbookService.publish(id);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .replacePath("/api/v1/rule-workbooks/{id}/versions/{versionNumber}")
                .buildAndExpand(id, version.versionNumber())
                .toUri();
        return ResponseEntity.created(location).body(version);
    }

    @GetMapping("/{id}/versions")
    public List<RuleWorkbookVersionVO> versions(@PathVariable UUID id) {
        return ruleWorkbookService.versions(id);
    }

    @GetMapping("/{id}/versions/{versionNumber}")
    public RuleWorkbookVersionVO getVersion(
            @PathVariable UUID id,
            @PathVariable int versionNumber) {
        return ruleWorkbookService.getVersion(id, versionNumber);
    }

    @PostMapping("/{id}/runs")
    public ResponseEntity<RuleRunVO> createRun(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) RuleRunCreateRequest request) {
        RuleRunVO run = ruleWorkbookService.createRun(id, request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/rule-runs/{runId}")
                .buildAndExpand(run.id())
                .toUri();
        return ResponseEntity.created(location).body(run);
    }

    @GetMapping("/{id}/runs")
    public List<RuleRunVO> runs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "20") int limit) {
        return ruleWorkbookService.runs(id, limit);
    }
}