package com.olist.analytics.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.analytics.entity.AnalysisPlan;
import com.olist.analytics.entity.AnalysisPlanVersion;
import com.olist.analytics.entity.AnalysisRun;
import com.olist.analytics.entity.AnalysisTemplate;
import com.olist.analytics.repository.AnalysisPlanRepository;
import com.olist.analytics.repository.AnalysisPlanVersionRepository;
import com.olist.analytics.repository.AnalysisRunRepository;
import com.olist.analytics.repository.AnalysisTemplateRepository;
import com.olist.analytics.spec.AnalyticsSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 分析方案：保存（版本化）、执行、历史。 */
@Service
public class PlanService {

    public record PlanRequest(String name, String description, AnalyticsSpec spec) {
    }

    public record PlanResponse(
            UUID id, String name, String description, String templateKey, int currentVersion,
            String status, AnalyticsSpec spec, Instant updatedAt) {
    }

    public record RunResponse(
            UUID id, UUID planId, Integer planVersion, String status, Integer rowCount, Integer elapsedMs,
            List<Map<String, Object>> columns, List<List<Object>> rows, String errorMessage,
            Instant createdAt, Instant finishedAt) {
    }

    public record TemplateResponse(
            String key, String name, String description, String category, AnalyticsSpec spec) {
    }

    private final AnalysisPlanRepository planRepository;
    private final AnalysisPlanVersionRepository versionRepository;
    private final AnalysisRunRepository runRepository;
    private final AnalysisTemplateRepository templateRepository;
    private final AnalyticsService analyticsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanService(
            AnalysisPlanRepository planRepository,
            AnalysisPlanVersionRepository versionRepository,
            AnalysisRunRepository runRepository,
            AnalysisTemplateRepository templateRepository,
            AnalyticsService analyticsService) {
        this.planRepository = planRepository;
        this.versionRepository = versionRepository;
        this.runRepository = runRepository;
        this.templateRepository = templateRepository;
        this.analyticsService = analyticsService;
    }

    public List<PlanResponse> list(String ownerId) {
        return planRepository.findTop100ByOwnerIdOrderByUpdatedAtDesc(ownerId).stream()
                .map(this::toResponse)
                .toList();
    }

    public PlanResponse create(String ownerId, PlanRequest request) {
        validate(request);
        Instant now = Instant.now();
        AnalysisPlan plan = planRepository.save(new AnalysisPlan(
                UUID.randomUUID(), ownerId, request.name().trim(), request.description(), null, now));
        versionRepository.save(new AnalysisPlanVersion(
                UUID.randomUUID(), plan.getId(), 1, json(request.spec()), null, ownerId, now));
        return toResponse(plan, request.spec());
    }

    public PlanResponse get(String ownerId, UUID id) {
        AnalysisPlan plan = require(ownerId, id);
        return toResponse(plan);
    }

    public PlanResponse update(String ownerId, UUID id, PlanRequest request) {
        validate(request);
        AnalysisPlan plan = require(ownerId, id);
        Instant now = Instant.now();
        int nextVersion = plan.getCurrentVersion() + 1;
        plan.update(request.name().trim(), request.description(), nextVersion, now);
        planRepository.save(plan);
        versionRepository.save(new AnalysisPlanVersion(
                UUID.randomUUID(), id, nextVersion, json(request.spec()), null, ownerId, now));
        return toResponse(plan, request.spec());
    }

    public void delete(String ownerId, UUID id) {
        if (planRepository.deleteByIdAndOwnerId(id, ownerId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析方案不存在");
        }
    }

    public RunResponse run(String ownerId, UUID planId, String authorization) {
        AnalysisPlan plan = require(ownerId, planId);
        AnalysisPlanVersion version = versionRepository.findByPlanIdAndVersionNo(planId, plan.getCurrentVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "方案版本缺失"));
        AnalyticsSpec spec = parse(version.getSpecJson());
        return execute(ownerId, planId, version.getVersionNo(), spec, authorization);
    }

    public List<RunResponse> runs(String ownerId, UUID planId) {
        List<AnalysisRun> runs = planId == null
                ? runRepository.findTop50ByOwnerIdOrderByCreatedAtDesc(ownerId)
                : runRepository.findTop50ByOwnerIdAndPlanIdOrderByCreatedAtDesc(ownerId, planId);
        return runs.stream().map(this::toRunResponse).toList();
    }

