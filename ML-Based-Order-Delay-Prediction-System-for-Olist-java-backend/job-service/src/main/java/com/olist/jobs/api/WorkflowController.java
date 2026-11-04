package com.olist.jobs.api;

import com.olist.jobs.service.WorkflowDefinitionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {
    private final WorkflowDefinitionService service;

    public WorkflowController(WorkflowDefinitionService service) { this.service = service; }

    @GetMapping
    public List<WorkflowSummaryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject());
    }

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody WorkflowRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        WorkflowResponse response = service.create(jwt.getSubject(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/workflows/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public WorkflowResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getSubject(), id);
    }

    @PutMapping("/{id}")
    public WorkflowResponse update(@PathVariable UUID id, @Valid @RequestBody WorkflowRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.update(jwt.getSubject(), id, request);
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<WorkflowResponse> duplicate(@PathVariable UUID id,
            @Valid @RequestBody WorkflowDuplicateRequest request, @AuthenticationPrincipal Jwt jwt) {
        WorkflowResponse response = service.duplicate(jwt.getSubject(), id, request.name());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/workflows/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }
}
