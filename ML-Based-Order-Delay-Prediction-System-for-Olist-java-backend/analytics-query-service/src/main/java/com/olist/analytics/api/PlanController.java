package com.olist.analytics.api;

import com.olist.analytics.service.PlanService;
import com.olist.analytics.service.PlanService.PlanRequest;
import com.olist.analytics.service.PlanService.PlanResponse;
import com.olist.analytics.service.PlanService.RunResponse;
import com.olist.analytics.service.PlanService.TemplateResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 分析方案与运行记录。 */
@RestController
@RequestMapping("/api/v1/analytics")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping("/plans")
    public List<PlanResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject());
    }

    @PostMapping("/plans")
    public ResponseEntity<PlanResponse> create(
            @RequestBody PlanRequest request, @AuthenticationPrincipal Jwt jwt) {
        PlanResponse created = service.create(jwt.getSubject(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/plans/{id}")
    public PlanResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getSubject(), id);
    }

    @PutMapping("/plans/{id}")
    public PlanResponse update(
            @PathVariable UUID id, @RequestBody PlanRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.update(jwt.getSubject(), id, request);
    }

    @DeleteMapping("/plans/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/plans/{id}/run")
    public ResponseEntity<RunResponse> run(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.run(jwt.getSubject(), id, authorization));
    }

    @GetMapping("/plans/{id}/runs")
    public List<RunResponse> runs(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.runs(jwt.getSubject(), id);
    }

    @GetMapping("/runs")
    public List<RunResponse> allRuns(
            @RequestParam(required = false) UUID planId, @AuthenticationPrincipal Jwt jwt) {
        return service.runs(jwt.getSubject(), planId);
    }

    @GetMapping("/runs/{id}")
    public RunResponse run(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.getRun(jwt.getSubject(), id);
    }

    @GetMapping("/templates")
    public List<TemplateResponse> templates() {
        return service.templates();
    }

    @PostMapping("/templates/{key}/copy")
    public ResponseEntity<PlanResponse> copy(
            @PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        PlanResponse created = service.copyTemplate(jwt.getSubject(), key);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/analytics/plans/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }
}