    public RunResponse getRun(String ownerId, UUID runId) {
        return toRunResponse(runRepository.findByIdAndOwnerId(runId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在")));
    }

    public List<TemplateResponse> templates() {
        return templateRepository.findAllByOrderByCategoryAscNameAsc().stream()
                .map(template -> new TemplateResponse(
                        template.getTemplateKey(), template.getName(), template.getDescription(),
                        template.getCategory(), parse(template.getSpecJson())))
                .toList();
    }

    public PlanResponse copyTemplate(String ownerId, String templateKey) {
        AnalysisTemplate template = templateRepository.findById(templateKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在"));
        AnalyticsSpec spec = parse(template.getSpecJson());
        Instant now = Instant.now();
        AnalysisPlan plan = planRepository.save(new AnalysisPlan(
                UUID.randomUUID(), ownerId, template.getName() + "（副本）", template.getDescription(),
                templateKey, now));
        versionRepository.save(new AnalysisPlanVersion(
                UUID.randomUUID(), plan.getId(), 1, json(spec), null, ownerId, now));
        return toResponse(plan, spec);
    }

    // ------------------------------------------------------------------ helpers

    private RunResponse execute(String ownerId, UUID planId, Integer planVersion, AnalyticsSpec spec,
            String authorization) {
        String specJson = json(spec);
        AnalysisRun run = new AnalysisRun(
                UUID.randomUUID(), ownerId, planId, planVersion,
                UUID.fromString(spec.source().datasetVersionId()),
                UUID.fromString(spec.source().tableId()),
                specJson, Integer.toHexString(specJson.hashCode()), Instant.now());
        try {
            Map<String, Object> result = analyticsService.query(
                    run.getDatasetVersionId(), run.getTableId(), spec, ownerId, authorization);
            int rows = result.get("rowCount") instanceof Number number ? number.intValue() : 0;
            int elapsed = result.get("elapsedMs") instanceof Number number ? number.intValue() : 0;
            run.succeed(rows, elapsed, json(result.get("columns")), json(result.get("rows")), Instant.now());
        } catch (RuntimeException error) {
            run.fail(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), Instant.now());
        }
        runRepository.save(run);
        return toRunResponse(run);
    }

    private AnalysisPlan require(String ownerId, UUID id) {
        return planRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "分析方案不存在"));
    }

    private void validate(PlanRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "方案名称不能为空");
        }
        if (request.spec() == null || request.spec().source() == null
                || request.spec().source().datasetVersionId() == null
                || request.spec().source().tableId() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "方案必须指定数据版本和数据表");
        }
        if (request.spec().metrics() == null || request.spec().metrics().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "方案至少需要一个指标");
        }
    }

    private PlanResponse toResponse(AnalysisPlan plan) {
        AnalyticsSpec spec = versionRepository.findTopByPlanIdOrderByVersionNoDesc(plan.getId())
                .map(version -> parse(version.getSpecJson()))
                .orElse(null);
        return toResponse(plan, spec);
    }

    private PlanResponse toResponse(AnalysisPlan plan, AnalyticsSpec spec) {
        return new PlanResponse(plan.getId(), plan.getName(), plan.getDescription(), plan.getTemplateKey(),
                plan.getCurrentVersion(), plan.getStatus(), spec, plan.getUpdatedAt());
    }

    private RunResponse toRunResponse(AnalysisRun run) {
        return new RunResponse(run.getId(), run.getPlanId(), run.getPlanVersion(), run.getStatus(),
                run.getRowCount(), run.getElapsedMs(),
                readList(run.getResultColumnsJson()), readRows(run.getResultRowsJson()),
                run.getErrorMessage(), run.getCreatedAt(), run.getFinishedAt());
    }

    private AnalyticsSpec parse(String json) {
        try {
            return objectMapper.readValue(json, AnalyticsSpec.class);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "方案规格解析失败");
        }
    }

    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception error) {
            return List.of();
        }
    }

    private List<List<Object>> readRows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<List<Object>>>() {});
        } catch (Exception error) {
            return List.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("序列化失败", error);
        }
    }
}
