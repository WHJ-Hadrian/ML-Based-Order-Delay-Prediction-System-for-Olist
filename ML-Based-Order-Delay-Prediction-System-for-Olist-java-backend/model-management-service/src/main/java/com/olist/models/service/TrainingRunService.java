package com.olist.models.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.olist.models.api.ModelTrainingRequest;
import com.olist.models.api.TrainingRunPageResponse;
import com.olist.models.api.TrainingRunResponse;
import com.olist.models.entity.ModelVersion;
import com.olist.models.entity.TrainingRun;
import com.olist.models.repository.ModelVersionRepository;
import com.olist.models.repository.TrainingRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TrainingRunService {

    private final TrainingRunRepository repository;
    private final ModelVersionRepository modelVersionRepository;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public TrainingRunService(
            TrainingRunRepository repository,
            ModelVersionRepository modelVersionRepository,
            ObjectMapper objectMapper,
            @Value("${olist.model-training.worker.max-attempts:3}") int maxAttempts) {
        this.repository = repository;
        this.modelVersionRepository = modelVersionRepository;
        this.objectMapper = objectMapper;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Transactional
    public TrainingRunResponse submit(
            String ownerId, String bearerToken, ModelTrainingRequest request, String idempotencyKey) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        String requestJson = json(request);
        if (key != null) {
            Optional<TrainingRun> existing = repository.findByOwnerIdAndIdempotencyKey(ownerId, key);
            if (existing.isPresent()) {
                if (!requestJson.equals(existing.get().getRequestJson())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "同一 Idempotency-Key 不能提交不同的训练请求");
                }
                return toResponse(existing.get());
            }
        }
        Instant now = Instant.now();
        TrainingRun run = new TrainingRun(
                UUID.randomUUID(), ownerId, request.datasetId(), request.datasetVersionId(),
                request.tableId(), request.name(), request.task(), request.algorithm(),
                request.targetColumn(), json(request.featureColumns()), request.testSize(),
                request.randomState(), key, requestJson, bearerToken, now);
        return toResponse(repository.save(run));
    }

    @Transactional(readOnly = true)
    public TrainingRunResponse replay(
            String ownerId, ModelTrainingRequest request, String idempotencyKey) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "训练运行并发提交冲突，请重试");
        }
        TrainingRun existing = repository.findByOwnerIdAndIdempotencyKey(ownerId, key)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "训练运行并发提交冲突，请重试"));
        if (!json(request).equals(existing.getRequestJson())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "同一 Idempotency-Key 不能提交不同的训练请求");
        }
        return toResponse(existing);
    }

    @Transactional(readOnly = true)
    public TrainingRunResponse get(String ownerId, UUID id) {
        return toResponse(repository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "训练运行不存在")));
    }

    @Transactional(readOnly = true)
    public TrainingRunPageResponse list(String ownerId, int page, int size) {
        int pageIndex = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 100));
        Page<TrainingRun> result = repository.findAllByOwnerIdOrderByCreatedAtDesc(
                ownerId, PageRequest.of(pageIndex, pageSize));
        return new TrainingRunPageResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public TrainingRunResponse cancel(String ownerId, UUID id) {
        TrainingRun run = repository.lockByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "训练运行不存在"));
        if (!TrainingRun.QUEUED.equals(run.getStatus()) && !TrainingRun.RUNNING.equals(run.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "训练运行当前状态不允许取消: " + run.getStatus());
        }
        run.cancel(Instant.now());
        return toResponse(repository.save(run));
    }

    @Transactional
    public List<TrainingRun> claimBatch(int limit, Duration lease) {
        Instant now = Instant.now();
        Instant leaseUntil = now.plus(lease);
        int effectiveLimit = Math.max(1, Math.min(limit, 20));
        List<TrainingRun> claimed = new ArrayList<>();
        for (TrainingRun candidate : repository.findTop20ByStatusOrderByCreatedAtAsc(TrainingRun.QUEUED)) {
            if (claimed.size() >= effectiveLimit) {
                break;
            }
            String claimToken = UUID.randomUUID().toString();
            if (repository.claimQueued(candidate.getId(), claimToken, leaseUntil, now) == 1) {
                repository.findById(candidate.getId()).ifPresent(claimed::add);
            }
        }
        for (TrainingRun candidate : repository
                .findTop20ByStatusAndLeaseExpiresAtBeforeOrderByCreatedAtAsc(TrainingRun.RUNNING, now)) {
            if (claimed.size() >= effectiveLimit) {
                break;
            }
            if (candidate.getAttempts() >= maxAttempts) {
                repository.failExpired(candidate.getId(), "租约过期且已达到最大重试次数", now);
                continue;
            }
            String claimToken = UUID.randomUUID().toString();
            if (repository.reclaimExpired(candidate.getId(), claimToken, leaseUntil, now) == 1) {
                repository.findById(candidate.getId()).ifPresent(claimed::add);
            }
        }
        return claimed;
    }

    @Transactional
    public boolean updateStage(UUID id, String claimToken, String stage, int progress) {
        return repository.updateStage(id, claimToken, stage, Math.min(progress, 99), Instant.now()) == 1;
    }

    @Transactional
    public boolean renewLease(UUID id, String claimToken, Duration lease) {
        Instant now = Instant.now();
        return repository.renewLease(id, claimToken, now.plus(lease), now) == 1;
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(UUID id) {
        return repository.findById(id)
                .map(run -> TrainingRun.CANCELLED.equals(run.getStatus()))
                .orElse(true);
    }

    @Transactional
    public TrainingRunResponse complete(UUID id, String claimToken, ModelVersion model, String resultJson) {
        TrainingRun run = repository.lockById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "训练运行不存在"));
        if (!TrainingRun.RUNNING.equals(run.getStatus())
                || run.getClaimToken() == null
                || !run.getClaimToken().equals(claimToken)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "训练运行已取消或租约已失效");
        }
        ModelVersion saved = modelVersionRepository.save(model);
        run.succeed(saved.getId(), saved.getArtifactId(), resultJson, Instant.now());
        return toResponse(repository.save(run));
    }

    @Transactional
    public void fail(UUID id, String claimToken, String message) {
        repository.lockById(id).ifPresent(run -> {
            if (!TrainingRun.RUNNING.equals(run.getStatus())
                    || run.getClaimToken() == null
                    || !run.getClaimToken().equals(claimToken)) {
                return;
            }
            run.fail(message == null || message.isBlank() ? "训练失败" : message, Instant.now());
            repository.save(run);
        });
    }

    private TrainingRunResponse toResponse(TrainingRun run) {
        return new TrainingRunResponse(
                run.getId(), run.getStatus(), run.getStage(), run.getProgress(),
                "/api/v1/models/training-runs/" + run.getId(), run.getDatasetId(),
                run.getDatasetVersionId(), run.getTableId(), run.getName(), run.getTask(),
                run.getAlgorithm(), run.getTargetColumn(),
                read(run.getFeatureColumnsJson(), new TypeReference<>() {}),
                run.getTestSize(), run.getRandomState(), run.getModelVersionId(),
                run.getArtifactId(), readNullable(run.getResultJson()),
                run.getErrorMessage(), run.getAttempts(), run.getCreatedAt(),
                run.getStartedAt(), run.getFinishedAt(), run.getUpdatedAt());
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 255) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency-Key 不能超过 255 个字符");
        }
        return normalized;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化训练运行数据", error);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("训练运行数据已损坏", error);
        }
    }

    private Map<String, Object> readNullable(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return read(json, new TypeReference<>() {});
    }
}
