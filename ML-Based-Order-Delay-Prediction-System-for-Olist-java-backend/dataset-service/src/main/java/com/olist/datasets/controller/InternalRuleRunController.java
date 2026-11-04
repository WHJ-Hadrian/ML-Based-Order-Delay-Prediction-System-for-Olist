package com.olist.datasets.controller;

import com.olist.datasets.dto.RuleRunClaimRequest;
import com.olist.datasets.dto.RuleRunFinishRequest;
import com.olist.datasets.dto.RuleRunLeaseRequest;
import com.olist.datasets.dto.RuleRunNodeRequest;
import com.olist.datasets.dto.RuleOutputManifest;
import com.olist.datasets.dto.RuleOutputUpload;
import com.olist.datasets.security.RuleWorkerAuthenticator;
import com.olist.datasets.service.RuleWorkbookService;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import com.olist.datasets.vo.RuleRunClaimVO;
import com.olist.datasets.vo.RuleRunExecutionVO;
import com.olist.datasets.vo.RuleRunLeaseVO;
import com.olist.datasets.vo.RuleRunNodeVO;
import com.olist.datasets.vo.RuleRunVO;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/rule-runs")
public class InternalRuleRunController {

    private static final String WORKER_TOKEN_HEADER = "X-Rule-Worker-Token";
    private static final String CLAIM_TOKEN_HEADER = "X-Rule-Claim-Token";
    private static final String ATTEMPT_HEADER = "X-Rule-Execution-Attempt";

    private final RuleWorkbookService ruleWorkbookService;
    private final RuleWorkerAuthenticator authenticator;
    private final ObjectMapper objectMapper;

    public InternalRuleRunController(
            RuleWorkbookService ruleWorkbookService,
            RuleWorkerAuthenticator authenticator,
            ObjectMapper objectMapper) {
        this.ruleWorkbookService = ruleWorkbookService;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/claim")
    public RuleRunClaimVO claim(
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestBody(required = false) RuleRunClaimRequest request) {
        authenticator.requireValid(workerToken);
        return ruleWorkbookService.claimRun(request);
    }

    @PostMapping("/{runId}/lease")
    public RuleRunLeaseVO renewLease(
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestHeader(CLAIM_TOKEN_HEADER) UUID claimToken,
            @RequestHeader(ATTEMPT_HEADER) int executionAttempt,
            @PathVariable UUID runId,
            @RequestBody(required = false) RuleRunLeaseRequest request) {
        authenticator.requireValid(workerToken);
        return ruleWorkbookService.renewRunLease(
                runId, claimToken, executionAttempt, request);
    }

    @GetMapping("/{runId}/execution")
    public RuleRunExecutionVO execution(
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestHeader(CLAIM_TOKEN_HEADER) UUID claimToken,
            @RequestHeader(ATTEMPT_HEADER) int executionAttempt,
            @PathVariable UUID runId) {
        authenticator.requireValid(workerToken);
        return ruleWorkbookService.getClaimedRunExecution(
                runId, claimToken, executionAttempt);
    }

    @GetMapping("/{runId}/execution-snapshot")
    public ExecutionSnapshotVO executionSnapshot(
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestHeader(CLAIM_TOKEN_HEADER) UUID claimToken,
            @RequestHeader(ATTEMPT_HEADER) int executionAttempt,
            @PathVariable UUID runId) {
        authenticator.requireValid(workerToken);
        return ruleWorkbookService.getClaimedExecutionSnapshot(
                runId, claimToken, executionAttempt);
    }

    @PostMapping("/{runId}/start")
    public RuleRunVO start(
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestHeader(CLAIM_TOKEN_HEADER) UUID claimToken,
            @RequestHeader(ATTEMPT_HEADER) int executionAttempt,
            @PathVariable UUID runId) {
        authenticator.requireValid(workerToken);
        return ruleWorkbookService.startClaimedRun(runId, claimToken, executionAttempt);
    }

    @PostMapping("/{runId}/finish")
    public RuleRunVO finish(
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestHeader(CLAIM_TOKEN_HEADER) UUID claimToken,
            @RequestHeader(ATTEMPT_HEADER) int executionAttempt,
            @PathVariable UUID runId,
            @RequestBody(required = false) RuleRunFinishRequest request) {
        authenticator.requireValid(workerToken);
        return ruleWorkbookService.finishClaimedRun(
                runId, claimToken, executionAttempt, request);
    }

    @PostMapping(value = "/{runId}/finish-with-outputs", consumes = "multipart/form-data")
    public RuleRunVO finishWithOutputs(
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestHeader(CLAIM_TOKEN_HEADER) UUID claimToken,
            @RequestHeader(ATTEMPT_HEADER) int executionAttempt,
            @PathVariable UUID runId,
            @RequestPart("manifest") String manifestJson,
            MultipartHttpServletRequest multipartRequest) {
        authenticator.requireValid(workerToken);
        try {
            RuleOutputManifest manifest = objectMapper.readValue(manifestJson, RuleOutputManifest.class);
            if (manifest.outputs() == null || manifest.outputs().isEmpty()
                    || manifest.outputs().size() > 20) {
                throw new IllegalArgumentException("规则输出数量必须在 1 到 20 之间");
            }
            List<RuleOutputUpload> uploads = new ArrayList<>();
            for (RuleOutputManifest.Output output : manifest.outputs()) {
                MultipartFile file = output.filePart() == null
                        ? null : multipartRequest.getFile(output.filePart());
                uploads.add(new RuleOutputUpload(
                        output.nodeId(), output.name(), output.sha256(), output.sizeBytes(),
                        output.rowCount(), output.columnCount(), file));
            }
            return ruleWorkbookService.finishClaimedRunWithOutputs(
                    runId, claimToken, executionAttempt, uploads);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalArgumentException("规则输出清单无法解析", exception);
        }
    }

    @PutMapping("/{runId}/nodes/{nodeId}")
    public RuleRunNodeVO upsertNode(
            @RequestHeader(value = WORKER_TOKEN_HEADER, required = false) String workerToken,
            @RequestHeader(CLAIM_TOKEN_HEADER) UUID claimToken,
            @RequestHeader(ATTEMPT_HEADER) int executionAttempt,
            @PathVariable UUID runId,
            @PathVariable String nodeId,
            @RequestBody RuleRunNodeRequest request) {
        authenticator.requireValid(workerToken);
        RuleRunNodeRequest normalized = new RuleRunNodeRequest(
                nodeId,
                request == null ? null : request.nodeKind(),
                request == null ? null : request.status(),
                request == null ? null : request.metrics(),
                request == null ? null : request.sampleUri(),
                request == null ? null : request.errorMessage());
        return ruleWorkbookService.upsertClaimedRunNode(
                runId, claimToken, executionAttempt, normalized);
    }
}
