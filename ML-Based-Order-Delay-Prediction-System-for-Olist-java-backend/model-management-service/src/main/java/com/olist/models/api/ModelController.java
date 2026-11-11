package com.olist.models.api;

import com.olist.models.service.ModelTrainingService;
import com.olist.models.service.ModelTrialService;
import com.olist.models.service.ModelWorkflowService;
import com.olist.models.service.TrainingRunService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final ModelTrainingService service;
    private final ModelTrialService trialService;
    private final ModelWorkflowService workflowService;
    private final TrainingRunService trainingRunService;

    public ModelController(
            ModelTrainingService service,
            ModelTrialService trialService,
            ModelWorkflowService workflowService,
            TrainingRunService trainingRunService) {
        this.service = service;
        this.trialService = trialService;
        this.workflowService = workflowService;
        this.trainingRunService = trainingRunService;
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
    public ResponseEntity<TrainingRunResponse> startTraining(
            @Valid @RequestBody ModelTrainingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {
        TrainingRunResponse run;
        try {
            run = trainingRunService.submit(
                    jwt.getSubject(), jwt.getTokenValue(), request, idempotencyKey);
        } catch (DataIntegrityViolationException conflict) {
            run = trainingRunService.replay(jwt.getSubject(), request, idempotencyKey);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, run.statusUrl())
                .body(run);
    }

    @GetMapping("/training-runs/{id}")
    public TrainingRunResponse trainingRun(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return trainingRunService.get(jwt.getSubject(), id);
    }

    @GetMapping("/training-runs")
    public TrainingRunPageResponse trainingRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return trainingRunService.list(jwt.getSubject(), page, size);
    }

    @PostMapping("/training-runs/{id}/cancel")
    public TrainingRunResponse cancelTrainingRun(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return trainingRunService.cancel(jwt.getSubject(), id);
    }

    @GetMapping("/versions")
    public List<ModelVersionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject());
    }

    @GetMapping("/versions/{id}")
    public ModelVersionResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getSubject(), id);
    }

    @PostMapping("/versions/{id}/approve")
    public ModelVersionResponse approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.approve(jwt.getSubject(), id);
    }

    @PostMapping("/versions/{id}/deprecate")
    public ModelVersionResponse deprecate(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.deprecate(jwt.getSubject(), id);
    }

    @GetMapping("/versions/{id}/manifest")
    public Map<String, Object> manifest(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.manifest(jwt.getSubject(), id);
    }

    @GetMapping("/versions/{id}/artifact")
    public ResponseEntity<StreamingResponseBody> artifact(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        String ownerId = jwt.getSubject();
        StreamingResponseBody body = output -> service.streamArtifact(ownerId, id, output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"model-" + id + ".zip\"")
                .body(body);
    }

    @PostMapping("/versions/{id}/trials")
    public ResponseEntity<ModelTrialResponse> trial(
            @PathVariable UUID id,
            @Valid @RequestBody ModelTrialRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ModelTrialResponse result = trialService.trial(jwt.getSubject(), jwt.getTokenValue(), id, request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/models/trials/{trialId}")
                .buildAndExpand(result.id())
                .toUri();
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
