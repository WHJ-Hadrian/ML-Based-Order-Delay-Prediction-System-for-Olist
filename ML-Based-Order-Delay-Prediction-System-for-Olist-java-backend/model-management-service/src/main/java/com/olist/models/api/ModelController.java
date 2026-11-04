package com.olist.models.api;

import com.olist.models.service.ModelTrainingService;
import com.olist.models.service.ModelTrialService;
import com.olist.models.api.ModelTrialCompareRequest;
import com.olist.models.api.ModelWorkflowDuplicateRequest;
import com.olist.models.api.ModelWorkflowRequest;
import com.olist.models.api.ModelWorkflowResponse;
import com.olist.models.service.ModelWorkflowService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final ModelTrainingService service;
    private final ModelTrialService trialService;
    private final ModelWorkflowService workflowService;

    public ModelController(ModelTrainingService service, ModelTrialService trialService,
            ModelWorkflowService workflowService) {
        this.service = service;
        this.trialService = trialService;
        this.workflowService = workflowService;
    }

    @GetMapping("/workflows")
    public List<ModelWorkflowSummaryResponse> workflows(@AuthenticationPrincipal Jwt jwt) {
        return workflowService.list(jwt.getSubject());
    }

    @PostMapping("/workflows")
    public ResponseEntity<ModelWorkflowResponse> createWorkflow(
            @Valid @RequestBody ModelWorkflowRequest request, @AuthenticationPrincipal Jwt jwt) {
        ModelWorkflowResponse workflow = workflowService.create(jwt.getSubject(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/models/workflows/{id}").buildAndExpand(workflow.id()).toUri();
        return ResponseEntity.created(location).body(workflow);
    }

    @GetMapping("/workflows/{id}")
    public ModelWorkflowResponse workflow(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return workflowService.get(jwt.getSubject(), id);
    }

    @PutMapping("/workflows/{id}")
    public ModelWorkflowResponse updateWorkflow(@PathVariable UUID id,
            @Valid @RequestBody ModelWorkflowRequest request, @AuthenticationPrincipal Jwt jwt) {
        return workflowService.update(jwt.getSubject(), id, request);
    }

    @PostMapping("/workflows/{id}/duplicate")
    public ResponseEntity<ModelWorkflowResponse> duplicateWorkflow(@PathVariable UUID id,
            @Valid @RequestBody ModelWorkflowDuplicateRequest request, @AuthenticationPrincipal Jwt jwt) {
        ModelWorkflowResponse workflow = workflowService.duplicate(jwt.getSubject(), id, request.name());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/models/workflows/{id}").buildAndExpand(workflow.id()).toUri();
        return ResponseEntity.created(location).body(workflow);
    }

    @PostMapping("/training-runs")
    public ResponseEntity<ModelVersionResponse> train(
            @Valid @RequestBody ModelTrainingRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ModelVersionResponse model = service.train(
                jwt.getSubject(), jwt.getTokenValue(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/models/versions/{id}")
                .buildAndExpand(model.id())
                .toUri();
        return ResponseEntity.created(location).body(model);
    }

    @GetMapping("/versions")
    public List<ModelVersionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject());
    }

    @GetMapping("/versions/{id}")
    public ModelVersionResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getSubject(), id);
    }

    @PostMapping("/versions/{id}/trials")
    public ResponseEntity<ModelTrialResponse> trial(
            @PathVariable UUID id,
            @Valid @RequestBody ModelTrialRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ModelTrialResponse result = trialService.trial(jwt.getSubject(), jwt.getTokenValue(), id, request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/models/trials/{trialId}")
                .buildAndExpand(result.id()).toUri();
        return ResponseEntity.created(location).body(result);
    }

    @PostMapping("/trials/compare")
    public ResponseEntity<List<ModelTrialResponse>> compareTrials(
            @Valid @RequestBody ModelTrialCompareRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        List<ModelTrialResponse> results = trialService.compare(jwt.getSubject(), jwt.getTokenValue(), request);
        return ResponseEntity.status(201).body(results);
    }

    @GetMapping("/versions/{id}/trials")
    public List<ModelTrialResponse> trials(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return trialService.list(jwt.getSubject(), id);
    }

    @GetMapping("/trials/{id}")
    public ModelTrialResponse trial(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return trialService.get(jwt.getSubject(), id);
    }

    @GetMapping("/comparisons/{id}")
    public List<ModelTrialResponse> comparison(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return trialService.getComparison(jwt.getSubject(), id);
    }
}
