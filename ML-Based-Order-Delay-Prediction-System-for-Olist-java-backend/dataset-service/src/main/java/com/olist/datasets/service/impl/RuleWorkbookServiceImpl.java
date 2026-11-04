package com.olist.datasets.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.olist.datasets.entity.DatasetVersion;
import com.olist.datasets.entity.RuleRun;
import com.olist.datasets.entity.RuleRunNode;
import com.olist.datasets.entity.RuleWorkbook;
import com.olist.datasets.entity.RuleWorkbookDraft;
import com.olist.datasets.entity.RuleWorkbookVersion;
import com.olist.datasets.enums.DatasetStatus;
import com.olist.datasets.enums.RuleRunMode;
import com.olist.datasets.enums.RuleRunNodeStatus;
import com.olist.datasets.enums.RuleRunStatus;
import com.olist.datasets.enums.RuleWorkbookStatus;
import com.olist.datasets.exception.DatasetNotFoundException;
import com.olist.datasets.exception.DatasetNotReadyException;
import com.olist.datasets.exception.InvalidLibraryRequestException;
import com.olist.datasets.exception.RulePublishRejectedException;
import com.olist.datasets.exception.RuleRunConflictException;
import com.olist.datasets.exception.RuleRunNotFoundException;
import com.olist.datasets.exception.RuleWorkbookConflictException;
import com.olist.datasets.exception.RuleWorkbookNotFoundException;
import com.olist.datasets.mapper.DatasetVersionMapper;
import com.olist.datasets.mapper.RuleRunMapper;
import com.olist.datasets.mapper.RuleRunNodeMapper;
import com.olist.datasets.mapper.RuleWorkbookDraftMapper;
import com.olist.datasets.mapper.RuleWorkbookMapper;
import com.olist.datasets.mapper.RuleWorkbookVersionMapper;
import com.olist.datasets.rule.RuleCompilation;
import com.olist.datasets.rule.RuleWorkbookCompiler;
import com.olist.datasets.rule.RuleWorkbookDocumentFactory;
import com.olist.datasets.security.DatasetOwnerProvider;
import com.olist.datasets.service.RuleWorkbookService;
import com.olist.datasets.service.DatasetService;
import com.olist.datasets.vo.ExecutionSnapshotVO;
import com.olist.datasets.vo.PageVO;
import com.olist.datasets.vo.RuleRunExecutionVO;
import com.olist.datasets.vo.RuleRunClaimVO;
import com.olist.datasets.vo.RuleRunLeaseVO;
import com.olist.datasets.vo.RuleRunNodeVO;
import com.olist.datasets.vo.RuleRunVO;
import com.olist.datasets.vo.RuleValidationIssueVO;
import com.olist.datasets.vo.RuleValidationResultVO;
import com.olist.datasets.vo.RuleWorkbookDetailVO;
import com.olist.datasets.vo.RuleWorkbookVO;
import com.olist.datasets.vo.RuleWorkbookVersionVO;
import com.olist.datasets.vo.PublishedRuleVersionVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleWorkbookServiceImpl implements RuleWorkbookService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 1000;
    private static final int DEFAULT_PREVIEW_ROWS = 1000;
    private static final int MAX_PREVIEW_ROWS = 5000;
    private static final int DEFAULT_LEASE_SECONDS = 60;
    private static final int MIN_LEASE_SECONDS = 15;
    private static final int MAX_LEASE_SECONDS = 300;
    private static final int MAX_CLAIM_RACE_RETRIES = 10;

    private final RuleWorkbookMapper ruleWorkbookMapper;
    private final RuleWorkbookDraftMapper ruleWorkbookDraftMapper;
    private final RuleWorkbookVersionMapper ruleWorkbookVersionMapper;
    private final RuleRunMapper ruleRunMapper;
    private final RuleRunNodeMapper ruleRunNodeMapper;
    private final DatasetVersionMapper datasetVersionMapper;
    private final DatasetService datasetService;
    private final RuleWorkbookCompiler compiler;
    private final RuleWorkbookDocumentFactory documentFactory;
    private final ObjectMapper objectMapper;
    private final DatasetOwnerProvider datasetOwnerProvider;

    public RuleWorkbookServiceImpl(
            RuleWorkbookMapper ruleWorkbookMapper,
            RuleWorkbookDraftMapper ruleWorkbookDraftMapper,
            RuleWorkbookVersionMapper ruleWorkbookVersionMapper,
            RuleRunMapper ruleRunMapper,
            RuleRunNodeMapper ruleRunNodeMapper,
            DatasetVersionMapper datasetVersionMapper,
            DatasetService datasetService,
            RuleWorkbookCompiler compiler,
            RuleWorkbookDocumentFactory documentFactory,
            ObjectMapper objectMapper,
            DatasetOwnerProvider datasetOwnerProvider) {
        this.ruleWorkbookMapper = ruleWorkbookMapper;
        this.ruleWorkbookDraftMapper = ruleWorkbookDraftMapper;
        this.ruleWorkbookVersionMapper = ruleWorkbookVersionMapper;
        this.ruleRunMapper = ruleRunMapper;
        this.ruleRunNodeMapper = ruleRunNodeMapper;
        this.datasetVersionMapper = datasetVersionMapper;
        this.datasetService = datasetService;
        this.compiler = compiler;
        this.documentFactory = documentFactory;
        this.objectMapper = objectMapper;
        this.datasetOwnerProvider = datasetOwnerProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public PageVO<RuleWorkbookVO> list(RuleWorkbookQuery query) {
        RuleWorkbookQuery effective = query == null ? new RuleWorkbookQuery() : query;
        int page = Math.max(effective.getPage(), 0);
        int size = Math.min(Math.max(effective.getSize(), MIN_PAGE_SIZE), MAX_PAGE_SIZE);
        String ownerId = datasetOwnerProvider.currentOwnerId();
        String keywordPattern = keywordPattern(effective.getKeyword());
        List<RuleWorkbookVO> items = ruleWorkbookMapper
                .findPage(effective.getStatus(), keywordPattern, ownerId, (long) page * size, size)
                .stream()
                .map(this::toVO)
                .toList();
        long total = ruleWorkbookMapper.count(effective.getStatus(), keywordPattern, ownerId);
        return PageVO.of(items, page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public RuleWorkbookDetailVO get(UUID id) {
        RuleWorkbook workbook = requireWorkbook(id);
        RuleWorkbookDraft draft = ruleWorkbookDraftMapper.findByWorkbookId(id);
        return toDetail(workbook, draft);
    }

    @Override
    @Transactional
    public RuleWorkbookDetailVO create(RuleWorkbookCreateRequest request) {
        if (request == null || request.getName() == null) {
            throw new InvalidLibraryRequestException("规则库名称不能为空");
        }
        String ownerId = datasetOwnerProvider.currentOwnerId();
        String name = normalizeName(request.getName());
        if (ruleWorkbookMapper.countByName(name, ownerId, null) > 0) {
            throw new RuleWorkbookConflictException("规则库名称「" + name + "」已存在");
        }
        UUID datasetVersionId = request.getDatasetVersionId();
        if (datasetVersionId == null) {
            throw new InvalidLibraryRequestException("数据集版本不能为空");
        }
        if (datasetVersionMapper.findById(datasetVersionId, ownerId) == null) {
            throw new DatasetNotFoundException(datasetVersionId);
        }

        Instant now = Instant.now();
        RuleWorkbook workbook = new RuleWorkbook();
        workbook.setId(UUID.randomUUID());
        workbook.setOwnerId(ownerId);
        workbook.setName(name);
        workbook.setDescription(normalizeDescription(request.getDescription()));
        workbook.setDatasetVersionId(datasetVersionId);
        workbook.setStatus(RuleWorkbookStatus.DRAFT);
        workbook.setCreatedAt(now);
        workbook.setUpdatedAt(now);
        ruleWorkbookMapper.insert(workbook);

        ObjectNode document = documentFactory.normalize(
                request.getDocument(), workbook.getId(), name, datasetVersionId);
        RuleWorkbookDraft draft = new RuleWorkbookDraft();
        draft.setWorkbookId(workbook.getId());
        draft.setRevision(1);
        draft.setDocumentJson(writeJson(document));
        draft.setUpdatedAt(now);
        ruleWorkbookDraftMapper.insert(draft);
        return toDetail(workbook, draft);
    }

    @Override
    @Transactional
    public RuleWorkbookDetailVO update(UUID id, RuleWorkbookUpdateRequest request) {
        if (request == null || request.getName() == null) {
            throw new InvalidLibraryRequestException("规则库名称不能为空");
        }
        RuleWorkbook workbook = requireWorkbook(id);
        String ownerId = datasetOwnerProvider.currentOwnerId();
        String name = normalizeName(request.getName());
        if (ruleWorkbookMapper.countByName(name, ownerId, id) > 0) {
            throw new RuleWorkbookConflictException("规则库名称「" + name + "」已存在");
        }
        String description = normalizeDescription(request.getDescription());
        Instant now = Instant.now();
        if (ruleWorkbookMapper.updateNameAndDescription(
                id, ownerId, name, description, now) != 1) {
            throw new RuleWorkbookNotFoundException(id.toString());
        }
        workbook.setName(name);
        workbook.setDescription(description);
        workbook.setUpdatedAt(now);

        RuleWorkbookDraft draft = ruleWorkbookDraftMapper.findByWorkbookId(id);
        if (draft != null) {
            ObjectNode document = documentFactory.normalize(
                    parseJson(draft.getDocumentJson()),
                    id,
                    name,
                    workbook.getDatasetVersionId());
            RuleWorkbookDraft renamed = new RuleWorkbookDraft();
            renamed.setWorkbookId(id);
            renamed.setRevision(draft.getRevision());
            renamed.setDocumentJson(writeJson(document));
            renamed.setUpdatedAt(now);
            if (ruleWorkbookDraftMapper.update(renamed, draft.getRevision()) == 1) {
                draft = renamed;
            }
        }
        return toDetail(workbook, draft);
    }

    @Override
    @Transactional
    public RuleWorkbookDetailVO saveDraft(UUID id, RuleWorkbookDraftRequest request) {
        if (request == null || request.getDocument() == null) {
            throw new InvalidLibraryRequestException("规则文档不能为空");
        }
        RuleWorkbook workbook = requireWorkbook(id);
        RuleWorkbookDraft current = requireDraft(id);
        if (request.getExpectedRevision() != null
                && request.getExpectedRevision() != current.getRevision()) {
            throw new RuleWorkbookConflictException("规则草稿已被其他操作更新，请刷新后重试");
        }
        JsonNode input = request.getDocument();
        if (input.path("datasetVersionId").isTextual()
                && !workbook.getDatasetVersionId().toString()
                        .equals(input.path("datasetVersionId").asText())) {
            throw new InvalidLibraryRequestException("规则文档不能绑定到与工作簿不同的数据集");
        }
        ObjectNode normalized = documentFactory.normalize(
                input, workbook.getId(), workbook.getName(), workbook.getDatasetVersionId());
        Instant now = Instant.now();
        RuleWorkbookDraft draft = new RuleWorkbookDraft();
        draft.setWorkbookId(workbook.getId());
        draft.setRevision(current.getRevision() + 1);
        draft.setDocumentJson(writeJson(normalized));
        draft.setUpdatedAt(now);
        if (ruleWorkbookDraftMapper.update(draft, current.getRevision()) != 1) {
            throw new RuleWorkbookConflictException("规则草稿已被其他操作更新，请刷新后重试");
        }
        ruleWorkbookMapper.updateStatus(
                workbook.getId(), workbook.getOwnerId(), RuleWorkbookStatus.DRAFT, now);
        workbook.setStatus(RuleWorkbookStatus.DRAFT);
        workbook.setUpdatedAt(now);
        return toDetail(workbook, draft);
    }
    @Override
    @Transactional
    public RuleValidationResultVO validate(UUID id) {
        RuleWorkbook workbook = requireWorkbook(id);
        RuleWorkbookDraft draft = requireDraft(id);
        RuleCompilation compilation = compileDraft(workbook, draft);
        RuleWorkbookStatus nextStatus = compilation.valid()
                ? RuleWorkbookStatus.VALIDATED
                : RuleWorkbookStatus.DRAFT;
        if (workbook.getStatus() != nextStatus) {
            ruleWorkbookMapper.updateStatus(
                    workbook.getId(), workbook.getOwnerId(), nextStatus, Instant.now());
        }
        return new RuleValidationResultVO(
                compilation.valid(),
                compilation.issues(),
                compilation.nodeCount(),
                compilation.plan());
    }

    @Override
    @Transactional
    public RuleWorkbookVersionVO publish(UUID id) {
        RuleWorkbook workbook = requireWorkbook(id);
        RuleWorkbookDraft draft = requireDraft(id);
        requireReadyDatasetVersion(workbook.getDatasetVersionId(), workbook.getOwnerId());
        RuleCompilation compilation = compileDraft(workbook, draft);
        if (!compilation.valid()) {
            throw new RulePublishRejectedException(issueSummary(compilation.issues()));
        }
        Instant now = Instant.now();
        int versionNumber = ruleWorkbookVersionMapper.findMaxVersionNumber(workbook.getId()) + 1;
        RuleWorkbookVersion version = new RuleWorkbookVersion();
        version.setId(UUID.randomUUID());
        version.setWorkbookId(workbook.getId());
        version.setVersionNumber(versionNumber);
        version.setDatasetVersionId(workbook.getDatasetVersionId());
        version.setStatus("PUBLISHED");
        version.setDocumentJson(draft.getDocumentJson());
        version.setCompiledPlanJson(writeJson(compilation.plan()));
        version.setContentHash(sha256(draft.getDocumentJson()));
        version.setCreatedAt(now);
        ruleWorkbookVersionMapper.insert(version);
        ruleWorkbookMapper.updateStatus(
                workbook.getId(), workbook.getOwnerId(), RuleWorkbookStatus.PUBLISHED, now);
        return toVersionVO(version);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleWorkbookVersionVO> versions(UUID id) {
        requireWorkbook(id);
        return ruleWorkbookVersionMapper
                .findByWorkbookId(id, datasetOwnerProvider.currentOwnerId())
                .stream()
                .map(this::toVersionVO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PublishedRuleVersionVO> publishedVersions(String keyword, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        String keywordPattern = keywordPattern(keyword);
        return ruleWorkbookVersionMapper.findPublishedCatalog(
                datasetOwnerProvider.currentOwnerId(), keywordPattern, safeLimit);
    }

    @Override
    @Transactional(readOnly = true)
    public RuleWorkbookVersionVO getVersion(UUID id, int versionNumber) {
        requireWorkbook(id);
        RuleWorkbookVersion version = ruleWorkbookVersionMapper.findByNumberAndOwner(
                id, versionNumber, datasetOwnerProvider.currentOwnerId());
        if (version == null) {
            throw new RuleWorkbookNotFoundException("规则工作簿版本 " + versionNumber + " 不存在");
        }
        return toVersionVO(version);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        requireWorkbook(id);
        if (ruleWorkbookMapper.deleteById(id, datasetOwnerProvider.currentOwnerId()) != 1) {
            throw new RuleWorkbookNotFoundException(id.toString());
        }
    }

    @Override
    @Transactional
    public RuleRunVO createRun(UUID id, RuleRunCreateRequest request) {
        RuleWorkbook workbook = requireWorkbook(id);
        String ownerId = datasetOwnerProvider.currentOwnerId();
        RuleWorkbookVersion version;
        if (request != null && request.getWorkbookVersionId() != null) {
            version = ruleWorkbookVersionMapper.findByIdAndOwner(request.getWorkbookVersionId(), ownerId);
        } else {
            version = ruleWorkbookVersionMapper.findLatestByWorkbook(id, ownerId);
        }
        if (version == null) {
            throw new RuleWorkbookConflictException("规则工作簿还没有可运行的已发布版本");
        }
        if (!workbook.getId().equals(version.getWorkbookId())) {
            throw new InvalidLibraryRequestException("所选版本不属于当前规则工作簿");
        }
        if (!"PUBLISHED".equalsIgnoreCase(version.getStatus())) {
            throw new RuleWorkbookConflictException("规则版本状态为「" + version.getStatus() + "」，不能新建运行");
        }
        requireReadyDatasetVersion(version.getDatasetVersionId(), ownerId);

        RuleRunMode mode = parseMode(request == null ? null : request.getMode());
        JsonNode compiledPlan = parseJson(version.getCompiledPlanJson());
        validateRunPlan(compiledPlan, mode);
        Integer sampleSize = request == null ? null : request.getSampleSize();
        if (mode == RuleRunMode.PREVIEW && sampleSize == null) {
            sampleSize = DEFAULT_PREVIEW_ROWS;
        }
        if (mode == RuleRunMode.PREVIEW
                && (sampleSize < 1 || sampleSize > MAX_PREVIEW_ROWS)) {
            throw new InvalidLibraryRequestException(
                    "预览行数必须在 1 到 " + MAX_PREVIEW_ROWS + " 之间");
        }
        Long seed = request == null ? null : request.getSeed();
        if (mode == RuleRunMode.PREVIEW && seed == null) {
            seed = 42L;
        }
        if (mode == RuleRunMode.PREVIEW && seed < 0) {
            throw new InvalidLibraryRequestException("随机种子不能小于 0");
        }
        Instant now = Instant.now();
        RuleRun run = new RuleRun();
        run.setId(UUID.randomUUID());
        run.setOwnerId(ownerId);
        run.setWorkbookId(workbook.getId());
        run.setWorkbookVersionId(version.getId());
        run.setDatasetVersionId(version.getDatasetVersionId());
        run.setMode(mode);
        run.setStatus(RuleRunStatus.QUEUED);
        run.setSampleSize(sampleSize);
        run.setSeed(seed);
        run.setCreatedAt(now);
        ruleRunMapper.insert(run);
        return toRunVO(run);
    }

    private void validateRunPlan(JsonNode plan, RuleRunMode mode) {
        int enabledOutputs = 0;
        for (JsonNode node : plan.path("nodes")) {
            if (!node.path("enabled").asBoolean(true)) {
                continue;
            }
            String kind = node.path("kind").asText("").toUpperCase(Locale.ROOT);
            if ("OUTPUT".equals(kind)) {
                enabledOutputs++;
                String outputMode = node.path("config").path("mode").asText("");
                String format = node.path("config").path("format").asText("");
                if (!"MATERIALIZE_VERSION".equalsIgnoreCase(outputMode)
                        || !"CSV".equalsIgnoreCase(format)) {
                    throw new InvalidLibraryRequestException(
                            "当前 FULL Worker 仅支持 OUTPUT: MATERIALIZE_VERSION + CSV");
                }
            } else if (!Set.of(
                    "CHECK", "TRANSFORM", "FILTER", "PROJECT", "DEDUPE", "JOIN",
                    "AGGREGATE", "DERIVE").contains(kind)) {
                throw new InvalidLibraryRequestException(
                        "当前 Worker 不支持运行节点 " + kind);
            }
        }
        if (mode == RuleRunMode.FULL && (enabledOutputs == 0 || enabledOutputs > 20)) {
            throw new InvalidLibraryRequestException(
                    "FULL 运行必须包含 1 到 20 个启用的 CSV 输出节点");
        }
        if (mode == RuleRunMode.PREVIEW && enabledOutputs > 0) {
            throw new InvalidLibraryRequestException(
                    "PREVIEW 不能包含启用的 OUTPUT 节点，请使用 FULL");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleRunVO> runs(UUID id, int limit) {
        requireWorkbook(id);
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        return ruleRunMapper
                .findByWorkbookId(id, datasetOwnerProvider.currentOwnerId(), 0, effectiveLimit)
                .stream()
                .map(this::toRunVO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RuleRunVO getRun(UUID runId) {
        return toRunVO(requireRun(runId));
    }

    @Override
    @Transactional
    public RuleRunVO retryRun(UUID runId) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        RuleRun source = ruleRunMapper.findByIdAndOwnerForUpdate(runId, ownerId);
        if (source == null) {
            throw new RuleRunNotFoundException("规则运行 " + runId + " 不存在");
        }
        if (source.getMode() != RuleRunMode.PREVIEW) {
            throw new RuleRunConflictException("只有 PREVIEW 运行支持安全重试；FULL 输出需要幂等物化后才能重试");
        }
        if (source.getStatus() != RuleRunStatus.FAILED
                && source.getStatus() != RuleRunStatus.CANCELLED) {
            throw new RuleRunConflictException("只有失败或已取消的 PREVIEW 运行可以重试");
        }
        requireReadyDatasetVersion(source.getDatasetVersionId(), ownerId);

        Instant now = Instant.now();
        RuleRun retry = new RuleRun();
        retry.setId(UUID.randomUUID());
        retry.setOwnerId(ownerId);
        retry.setWorkbookId(source.getWorkbookId());
        retry.setWorkbookVersionId(source.getWorkbookVersionId());
        retry.setDatasetVersionId(source.getDatasetVersionId());
        retry.setMode(source.getMode());
        retry.setStatus(RuleRunStatus.QUEUED);
        retry.setSampleSize(source.getSampleSize());
        retry.setSeed(source.getSeed());
        retry.setRetryOfRunId(source.getId());
        retry.setCreatedAt(now);
        ruleRunMapper.insert(retry);
        return toRunVO(retry);
    }

    @Override
    @Transactional
    public RuleRunVO cancelRun(UUID runId) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        RuleRun run = ruleRunMapper.findByIdAndOwnerForUpdate(runId, ownerId);
        if (run == null) {
            throw new RuleRunNotFoundException("规则运行 " + runId + " 不存在");
        }
        if (run.getStatus() == RuleRunStatus.CANCELLED) {
            return toRunVO(run);
        }
        if (run.getStatus() != RuleRunStatus.QUEUED && run.getStatus() != RuleRunStatus.RUNNING) {
            throw new RuleRunConflictException(
                    "规则运行状态为「" + run.getStatus() + "」，不能取消");
        }
        Instant now = Instant.now();
        if (ruleRunMapper.cancelIfActive(runId, ownerId, now) != 1) {
            RuleRun current = requireRun(runId);
            if (current.getStatus() == RuleRunStatus.CANCELLED) {
                return toRunVO(current);
            }
            throw new RuleRunConflictException(
                    "规则运行状态已变为「" + current.getStatus() + "」，不能取消");
        }
        ruleRunNodeMapper.cancelActiveByRunId(runId, now);
        return toRunVO(requireRun(runId));
    }

    @Override
    @Transactional(readOnly = true)
    public RuleRunExecutionVO getRunExecution(UUID runId) {
        RuleRun run = requireRun(runId);
        return toRunExecution(run, run.getOwnerId());
    }

    private RuleRunExecutionVO toRunExecution(RuleRun run, String ownerId) {
        RuleWorkbookVersion version = requireRunVersion(run, ownerId);
        JsonNode plan = parseJson(version.getCompiledPlanJson());
        String planDatasetVersionId = plan.path("datasetVersionId").asText(null);
        if (planDatasetVersionId != null
                && !planDatasetVersionId.equals(run.getDatasetVersionId().toString())) {
            throw new RuleRunConflictException("运行冻结的数据集版本与已发布计划不一致");
        }
        return new RuleRunExecutionVO(
                run.getId(),
                run.getWorkbookId(),
                run.getWorkbookVersionId(),
                run.getDatasetVersionId(),
                version.getContentHash(),
                run.getMode(),
                run.getStatus(),
                run.getSampleSize(),
                run.getSeed(),
                plan);
    }

    @Override
    @Transactional
    public RuleRunVO startRun(UUID runId) {
        String ownerId = datasetOwnerProvider.currentOwnerId();
        RuleRun run = ruleRunMapper.findByIdAndOwnerForUpdate(runId, ownerId);
        if (run == null) {
            throw new RuleRunNotFoundException("规则运行 " + runId + " 不存在");
        }
        ensureNotClaimed(run);
        if (run.getMode() == RuleRunMode.FULL) {
            throw new RuleRunConflictException("FULL 运行必须由队列 Worker 领取后启动");
        }
        if (run.getStatus() == RuleRunStatus.RUNNING) {
            return toRunVO(run);
        }
        if (run.getStatus() != RuleRunStatus.QUEUED) {
            throw new RuleRunConflictException(
                    "规则运行状态为「" + run.getStatus() + "」，不能开始执行");
        }
        Instant now = Instant.now();
        int updated = ruleRunMapper.updateStatusIfCurrent(
                runId,
                ownerId,
                RuleRunStatus.QUEUED,
                RuleRunStatus.RUNNING,
                now,
                null,
                null);
        if (updated != 1) {
            RuleRun current = requireRun(runId);
            if (current.getStatus() == RuleRunStatus.RUNNING) {
                return toRunVO(current);
            }
            throw new RuleRunConflictException(
                    "规则运行状态已变为「" + current.getStatus() + "」，不能开始执行");
        }
        return toRunVO(requireRun(runId));
    }

    @Override
    @Transactional
    public RuleRunVO finishRun(UUID runId, RuleRunFinishRequest request) {
        RuleRunStatus target = parseFinishStatus(request);
        String ownerId = datasetOwnerProvider.currentOwnerId();
        RuleRun run = ruleRunMapper.findByIdAndOwnerForUpdate(runId, ownerId);
        if (run == null) {
            throw new RuleRunNotFoundException("规则运行 " + runId + " 不存在");
        }
        ensureNotClaimed(run);
        if (run.getStatus() == target) {
            return toRunVO(run);
        }
        if (run.getStatus() != RuleRunStatus.RUNNING) {
            throw new RuleRunConflictException(
                    "规则运行状态为「" + run.getStatus() + "」，不能结束执行");
        }
        if (target == RuleRunStatus.SUCCEEDED) {
            requireSuccessfulNodes(run);
        }
        String errorMessage = normalizeErrorMessage(request == null ? null : request.errorMessage());
        if (target == RuleRunStatus.SUCCEEDED) {
            errorMessage = null;
        }
        Instant now = Instant.now();
        int updated = ruleRunMapper.updateStatusIfCurrent(
                runId,
                ownerId,
                RuleRunStatus.RUNNING,
                target,
                null,
                now,
                errorMessage);
        if (updated != 1) {
            RuleRun current = requireRun(runId);
            if (current.getStatus() == target) {
                return toRunVO(current);
            }
            throw new RuleRunConflictException(
                    "规则运行状态已变为「" + current.getStatus() + "」，不能结束执行");
        }
        return toRunVO(requireRun(runId));
    }

    @Override
    @Transactional
    public RuleRunNodeVO upsertRunNode(UUID runId, RuleRunNodeRequest request) {
        if (request == null) {
            throw new InvalidLibraryRequestException("节点上报请求不能为空");
        }
        String ownerId = datasetOwnerProvider.currentOwnerId();
        RuleRun run = ruleRunMapper.findByIdAndOwnerForUpdate(runId, ownerId);
        if (run == null) {
            throw new RuleRunNotFoundException("规则运行 " + runId + " 不存在");
        }
        ensureNotClaimed(run);
        return upsertRunNodeForRun(run, request, ownerId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleRunNodeVO> runNodes(UUID runId) {
        requireRun(runId);
        return ruleRunNodeMapper.findByRunId(runId).stream()
                .map(this::toNodeVO)
                .toList();
    }

    @Override
    @Transactional
    public RuleRunClaimVO claimRun(RuleRunClaimRequest request) {
        String workerId = request == null || request.workerId() == null
                ? "" : request.workerId().trim();
        if (workerId.isEmpty() || workerId.length() > 128) {
            throw new InvalidLibraryRequestException("Worker ID 必须为 1 到 128 个字符");
        }
        int leaseSeconds = normalizeLeaseSeconds(
                request == null ? null : request.leaseSeconds());
        String mode = normalizeClaimMode(request == null ? null : request.mode());
        Instant now = Instant.now();
        ruleRunMapper.failExpiredFullRuns(now);
        for (int attempt = 0; attempt < MAX_CLAIM_RACE_RETRIES; attempt++) {
            RuleRun candidate = ruleRunMapper.findClaimCandidate(mode, now);
            if (candidate == null) {
                return RuleRunClaimVO.empty();
            }
            UUID claimToken = UUID.randomUUID();
            Instant expiresAt = now.plusSeconds(leaseSeconds);
            RuleRunStatus expectedStatus = candidate.getStatus();
            if (ruleRunMapper.claimIfAvailable(
                    candidate.getId(), expectedStatus, candidate.getExecutionAttempt(),
                    claimToken, workerId, expiresAt, now) != 1) {
                continue;
            }
            if (expectedStatus == RuleRunStatus.RUNNING) {
                ruleRunNodeMapper.deleteByRunId(candidate.getId());
            }
            return new RuleRunClaimVO(
                    true,
                    candidate.getId(),
                    candidate.getOwnerId(),
                    claimToken,
                    candidate.getExecutionAttempt() + 1,
                    expiresAt);
        }
        return RuleRunClaimVO.empty();
    }

    @Override
    @Transactional
    public RuleRunLeaseVO renewRunLease(
            UUID runId, UUID claimToken, int executionAttempt, RuleRunLeaseRequest request) {
        Instant now = Instant.now();
        RuleRun run = requireClaimedRunForUpdate(runId, claimToken, executionAttempt, now);
        int leaseSeconds = normalizeLeaseSeconds(request == null ? null : request.leaseSeconds());
        Instant expiresAt = now.plusSeconds(leaseSeconds);
        if (ruleRunMapper.renewLease(runId, claimToken, executionAttempt, expiresAt, now) != 1) {
            throw lostClaim(runId);
        }
        return new RuleRunLeaseVO(run.getId(), executionAttempt, expiresAt);
    }

    @Override
    @Transactional(readOnly = true)
    public RuleRunExecutionVO getClaimedRunExecution(
            UUID runId, UUID claimToken, int executionAttempt) {
        RuleRun run = requireClaimedRun(runId, claimToken, executionAttempt, Instant.now());
        return toRunExecution(run, run.getOwnerId());
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionSnapshotVO getClaimedExecutionSnapshot(
            UUID runId, UUID claimToken, int executionAttempt) {
        RuleRun run = requireClaimedRun(runId, claimToken, executionAttempt, Instant.now());
        if (run.getStatus() != RuleRunStatus.QUEUED && run.getStatus() != RuleRunStatus.RUNNING) {
            throw lostClaim(runId);
        }
        return datasetService.executionSnapshot(run.getDatasetVersionId(), run.getOwnerId());
    }

    @Override
    @Transactional
    public RuleRunVO startClaimedRun(UUID runId, UUID claimToken, int executionAttempt) {
        Instant now = Instant.now();
        RuleRun run = requireClaimedRunForUpdate(runId, claimToken, executionAttempt, now);
        if (run.getStatus() == RuleRunStatus.RUNNING) {
            return toRunVO(run);
        }
        if (run.getStatus() != RuleRunStatus.QUEUED) {
            throw new RuleRunConflictException(
                    "规则运行状态为「" + run.getStatus() + "」，不能开始执行");
        }
        if (ruleRunMapper.updateClaimedStatus(
                runId, claimToken, executionAttempt, RuleRunStatus.QUEUED,
                RuleRunStatus.RUNNING, now, null, null, now) != 1) {
            throw lostClaim(runId);
        }
        return toRunVO(ruleRunMapper.findByIdAndClaim(
                runId, claimToken, executionAttempt, Instant.now()));
    }

    @Override
    @Transactional
    public RuleRunVO finishClaimedRun(
            UUID runId, UUID claimToken, int executionAttempt, RuleRunFinishRequest request) {
        RuleRunStatus target = parseFinishStatus(request);
        Instant now = Instant.now();
        RuleRun run = requireClaimedRunForUpdate(runId, claimToken, executionAttempt, now);
        if (run.getMode() == RuleRunMode.FULL && target == RuleRunStatus.SUCCEEDED) {
            throw new RuleRunConflictException("FULL 运行必须通过 finish-with-outputs 登记输出后结束");
        }
        if (run.getStatus() == target) {
            return toRunVO(run);
        }
        if (run.getStatus() != RuleRunStatus.RUNNING
                && !(run.getStatus() == RuleRunStatus.QUEUED
                        && target == RuleRunStatus.FAILED)) {
            throw new RuleRunConflictException(
                    "规则运行状态为「" + run.getStatus() + "」，不能结束执行");
        }
        if (target == RuleRunStatus.SUCCEEDED) {
            requireSuccessfulNodes(run, run.getOwnerId());
        }
        String errorMessage = normalizeErrorMessage(request == null ? null : request.errorMessage());
        if (target == RuleRunStatus.SUCCEEDED) {
            errorMessage = null;
        }
        if (ruleRunMapper.updateClaimedStatus(
                runId, claimToken, executionAttempt, run.getStatus(),
                target, null, now, errorMessage, now) != 1) {
            throw lostClaim(runId);
        }
        return toRunVO(ruleRunMapper.findByIdAndOwner(runId, run.getOwnerId()));
    }

    @Override
    @Transactional
    public RuleRunVO finishClaimedRunWithOutputs(
            UUID runId,
            UUID claimToken,
            int executionAttempt,
            List<RuleOutputUpload> outputs) {
        Instant now = Instant.now();
        RuleRun run = requireClaimedRunForUpdate(runId, claimToken, executionAttempt, now);
        if (run.getMode() != RuleRunMode.FULL || run.getStatus() != RuleRunStatus.RUNNING) {
            throw new RuleRunConflictException("仅 RUNNING 的 FULL 运行可以登记物化输出");
        }
        if (outputs == null || outputs.isEmpty() || outputs.size() > 20) {
            throw new InvalidLibraryRequestException("规则输出数量必须在 1 到 20 之间");
        }
        requireSuccessfulNodes(run, run.getOwnerId());
        RuleWorkbookVersion version = requireRunVersion(run, run.getOwnerId());
        JsonNode plan = parseJson(version.getCompiledPlanJson());
        Map<String, JsonNode> expected = new HashMap<>();
        for (JsonNode node : plan.path("nodes")) {
            if (node.path("enabled").asBoolean(true)
                    && "OUTPUT".equalsIgnoreCase(node.path("kind").asText())) {
                expected.put(node.path("id").asText(), node);
            }
        }
        if (expected.size() != outputs.size()) {
            throw new InvalidLibraryRequestException("输出文件清单必须与工作簿启用的 OUTPUT 节点完全一致");
        }
        Set<String> received = new java.util.HashSet<>();
        for (RuleOutputUpload output : outputs) {
            if (output == null || output.nodeId() == null || !received.add(output.nodeId())) {
                throw new InvalidLibraryRequestException("输出节点标识缺失或重复");
            }
            JsonNode node = expected.get(output.nodeId());
            if (node == null || !node.path("config").path("name").asText().equals(output.name())) {
                throw new InvalidLibraryRequestException("输出清单与冻结工作簿配置不匹配");
            }
            if (output.file() == null || output.file().getSize() != output.sizeBytes()) {
                throw new InvalidLibraryRequestException("输出文件缺失或大小与清单不符");
            }
            RuleRunNode outputNode = ruleRunNodeMapper.findByRunIdAndNodeId(runId, output.nodeId());
            validateOutputMetrics(outputNode, output);
            datasetService.materializeRuleOutput(
                    run.getOwnerId(), run.getId(), version.getId(), output.nodeId(), output.name(),
                    output.file(), output.sha256(), output.rowCount(), output.columnCount());
        }
        if (!received.equals(expected.keySet())) {
            throw new InvalidLibraryRequestException("输出文件清单缺少启用的 OUTPUT 节点");
        }
        if (ruleRunMapper.updateClaimedStatus(
                runId, claimToken, executionAttempt, RuleRunStatus.RUNNING,
                RuleRunStatus.SUCCEEDED, null, now, null, now) != 1) {
            throw lostClaim(runId);
        }
        return toRunVO(ruleRunMapper.findByIdAndOwner(runId, run.getOwnerId()));
    }

    private void validateOutputMetrics(RuleRunNode node, RuleOutputUpload output) {
        if (node == null || node.getStatus() != RuleRunNodeStatus.SUCCEEDED
                || !"OUTPUT".equalsIgnoreCase(node.getNodeKind())
                || node.getMetricsJson() == null) {
            throw new RuleRunConflictException("输出节点尚未以 OUTPUT/SUCCEEDED 上报完整指标");
        }
        JsonNode metrics = parseJson(node.getMetricsJson());
        if (!metrics.path("sha256").asText().equals(output.sha256())
                || metrics.path("sizeBytes").asLong(-1) != output.sizeBytes()
                || metrics.path("rowCount").asLong(-1) != output.rowCount()
                || metrics.path("columnCount").asInt(-1) != output.columnCount()) {
            throw new InvalidLibraryRequestException("输出文件清单与节点执行指标不一致");
        }
    }

    @Override
    @Transactional
    public RuleRunNodeVO upsertClaimedRunNode(
            UUID runId, UUID claimToken, int executionAttempt, RuleRunNodeRequest request) {
        if (request == null) {
            throw new InvalidLibraryRequestException("节点上报请求不能为空");
        }
        Instant now = Instant.now();
        RuleRun run = requireClaimedRunForUpdate(runId, claimToken, executionAttempt, now);
        return upsertRunNodeForRun(run, request, run.getOwnerId());
    }

    private RuleRunNodeVO upsertRunNodeForRun(
            RuleRun run, RuleRunNodeRequest request, String ownerId) {
        UUID runId = run.getId();
        if (run.getStatus() != RuleRunStatus.RUNNING) {
            throw new RuleRunConflictException(
                    "规则运行状态为「" + run.getStatus() + "」，只有 RUNNING 可以上报节点");
        }
        String nodeId = normalizeNodeId(request.nodeId());
        RuleWorkbookVersion version = requireRunVersion(run, ownerId);
        JsonNode plan = parseJson(version.getCompiledPlanJson());
        JsonNode planNode = findPlanNode(plan, nodeId);
        if (planNode == null) {
            throw new InvalidLibraryRequestException("节点「" + nodeId + "」不属于当前规则版本");
        }
        String planKind = normalizeNodeKind(planNode.path("kind").asText(null));
        String requestedKind = normalizeNodeKind(request.nodeKind());
        if (requestedKind != null && planKind != null
                && !requestedKind.equalsIgnoreCase(planKind)) {
            throw new InvalidLibraryRequestException(
                    "节点「" + nodeId + "」类型为「" + planKind + "」，与上报类型「" + requestedKind + "」不一致");
        }
        RuleRunNodeStatus target = parseNodeStatus(request.status());
        RuleRunNode existing = ruleRunNodeMapper.findByRunIdAndNodeId(runId, nodeId);
        validateNodeTransition(existing == null ? null : existing.getStatus(), target);
        RuleRunNode node = existing == null ? new RuleRunNode() : existing;
        if (existing == null) {
            node.setId(UUID.randomUUID());
            node.setRunId(runId);
            node.setNodeId(nodeId);
        }
        node.setNodeKind(planKind == null ? requestedKind : planKind);
        node.setStatus(target);
        if (request.metrics() != null) {
            node.setMetricsJson(writeJson(request.metrics()));
        }
        if (request.sampleUri() != null) {
            node.setSampleUri(normalizeSampleUri(request.sampleUri()));
        }
        if (request.errorMessage() != null) {
            node.setErrorMessage(normalizeErrorMessage(request.errorMessage()));
        }
        if (node.getStartedAt() == null && target != RuleRunNodeStatus.PENDING) {
            node.setStartedAt(Instant.now());
        }
        if (target.isTerminal() && node.getFinishedAt() == null) {
            node.setFinishedAt(Instant.now());
        }
        if (existing == null) {
            ruleRunNodeMapper.insert(node);
        } else {
            ruleRunNodeMapper.update(node);
        }
        return toNodeVO(node);
    }

    private RuleRun requireRun(UUID runId) {
        RuleRun run = ruleRunMapper.findByIdAndOwner(runId, datasetOwnerProvider.currentOwnerId());
        if (run == null) {
            throw new RuleRunNotFoundException("规则运行 " + runId + " 不存在");
        }
        return run;
    }

    private RuleWorkbookVersion requireRunVersion(RuleRun run) {
        return requireRunVersion(run, datasetOwnerProvider.currentOwnerId());
    }

    private RuleWorkbookVersion requireRunVersion(RuleRun run, String ownerId) {
        RuleWorkbookVersion version = ruleWorkbookVersionMapper.findByIdAndOwner(
                run.getWorkbookVersionId(), ownerId);
        if (version == null) {
            throw new RuleRunConflictException("规则运行对应的已发布版本不存在");
        }
        if (!run.getWorkbookId().equals(version.getWorkbookId())) {
            throw new RuleRunConflictException("规则运行与规则版本不匹配");
        }
        if (!run.getDatasetVersionId().equals(version.getDatasetVersionId())) {
            throw new RuleRunConflictException("规则运行与已发布版本的数据集版本不匹配");
        }
        if (!"PUBLISHED".equalsIgnoreCase(version.getStatus())) {
            throw new RuleRunConflictException(
                    "规则运行对应的版本状态为「" + version.getStatus() + "」，不是 PUBLISHED");
        }
        return version;
    }

    private void requireSuccessfulNodes(RuleRun run) {
        requireSuccessfulNodes(run, datasetOwnerProvider.currentOwnerId());
    }

    private void requireSuccessfulNodes(RuleRun run, String ownerId) {
        RuleWorkbookVersion version = requireRunVersion(run, ownerId);
        JsonNode plan = parseJson(version.getCompiledPlanJson());
        Map<String, RuleRunNodeStatus> statuses = new HashMap<>();
        for (RuleRunNode node : ruleRunNodeMapper.findByRunId(run.getId())) {
            statuses.put(node.getNodeId(), node.getStatus());
        }
        for (JsonNode planNode : plan.path("nodes")) {
            if (!planNode.path("enabled").asBoolean(true)) {
                continue;
            }
            String nodeId = planNode.path("id").asText(null);
            if (nodeId == null) {
                continue;
            }
            RuleRunNodeStatus status = statuses.get(nodeId);
            if (status != RuleRunNodeStatus.SUCCEEDED && status != RuleRunNodeStatus.SKIPPED) {
                throw new RuleRunConflictException(
                        "节点「" + nodeId + "」状态为「" + status + "」，不能将运行标记为 SUCCEEDED");
            }
        }
    }

    private RuleRun requireClaimedRun(
            UUID runId, UUID claimToken, int executionAttempt, Instant now) {
        if (claimToken == null || executionAttempt < 1) {
            throw lostClaim(runId);
        }
        RuleRun run = ruleRunMapper.findByIdAndClaim(
                runId, claimToken, executionAttempt, now);
        if (run == null) {
            throw lostClaim(runId);
        }
        return run;
    }

    private RuleRun requireClaimedRunForUpdate(
            UUID runId, UUID claimToken, int executionAttempt, Instant now) {
        if (claimToken == null || executionAttempt < 1) {
            throw lostClaim(runId);
        }
        RuleRun run = ruleRunMapper.findByIdAndClaimForUpdate(
                runId, claimToken, executionAttempt, now);
        if (run == null) {
            throw lostClaim(runId);
        }
        return run;
    }

    private static RuleRunConflictException lostClaim(UUID runId) {
        return new RuleRunConflictException(
                "规则运行 " + runId + " 的领取已失效、租约已过期或已被新的执行接管");
    }

    private static void ensureNotClaimed(RuleRun run) {
        if (run.getClaimToken() != null) {
            throw new RuleRunConflictException(
                    "规则运行已由 Worker 领取，必须通过带 fencing 信息的内部接口更新");
        }
    }

    private static int normalizeLeaseSeconds(Integer value) {
        int leaseSeconds = value == null ? DEFAULT_LEASE_SECONDS : value;
        if (leaseSeconds < MIN_LEASE_SECONDS || leaseSeconds > MAX_LEASE_SECONDS) {
            throw new InvalidLibraryRequestException(
                    "租约秒数必须在 " + MIN_LEASE_SECONDS + " 到 " + MAX_LEASE_SECONDS + " 之间");
        }
        return leaseSeconds;
    }

    private static String normalizeClaimMode(String value) {
        String mode = value == null || value.isBlank()
                ? "ALL"
                : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "PREVIEW", "FULL").contains(mode)) {
            throw new InvalidLibraryRequestException("自动 Worker 仅支持领取 PREVIEW 或 FULL 运行");
        }
        return "ALL".equals(mode) ? null : mode;
    }

    private static JsonNode findPlanNode(JsonNode plan, String nodeId) {
        JsonNode nodes = plan.path("nodes");
        if (!nodes.isArray()) {
            return null;
        }
        for (JsonNode node : nodes) {
            if (nodeId.equals(node.path("id").asText(null))) {
                return node;
            }
        }
        return null;
    }

    private RuleRunNodeVO toNodeVO(RuleRunNode node) {
        return new RuleRunNodeVO(
                node.getId(),
                node.getRunId(),
                node.getNodeId(),
                node.getNodeKind(),
                node.getStatus(),
                node.getMetricsJson() == null ? null : parseJson(node.getMetricsJson()),
                node.getSampleUri(),
                node.getErrorMessage(),
                node.getStartedAt(),
                node.getFinishedAt());
    }

    private static String normalizeNodeId(String value) {
        String nodeId = value == null ? "" : value.trim();
        if (nodeId.isEmpty()) {
            throw new InvalidLibraryRequestException("节点 ID 不能为空");
        }
        if (nodeId.length() > 255) {
            throw new InvalidLibraryRequestException("节点 ID 不能超过 255 个字符");
        }
        return nodeId;
    }

    private static String normalizeNodeKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String kind = value.trim().toUpperCase(Locale.ROOT);
        if (kind.length() > 32) {
            throw new InvalidLibraryRequestException("节点类型不能超过 32 个字符");
        }
        return kind;
    }

    private static String normalizeSampleUri(String value) {
        if (value == null) {
            return null;
        }
        String uri = value.trim();
        if (uri.isEmpty()) {
            return null;
        }
        if (uri.length() > 1024) {
            throw new InvalidLibraryRequestException("样例行 URI 不能超过 1024 个字符");
        }
        return uri;
    }

    private static String normalizeErrorMessage(String value) {
        if (value == null) {
            return null;
        }
        String message = value.trim();
        if (message.isEmpty()) {
            return null;
        }
        if (message.length() > 2000) {
            throw new InvalidLibraryRequestException("错误信息不能超过 2000 个字符");
        }
        return message;
    }

    private static RuleRunNodeStatus parseNodeStatus(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidLibraryRequestException("节点状态不能为空");
        }
        try {
            return RuleRunNodeStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidLibraryRequestException(
                    "节点状态只能是 PENDING、RUNNING、SUCCEEDED、FAILED、SKIPPED 或 CANCELLED");
        }
    }

    private static RuleRunStatus parseFinishStatus(RuleRunFinishRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new InvalidLibraryRequestException("结束状态不能为空");
        }
        RuleRunStatus status;
        try {
            status = RuleRunStatus.valueOf(request.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidLibraryRequestException("结束状态只能是 SUCCEEDED、FAILED 或 CANCELLED");
        }
        if (status != RuleRunStatus.SUCCEEDED
                && status != RuleRunStatus.FAILED
                && status != RuleRunStatus.CANCELLED) {
            throw new InvalidLibraryRequestException("结束状态只能是 SUCCEEDED、FAILED 或 CANCELLED");
        }
        return status;
    }

    private static void validateNodeTransition(RuleRunNodeStatus current, RuleRunNodeStatus next) {
        if (current == null || current == next) {
            return;
        }
        if (current.isTerminal()) {
            throw new RuleRunConflictException(
                    "节点状态已为「" + current + "」，不能再改为「" + next + "」");
        }
        if (current == RuleRunNodeStatus.PENDING) {
            return;
        }
        if (current == RuleRunNodeStatus.RUNNING && next != RuleRunNodeStatus.PENDING) {
            return;
        }
        throw new RuleRunConflictException(
                "节点状态不能从「" + current + "」变为「" + next + "」");
    }

    private RuleWorkbook requireWorkbook(UUID id) {
        RuleWorkbook workbook = ruleWorkbookMapper.findById(id, datasetOwnerProvider.currentOwnerId());
        if (workbook == null) {
            throw new RuleWorkbookNotFoundException("规则工作簿 " + id + " 不存在");
        }
        return workbook;
    }

    private DatasetVersion requireReadyDatasetVersion(UUID datasetVersionId, String ownerId) {
        DatasetVersion version = datasetVersionMapper.findById(datasetVersionId, ownerId);
        if (version == null) {
            throw new DatasetNotFoundException(datasetVersionId);
        }
        if (version.getStatus() != DatasetStatus.READY) {
            throw new DatasetNotReadyException(
                    "数据集版本 " + datasetVersionId + " 状态为「" + version.getStatus()
                            + "」，规则发布和运行前必须 READY");
        }
        return version;
    }

    private RuleWorkbookDraft requireDraft(UUID id) {
        RuleWorkbookDraft draft = ruleWorkbookDraftMapper.findByWorkbookId(id);
        if (draft == null) {
            throw new RuleWorkbookConflictException("规则工作簿草稿不存在，请重新创建");
        }
        return draft;
    }

    private RuleCompilation compileDraft(RuleWorkbook workbook, RuleWorkbookDraft draft) {
        return compiler.compile(parseJson(draft.getDocumentJson()), workbook.getDatasetVersionId());
    }

    private RuleWorkbookVO toVO(RuleWorkbook workbook) {
        RuleWorkbookDraft draft = ruleWorkbookDraftMapper.findByWorkbookId(workbook.getId());
        return new RuleWorkbookVO(
                workbook.getId(),
                workbook.getName(),
                workbook.getDescription(),
                workbook.getDatasetVersionId(),
                workbook.getStatus(),
                draft == null ? 0 : draft.getRevision(),
                workbook.getCreatedAt(),
                workbook.getUpdatedAt());
    }

    private RuleWorkbookDetailVO toDetail(RuleWorkbook workbook, RuleWorkbookDraft draft) {
        return new RuleWorkbookDetailVO(
                workbook.getId(),
                workbook.getName(),
                workbook.getDescription(),
                workbook.getDatasetVersionId(),
                workbook.getStatus(),
                draft == null ? 0 : draft.getRevision(),
                workbook.getCreatedAt(),
                workbook.getUpdatedAt(),
                draft == null ? null : parseJson(draft.getDocumentJson()));
    }

    private RuleWorkbookVersionVO toVersionVO(RuleWorkbookVersion version) {
        return new RuleWorkbookVersionVO(
                version.getId(),
                version.getWorkbookId(),
                version.getVersionNumber(),
                version.getDatasetVersionId(),
                version.getStatus(),
                version.getContentHash(),
                version.getCreatedAt(),
                parseJson(version.getDocumentJson()),
                parseJson(version.getCompiledPlanJson()));
    }

    private RuleRunVO toRunVO(RuleRun run) {
        return new RuleRunVO(
                run.getId(),
                run.getWorkbookId(),
                run.getWorkbookVersionId(),
                run.getDatasetVersionId(),
                run.getMode(),
                run.getStatus(),
                run.getSampleSize(),
                run.getSeed(),
                run.getErrorMessage(),
                run.getRetryOfRunId(),
                run.getClaimedBy(),
                run.getLeaseExpiresAt(),
                run.getExecutionAttempt(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getFinishedAt());
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规则文档存储格式损坏", exception);
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规则文档序列化失败", exception);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String issueSummary(List<RuleValidationIssueVO> issues) {
        return issues.stream()
                .filter(issue -> "ERROR".equals(issue.severity()))
                .limit(3)
                .map(RuleValidationIssueVO::message)
                .collect(Collectors.joining("；"));
    }

    private static RuleRunMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return RuleRunMode.PREVIEW;
        }
        try {
            return RuleRunMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new InvalidLibraryRequestException("运行模式只能是 PREVIEW 或 FULL");
        }
    }

    private static String keywordPattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static String normalizeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) {
            throw new InvalidLibraryRequestException("规则库名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidLibraryRequestException("规则库名称不能超过 255 个字符");
        }
        return name;
    }

    private static String normalizeDescription(String value) {
        if (value == null) {
            return null;
        }
        String description = value.trim();
        if (description.isEmpty()) {
            return null;
        }
        return description.length() > MAX_DESCRIPTION_LENGTH
                ? description.substring(0, MAX_DESCRIPTION_LENGTH)
                : description;
    }
}
