package com.olist.jobs.api;

import com.olist.jobs.service.WorkflowRunService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workflow-runs")
public class WorkflowRunController {
    private final WorkflowRunService service;

    public WorkflowRunController(WorkflowRunService service) { this.service = service; }

    @GetMapping
    public List<WorkflowRunSummaryResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "50") int limit) {
        return service.listRecent(jwt.getSubject(), limit);
    }

    @GetMapping("/{id}")
    public WorkflowRunResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getSubject(), id);
    }

    @PostMapping("/{id}/cancel")
    public WorkflowRunResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.cancel(jwt.getSubject(), id);
    }

    @PostMapping("/{id}/retry")
    public WorkflowRunResponse retry(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.retry(jwt.getSubject(), id);
    }
}
