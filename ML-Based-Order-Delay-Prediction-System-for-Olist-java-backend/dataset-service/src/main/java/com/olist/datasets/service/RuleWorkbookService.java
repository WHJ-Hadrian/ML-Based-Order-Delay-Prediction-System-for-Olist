package com.olist.datasets.service;

import com.olist.datasets.dto.RuleRunCreateRequest;
import com.olist.datasets.dto.RuleRunClaimRequest;
import com.olist.datasets.dto.RuleRunFinishRequest;
import com.olist.datasets.dto.RuleRunLeaseRequest;
import com.olist.datasets.dto.RuleRunNodeRequest;
import com.olist.datasets.dto.RuleOutputUpload;
import com.olist.datasets.dto.RuleWorkbookCreateRequest;
import com.olist.datasets.dto.RuleWorkbookDraftRequest;
import com.olist.datasets.dto.RuleWorkbookQuery;
import com.olist.datasets.dto.RuleWorkbookUpdateRequest;
import com.olist.datasets.vo.PageVO;
import com.olist.datasets.vo.RuleRunExecutionVO;
import com.olist.datasets.vo.RuleRunClaimVO;
import com.olist.datasets.vo.RuleRunLeaseVO;
import com.olist.datasets.vo.RuleRunNodeVO;
import com.olist.datasets.vo.RuleRunVO;
import com.olist.datasets.vo.RuleValidationResultVO;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import com.olist.datasets.vo.RuleWorkbookDetailVO;
import com.olist.datasets.vo.RuleWorkbookVO;
import com.olist.datasets.vo.RuleWorkbookVersionVO;
import com.olist.datasets.vo.PublishedRuleVersionVO;
import java.util.List;
import java.util.UUID;

public interface RuleWorkbookService {

    PageVO<RuleWorkbookVO> list(RuleWorkbookQuery query);

    RuleWorkbookDetailVO get(UUID id);

    RuleWorkbookDetailVO create(RuleWorkbookCreateRequest request);

    RuleWorkbookDetailVO update(UUID id, RuleWorkbookUpdateRequest request);

    RuleWorkbookDetailVO saveDraft(UUID id, RuleWorkbookDraftRequest request);

    RuleValidationResultVO validate(UUID id);

    RuleWorkbookVersionVO publish(UUID id);

    List<RuleWorkbookVersionVO> versions(UUID id);

    List<PublishedRuleVersionVO> publishedVersions(String keyword, int limit);

    RuleWorkbookVersionVO getVersion(UUID id, int versionNumber);

    void delete(UUID id);

    RuleRunVO createRun(UUID id, RuleRunCreateRequest request);

    List<RuleRunVO> runs(UUID id, int limit);

    RuleRunVO getRun(UUID runId);

    RuleRunVO retryRun(UUID runId);

    RuleRunVO cancelRun(UUID runId);

    RuleRunExecutionVO getRunExecution(UUID runId);

    RuleRunVO startRun(UUID runId);

    RuleRunVO finishRun(UUID runId, RuleRunFinishRequest request);

    RuleRunNodeVO upsertRunNode(UUID runId, RuleRunNodeRequest request);

    List<RuleRunNodeVO> runNodes(UUID runId);

    RuleRunClaimVO claimRun(RuleRunClaimRequest request);

    RuleRunLeaseVO renewRunLease(
            UUID runId, UUID claimToken, int executionAttempt, RuleRunLeaseRequest request);

    RuleRunExecutionVO getClaimedRunExecution(
            UUID runId, UUID claimToken, int executionAttempt);

    ExecutionSnapshotVO getClaimedExecutionSnapshot(
            UUID runId, UUID claimToken, int executionAttempt);

    RuleRunVO startClaimedRun(UUID runId, UUID claimToken, int executionAttempt);

    RuleRunVO finishClaimedRun(
            UUID runId, UUID claimToken, int executionAttempt, RuleRunFinishRequest request);

    RuleRunVO finishClaimedRunWithOutputs(
            UUID runId,
            UUID claimToken,
            int executionAttempt,
            List<RuleOutputUpload> outputs);

    RuleRunNodeVO upsertClaimedRunNode(
            UUID runId, UUID claimToken, int executionAttempt, RuleRunNodeRequest request);
}
