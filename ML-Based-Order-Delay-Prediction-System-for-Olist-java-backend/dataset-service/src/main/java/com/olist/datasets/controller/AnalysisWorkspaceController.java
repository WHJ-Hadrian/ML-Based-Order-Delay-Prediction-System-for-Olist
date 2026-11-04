package com.olist.datasets.controller;

import com.olist.datasets.dto.AnalysisWorkspaceCopyRequest;
import com.olist.datasets.dto.AnalysisWorkspaceRequest;
import com.olist.datasets.service.AnalysisWorkspaceService;
import com.olist.datasets.vo.AnalysisWorkspaceVO;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/analysis-workspaces")
public class AnalysisWorkspaceController {

    private final AnalysisWorkspaceService service;

    public AnalysisWorkspaceController(AnalysisWorkspaceService service) {
        this.service = service;
    }

    @GetMapping
    public List<AnalysisWorkspaceVO> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<AnalysisWorkspaceVO> create(@Valid @RequestBody AnalysisWorkspaceRequest request) {
        AnalysisWorkspaceVO created = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public AnalysisWorkspaceVO get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<AnalysisWorkspaceVO> copy(
            @PathVariable UUID id,
            @Valid @RequestBody AnalysisWorkspaceCopyRequest request) {
        AnalysisWorkspaceVO copy = service.copy(id, request.name());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/analysis-workspaces/{id}")
                .buildAndExpand(copy.id()).toUri();
        return ResponseEntity.created(location).body(copy);
    }

    @PutMapping("/{id}")
    public AnalysisWorkspaceVO update(
            @PathVariable UUID id,
            @Valid @RequestBody AnalysisWorkspaceRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
